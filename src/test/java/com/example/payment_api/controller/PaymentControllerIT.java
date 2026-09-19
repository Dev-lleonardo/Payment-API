package com.example.payment_api.controller;

import com.example.payment_api.AbstractIntegrationTest;
import com.example.payment_api.dto.PaymentRequest;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.PaymentMethod;
import com.example.payment_api.entity.PaymentStatus;
import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.UserRepository;
import com.example.payment_api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Passo 10.5b — mesmo padrão do AuthControllerIT (10.5a): @SpringBootTest +
// MockMvc real, Postgres real via Testcontainers, GlobalExceptionHandler real.
//
// Diferença aqui: todo endpoint de /payments exige autenticação (SecurityConfig
// só libera /auth/** e /webhooks/payment). Em vez de bater em /auth/login pra
// cada teste, geramos o JWT direto com JwtService.generateToken(user) — o login
// em si já está coberto no AuthControllerIT, então aqui só precisamos de um
// token válido pra exercitar o filtro e a lógica de escopo USER/ADMIN.
@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JwtService jwtService;

    // O container Postgres é compartilhado (static) por TODA a classe de
    // teste — sem isso, um teste de listagem (que traz "tudo" pro ADMIN)
    // enxergaria também os pagamentos deixados por testes anteriores.
    // Ordem importa: payments tem FK pra users.
    @BeforeEach
    void cleanDatabase() {
        paymentRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------
    // POST /payments
    // ---------------------------------------------------------------

    @Test
    void create_withValidDataAndIdempotencyKey_returns201WithProcessingStatus() throws Exception {
        User user = createUser(Role.USER);
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), PaymentMethod.PIX);
        String idempotencyKey = uniqueKey();

        mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.paymentMethod").value("PIX"))
                // A entidade já sai de PENDING pra PROCESSING dentro do próprio
                // createPayment() — não existe pagamento visível em PENDING via API.
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey));

        Payment persisted = paymentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(persisted.getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    void create_withoutIdempotencyKeyHeader_returns400() throws Exception {
        User user = createUser(Role.USER);
        PaymentRequest request = new PaymentRequest("order-2", new BigDecimal("50.00"), PaymentMethod.CREDIT_CARD);

        mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withInvalidBody_returns400() throws Exception {
        User user = createUser(Role.USER);
        PaymentRequest request = new PaymentRequest("", new BigDecimal("-10.00"), null);

        mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("orderId is required")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("amount must be greater than zero")));
    }

    @Test
    void create_resendingSameIdempotencyKeyWithSameData_returnsSamePaymentWithoutDuplicating() throws Exception {
        User user = createUser(Role.USER);
        String idempotencyKey = uniqueKey();
        PaymentRequest request = new PaymentRequest("order-3", new BigDecimal("75.00"), PaymentMethod.DEBIT_CARD);

        String firstResponse = mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long firstId = jsonMapper.readTree(firstResponse).get("id").asLong();

        mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(firstId));

        assertThat(paymentRepository.countByIdempotencyKey(idempotencyKey)).isEqualTo(1);
    }

    @Test
    void create_resendingSameIdempotencyKeyWithDifferentData_returns409() throws Exception {
        User user = createUser(Role.USER);
        String idempotencyKey = uniqueKey();
        PaymentRequest original = new PaymentRequest("order-4", new BigDecimal("75.00"), PaymentMethod.DEBIT_CARD);
        PaymentRequest conflicting = new PaymentRequest("order-4-different", new BigDecimal("75.00"), PaymentMethod.DEBIT_CARD);

        mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(conflicting)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        PaymentRequest request = new PaymentRequest("order-5", new BigDecimal("20.00"), PaymentMethod.PIX);

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // GET /payments/{id}
    // ---------------------------------------------------------------

    @Test
    void getById_asOwner_returns200() throws Exception {
        User owner = createUser(Role.USER);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(get("/payments/{id}", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId()))
                .andExpect(jsonPath("$.userId").value(owner.getId()));
    }

    @Test
    void getById_asNonOwnerUser_returns404() throws Exception {
        User owner = createUser(Role.USER);
        User otherUser = createUser(Role.USER);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(get("/payments/{id}", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(otherUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_asAdmin_returns200EvenForOthersPayment() throws Exception {
        User owner = createUser(Role.USER);
        User admin = createUser(Role.ADMIN);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(get("/payments/{id}", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId()));
    }

    @Test
    void getById_withNonexistentId_returns404() throws Exception {
        User user = createUser(Role.USER);

        mockMvc.perform(get("/payments/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------
    // GET /payments (lista paginada)
    // ---------------------------------------------------------------

    @Test
    void list_asUser_returnsOnlyOwnPayments() throws Exception {
        User user = createUser(Role.USER);
        User otherUser = createUser(Role.USER);
        Payment ownPayment = createPersistedPayment(user);
        createPersistedPayment(otherUser);

        mockMvc.perform(get("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ownPayment.getId()));
    }

    @Test
    void list_asAdmin_returnsPaymentsFromAllUsers() throws Exception {
        User userA = createUser(Role.USER);
        User userB = createUser(Role.USER);
        User admin = createUser(Role.ADMIN);
        createPersistedPayment(userA);
        createPersistedPayment(userB);

        mockMvc.perform(get("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // ---------------------------------------------------------------
    // POST /payments/{id}/cancel
    // ---------------------------------------------------------------

    @Test
    void cancel_asOwnerWithProcessingPayment_returns200WithCancelledStatus() throws Exception {
        User owner = createUser(Role.USER);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(post("/payments/{id}/cancel", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void cancel_asNonOwnerUser_returns404() throws Exception {
        User owner = createUser(Role.USER);
        User otherUser = createUser(Role.USER);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(post("/payments/{id}/cancel", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(otherUser)))
                .andExpect(status().isNotFound());

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void cancel_alreadyCancelledPayment_returns409() throws Exception {
        User owner = createUser(Role.USER);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(post("/payments/{id}/cancel", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/payments/{id}/cancel", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void cancel_asAdmin_canCancelOthersPayment() throws Exception {
        User owner = createUser(Role.USER);
        User admin = createUser(Role.ADMIN);
        Payment payment = createPersistedPayment(owner);

        mockMvc.perform(post("/payments/{id}/cancel", payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private User createUser(Role role) {
        return userRepository.save(new User("Test User", uniqueEmail(), "irrelevant-hash", role));
    }

    // Cria o pagamento direto via repositório, pulando o endpoint POST — os
    // testes de criação já cobrem esse caminho; aqui só precisamos de um
    // pagamento existente (em PROCESSING, igual sairia da API) como fixture.
    private Payment createPersistedPayment(User user) {
        Payment payment = new Payment(
                "order-" + UUID.randomUUID(),
                user,
                new BigDecimal("100.00"),
                PaymentMethod.PIX,
                uniqueKey());
        payment.startProcessing();
        return paymentRepository.save(payment);
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private String uniqueEmail() {
        return "payment-it-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }
}
