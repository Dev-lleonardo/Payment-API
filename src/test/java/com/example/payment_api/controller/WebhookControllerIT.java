package com.example.payment_api.controller;

import com.example.payment_api.AbstractIntegrationTest;
import com.example.payment_api.dto.WebhookPaymentRequest;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.PaymentMethod;
import com.example.payment_api.entity.PaymentStatus;
import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;
import com.example.payment_api.entity.WebhookEvent;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.UserRepository;
import com.example.payment_api.repository.WebhookEventRepository;
import com.example.payment_api.security.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Passo 10.5c — mesmo padrão dos ITs anteriores (@SpringBootTest + MockMvc +
// Testcontainers), mas /webhooks/payment é o único endpoint PÚBLICO da API
// (liberado no SecurityConfig): não tem JWT aqui, então nenhum dos helpers
// de token do PaymentControllerIT se aplica.
//
// O que garante autenticidade é a assinatura HMAC. Por isso o corpo de cada
// request é montado como String CRUA (via jsonMapper.writeValueAsString) e
// assinado com WebhookSignatureVerifier.sign(rawBody) SOBRE ESSA MESMA
// STRING — exatamente o que o WebhookController faz ao receber @RequestBody
// String rawBody e recalcular o HMAC em cima dela. Assinar uma coisa e
// mandar outra no corpo é precisamente o cenário do teste de assinatura
// inválida (signature mismatch).
@SpringBootTest
@AutoConfigureMockMvc
class WebhookControllerIT extends AbstractIntegrationTest {

    private static final String WEBHOOK_PATH = "/webhooks/payment";
    private static final String SIGNATURE_HEADER = "X-Webhook-Signature";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

    // Mesmo motivo do PaymentControllerIT: o container Postgres é static,
    // compartilhado por toda a classe — sem limpar, contagens como
    // "só 1 WebhookEvent persistido" ficariam reféns de testes anteriores.
    @BeforeEach
    void cleanDatabase() {
        webhookEventRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------
    // Processamento de evento válido
    // ---------------------------------------------------------------

    @Test
    void receivePaymentEvent_withApprovedStatus_returns200AndApprovesPayment() throws Exception {
        Payment payment = createProcessingPayment();
        String rawBody = webhookJson(payment.getId(), PaymentStatus.APPROVED, uniqueEventId());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk());

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(webhookEventRepository.findAll()).hasSize(1);
    }

    @Test
    void receivePaymentEvent_withDeclinedStatus_returns200AndDeclinesPayment() throws Exception {
        Payment payment = createProcessingPayment();
        String rawBody = webhookJson(payment.getId(), PaymentStatus.DECLINED, uniqueEventId());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk());

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    // ---------------------------------------------------------------
    // Autenticidade (assinatura)
    // ---------------------------------------------------------------

    @Test
    void receivePaymentEvent_withInvalidSignature_returns401() throws Exception {
        Payment payment = createProcessingPayment();
        String rawBody = webhookJson(payment.getId(), PaymentStatus.APPROVED, uniqueEventId());
        String signatureOfAnotherBody = signatureVerifier.sign("{\"different\":\"payload\"}");

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureOfAnotherBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isUnauthorized());

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void receivePaymentEvent_withoutSignatureHeader_returns400() throws Exception {
        Payment payment = createProcessingPayment();
        String rawBody = webhookJson(payment.getId(), PaymentStatus.APPROVED, uniqueEventId());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // Validação do payload (assinatura correta, conteúdo inválido)
    // ---------------------------------------------------------------

    @Test
    void receivePaymentEvent_withMalformedJson_returns400() throws Exception {
        String rawBody = "{ isto nao e json valido ";

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed webhook payload"));
    }

    @Test
    void receivePaymentEvent_withBlankEventId_returns400() throws Exception {
        Payment payment = createProcessingPayment();
        String rawBody = webhookJson(payment.getId(), PaymentStatus.APPROVED, "");

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("eventId is required"));
    }

    @Test
    void receivePaymentEvent_withUnsupportedStatus_returns400() throws Exception {
        Payment payment = createProcessingPayment();
        // PENDING é um status interno nosso — nunca algo que um gateway
        // externo deveria poder notificar via webhook.
        String rawBody = webhookJson(payment.getId(), PaymentStatus.PENDING, uniqueEventId());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Unsupported webhook status")));
    }

    @Test
    void receivePaymentEvent_withNonexistentPaymentId_returns404() throws Exception {
        String rawBody = webhookJson(999_999_999L, PaymentStatus.APPROVED, uniqueEventId());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------
    // Idempotência e transição de estado inválida
    // ---------------------------------------------------------------

    @Test
    void receivePaymentEvent_resendingSameEventId_doesNotReprocess() throws Exception {
        Payment payment = createProcessingPayment();
        String eventId = uniqueEventId();
        String rawBody = webhookJson(payment.getId(), PaymentStatus.APPROVED, eventId);
        String signature = signatureVerifier.sign(rawBody);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk());

        // Reentrega do MESMO evento (retry/timeout do gateway): a segunda
        // chamada não pode falhar nem duplicar o WebhookEvent. Se o service
        // não checasse o eventId antes de mutar, approve() explodiria aqui
        // com InvalidPaymentStateException, porque o pagamento já não está
        // mais em PROCESSING.
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk());

        assertThat(webhookEventRepository.findAll()).hasSize(1);
        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void receivePaymentEvent_forPaymentNotInProcessing_returns409() throws Exception {
        Payment payment = createProcessingPayment();
        payment.cancel();
        paymentRepository.save(payment);

        String rawBody = webhookJson(payment.getId(), PaymentStatus.APPROVED, uniqueEventId());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header(SIGNATURE_HEADER, signatureVerifier.sign(rawBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isConflict());

        // O evento não pode ter sido gravado: como a mutação falhou, um
        // eventual retry legítimo (com um pagamento em estado válido) ainda
        // precisa conseguir reprocessar do zero.
        assertThat(webhookEventRepository.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private Payment createProcessingPayment() {
        User user = userRepository.save(new User("Test User", uniqueEmail(), "irrelevant-hash", Role.USER));
        Payment payment = new Payment(
                "order-" + UUID.randomUUID(),
                user,
                new BigDecimal("100.00"),
                PaymentMethod.PIX,
                "key-" + UUID.randomUUID());
        payment.startProcessing();
        return paymentRepository.save(payment);
    }

    private String webhookJson(Long paymentId, PaymentStatus status, String eventId) {
        return jsonMapper.writeValueAsString(new WebhookPaymentRequest(paymentId, status, eventId));
    }

    private String uniqueEventId() {
        return "evt-" + UUID.randomUUID();
    }

    private String uniqueEmail() {
        return "webhook-it-" + UUID.randomUUID() + "@example.com";
    }
}
