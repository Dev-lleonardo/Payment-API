package com.example.payment_api.entity;

import com.example.payment_api.exception.InvalidPaymentStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Teste UNITÁRIO puro: nada de Spring, nada de banco, nada de Mockito —
// só a entidade Payment isolada, testando a máquina de estados descrita
// no plano do projeto. É rápido (milissegundos) porque não sobe contexto
// nenhum; é exatamente o tipo de caso que deve virar teste unitário,
// diferente do PaymentIdempotencyConcurrencyTest (integração, precisa de
// banco de verdade pra testar concorrência).
class PaymentTest {

    @Test
    void newPayment_startsAsPending() {
        Payment payment = newPendingPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void startProcessing_fromPending_movesToProcessing() {
        Payment payment = newPendingPayment();

        payment.startProcessing();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void startProcessing_fromAnyNonPendingStatus_throws(PaymentStatus status) {
        Payment payment = paymentInStatus(status);

        assertThatThrownBy(payment::startProcessing)
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void approve_fromProcessing_movesToApproved() {
        Payment payment = newPendingPayment();
        payment.startProcessing();

        payment.approve();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PROCESSING", mode = EnumSource.Mode.EXCLUDE)
    void approve_fromAnyNonProcessingStatus_throws(PaymentStatus status) {
        Payment payment = paymentInStatus(status);

        assertThatThrownBy(payment::approve)
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void decline_fromProcessing_movesToDeclined() {
        Payment payment = newPendingPayment();
        payment.startProcessing();

        payment.decline();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PROCESSING", mode = EnumSource.Mode.EXCLUDE)
    void decline_fromAnyNonProcessingStatus_throws(PaymentStatus status) {
        Payment payment = paymentInStatus(status);

        assertThatThrownBy(payment::decline)
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void cancel_fromPending_movesToCancelled() {
        Payment payment = newPendingPayment();

        payment.cancel();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void cancel_fromProcessing_movesToCancelled() {
        Payment payment = newPendingPayment();
        payment.startProcessing();

        payment.cancel();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "PROCESSING"}, mode = EnumSource.Mode.EXCLUDE)
    void cancel_fromApprovedDeclinedOrCancelled_throws(PaymentStatus status) {
        Payment payment = paymentInStatus(status);

        assertThatThrownBy(payment::cancel)
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining(status.name());
    }

    // ---- helpers ---------------------------------------------------

    private Payment newPendingPayment() {
        User user = new User("Test User", "test-" + UUID.randomUUID() + "@example.com", "hashed-password", Role.USER);
        return new Payment("order-1", user, new BigDecimal("100.00"), PaymentMethod.PIX, "idem-" + UUID.randomUUID());
    }

    // Leva um Payment recém-criado até o status pedido passando pelas
    // TRANSIÇÕES VÁLIDAS de verdade (nunca forçando o campo por reflection
    // ou setter direto) — assim o próprio cenário de teste já é consistente
    // com a máquina de estados real, e não corre o risco de testar um
    // estado que a aplicação nunca conseguiria alcançar de verdade.
    private Payment paymentInStatus(PaymentStatus status) {
        Payment payment = newPendingPayment();
        switch (status) {
            case PENDING -> { }
            case PROCESSING -> payment.startProcessing();
            case APPROVED -> {
                payment.startProcessing();
                payment.approve();
            }
            case DECLINED -> {
                payment.startProcessing();
                payment.decline();
            }
            case CANCELLED -> payment.cancel();
        }
        return payment;
    }
}
