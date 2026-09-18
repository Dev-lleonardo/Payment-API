package com.example.payment_api.service;

import com.example.payment_api.dto.PaymentRequest;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.PaymentMethod;
import com.example.payment_api.entity.PaymentStatus;
import com.example.payment_api.entity.User;
import com.example.payment_api.exception.IdempotencyKeyConflictException;
import com.example.payment_api.exception.PaymentNotFoundException;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Teste UNITÁRIO do PaymentService: PaymentRepository, UserRepository e o
// próprio PaymentService ("self", o proxy @Lazy da FASE 7) são todos
// dublês do Mockito — nada de Spring, nada de banco. Isso testa a
// ORQUESTRAÇÃO da classe (decisões de fluxo: já existe a chave? precisa
// inserir? precisa recuperar de uma corrida?), não o comportamento
// transacional em si — @Transactional só existe de verdade com um proxy
// Spring por trás, e isso é papel do PaymentIdempotencyConcurrencyTest
// (integração, Testcontainers), não deste arquivo.
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private UserRepository userRepository;

    // Dublê do próprio PaymentService, no lugar do proxy @Lazy que o
    // Spring injetaria em produção — aqui não existe proxy nenhum, então
    // "self" vira só mais uma dependência mockada.
    @Mock
    private PaymentService self;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, userRepository, self);
    }

    // ---- createPayment ------------------------------------------------

    @Test
    void createPayment_noExistingIdempotencyKey_delegatesToSelfAndReturnsInsertedPayment() {
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), PaymentMethod.PIX);
        Payment inserted = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");

        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(self.insertAndStartProcessing(request, "key-1", 7L)).thenReturn(inserted);

        Payment result = paymentService.createPayment(request, "key-1", 7L);

        assertThat(result).isSameAs(inserted);
        verify(self).insertAndStartProcessing(request, "key-1", 7L);
    }

    @Test
    void createPayment_existingIdempotencyKeyWithSameData_returnsExistingWithoutInserting() {
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), PaymentMethod.PIX);
        Payment existing = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");

        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        Payment result = paymentService.createPayment(request, "key-1", 7L);

        assertThat(result).isSameAs(existing);
        // O ponto central da idempotência: uma chave repetida com os
        // mesmos dados NUNCA deve tentar inserir de novo.
        verify(self, never()).insertAndStartProcessing(any(), any(), any());
    }

    @Test
    void createPayment_existingIdempotencyKeyWithDifferentAmount_throwsConflict() {
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("999.00"), PaymentMethod.PIX);
        Payment existing = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");

        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> paymentService.createPayment(request, "key-1", 7L))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        verify(self, never()).insertAndStartProcessing(any(), any(), any());
    }

    @Test
    void createPayment_concurrentInsertRace_recoversByFindingTheWinningPayment() {
        // Simula a corrida da FASE 7: quando este método checou, a chave
        // ainda não existia — mas outra thread inseriu primeiro entre a
        // checagem e o insert, e o banco reagiu com a UNIQUE constraint.
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), PaymentMethod.PIX);
        Payment concurrentlyCreated = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");

        when(paymentRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())              // 1ª checagem: nada ainda
                .thenReturn(Optional.of(concurrentlyCreated)); // recuperação após a corrida
        when(self.insertAndStartProcessing(request, "key-1", 7L))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        Payment result = paymentService.createPayment(request, "key-1", 7L);

        assertThat(result).isSameAs(concurrentlyCreated);
    }

    @Test
    void createPayment_concurrentInsertRaceButRecoveryFindsNothing_rethrowsOriginalException() {
        // Caso extremo, quase impossível na prática (a linha que causou a
        // violação de constraint deveria estar visível logo em seguida),
        // mas o código precisa de um fallback: se a recuperação também não
        // encontra nada, a exceção original não pode ser engolida.
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), PaymentMethod.PIX);
        DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");

        when(paymentRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(self.insertAndStartProcessing(request, "key-1", 7L)).thenThrow(original);

        assertThatThrownBy(() -> paymentService.createPayment(request, "key-1", 7L))
                .isSameAs(original);
    }

    // ---- getPayment -----------------------------------------------------

    @Test
    void getPayment_owner_returnsPayment() {
        Payment payment = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        Payment result = paymentService.getPayment(1L, 7L, false);

        assertThat(result).isSameAs(payment);
    }

    @Test
    void getPayment_admin_returnsAnyUsersPayment() {
        Payment payment = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        // currentUserId=999 não é dono (7L), mas isAdmin=true libera acesso.
        Payment result = paymentService.getPayment(1L, 999L, true);

        assertThat(result).isSameAs(payment);
    }

    @Test
    void getPayment_nonOwnerNonAdmin_throwsNotFound_notForbidden() {
        Payment payment = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        // Regra de segurança da FASE 9/6: não vazamos que o recurso existe
        // e é de outra pessoa — o erro é "não encontrado" (404), igual ao
        // de um ID que nem existe, nunca um 403 explícito.
        assertThatThrownBy(() -> paymentService.getPayment(1L, 999L, false))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void getPayment_nonexistentId_throwsNotFound() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(1L, 7L, false))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    // ---- listPayments ---------------------------------------------------

    @Test
    void listPayments_admin_returnsAllPayments() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Payment> allPayments = new PageImpl<>(List.of());
        when(paymentRepository.findAll(pageable)).thenReturn(allPayments);

        Page<Payment> result = paymentService.listPayments(7L, true, pageable);

        assertThat(result).isSameAs(allPayments);
        verify(paymentRepository, never()).findByUserId(any(), any());
    }

    @Test
    void listPayments_regularUser_returnsOnlyOwnPayments() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Payment> ownPayments = new PageImpl<>(List.of());
        when(paymentRepository.findByUserId(eq(7L), eq(pageable))).thenReturn(ownPayments);

        Page<Payment> result = paymentService.listPayments(7L, false, pageable);

        assertThat(result).isSameAs(ownPayments);
        verify(paymentRepository, never()).findAll(any(Pageable.class));
    }

    // ---- cancelPayment ----------------------------------------------------

    @Test
    void cancelPayment_owner_cancelsSuccessfully() {
        Payment payment = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        Payment result = paymentService.cancelPayment(1L, 7L, false);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void cancelPayment_admin_canCancelAnyUsersPayment() {
        Payment payment = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        Payment result = paymentService.cancelPayment(1L, 999L, true);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void cancelPayment_nonOwnerNonAdmin_throwsNotFoundAndLeavesPaymentUntouched() {
        Payment payment = newPayment("order-1", mockUser(7L), new BigDecimal("100.00"), PaymentMethod.PIX, "key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.cancelPayment(1L, 999L, false))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // ---- helpers ------------------------------------------------------

    // lenient() de propósito: este helper é reaproveitado por testes que
    // NUNCA chegam a chamar user.getId() de verdade — por exemplo, quando
    // isAdmin=true, o curto-circuito do && em requireOwnershipOrAdmin
    // nem avalia o dono; e sameOrConflict() não olha pra dono nenhum. O
    // modo estrito padrão do Mockito (MockitoExtension) trata um stub
    // configurado e nunca usado como erro (UnnecessaryStubbingException)
    // — uma proteção legítima contra configuração morta no teste. lenient()
    // avisa explicitamente "este stub é opcional, dependendo do caminho
    // que o teste realmente percorre", sem desligar a checagem estrita
    // pros outros stubs do arquivo.
    private User mockUser(Long id) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(id);
        return user;
    }

    private Payment newPayment(String orderId, User user, BigDecimal amount, PaymentMethod method, String idempotencyKey) {
        return new Payment(orderId, user, amount, method, idempotencyKey);
    }
}
