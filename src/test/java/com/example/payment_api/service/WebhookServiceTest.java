package com.example.payment_api.service;

import com.example.payment_api.dto.WebhookPaymentRequest;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.PaymentMethod;
import com.example.payment_api.entity.PaymentStatus;
import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;
import com.example.payment_api.entity.WebhookEvent;
import com.example.payment_api.exception.InvalidPaymentStateException;
import com.example.payment_api.exception.InvalidWebhookException;
import com.example.payment_api.exception.InvalidWebhookSignatureException;
import com.example.payment_api.exception.PaymentNotFoundException;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.WebhookEventRepository;
import com.example.payment_api.security.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Teste UNITÁRIO do WebhookService: todas as dependências (repositórios,
// verificador de assinatura, JsonMapper e o "self" da FASE 8) são dublês
// do Mockito. Dividido em duas partes: processWebhook() (a orquestração —
// assinatura, parsing, validação, checagem de idempotência) e
// applyEvent() (a mutação de verdade — aprovar/recusar o pagamento e
// gravar o evento), testado diretamente aqui porque sua lógica (qual
// transição chamar, o que vai no WebhookEvent) não depende de proxy
// transacional nenhum — só a GARANTIA transacional em si (rollback
// atômico) é que exige um teste de integração de verdade.
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final String RAW_BODY = "{\"paymentId\":1,\"status\":\"APPROVED\",\"eventId\":\"evt-1\"}";
    private static final String SIGNATURE = "valid-signature";

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private WebhookSignatureVerifier signatureVerifier;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private WebhookService self;

    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(webhookEventRepository, paymentRepository, signatureVerifier, jsonMapper, self);
    }

    // ---- processWebhook: assinatura -------------------------------------

    @Test
    void processWebhook_invalidSignature_throwsAndTouchesNothingElse() {
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(false);

        assertThatThrownBy(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE))
                .isInstanceOf(InvalidWebhookSignatureException.class);

        // A assinatura é a PRIMEIRA barreira: se ela falha, nada mais deve
        // ser tocado — nem parsing, nem checagem de idempotência.
        verifyNoInteractions(jsonMapper, webhookEventRepository, self);
    }

    // ---- processWebhook: parsing e validação -----------------------------

    @Test
    void processWebhook_malformedPayload_throwsInvalidWebhookException() {
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class))
                .thenThrow(new RuntimeException("unexpected token"));

        assertThatThrownBy(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessage("Malformed webhook payload");
    }

    @Test
    void processWebhook_missingPaymentId_throwsInvalidWebhookException() {
        WebhookPaymentRequest event = new WebhookPaymentRequest(null, PaymentStatus.APPROVED, "evt-1");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);

        assertThatThrownBy(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessage("paymentId is required");
    }

    @Test
    void processWebhook_blankEventId_throwsInvalidWebhookException() {
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "   ");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);

        assertThatThrownBy(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessage("eventId is required");
    }

    @Test
    void processWebhook_unsupportedStatus_throwsInvalidWebhookException() {
        // PENDING é um status interno nosso — nenhum gateway deveria estar
        // notificando isso de fora; só APPROVED/DECLINED são aceitos.
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.PENDING, "evt-1");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);

        assertThatThrownBy(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE))
                .isInstanceOf(InvalidWebhookException.class)
                .hasMessage("Unsupported webhook status: PENDING");
    }

    // ---- processWebhook: idempotência e concorrência ----------------------

    @Test
    void processWebhook_eventAlreadyProcessed_returnsWithoutCallingApplyEvent() {
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "evt-1");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);
        when(webhookEventRepository.findByEventId("evt-1"))
                .thenReturn(Optional.of(new WebhookEvent("evt-1", null, PaymentStatus.APPROVED, RAW_BODY, null)));

        assertThatCode(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE)).doesNotThrowAnyException();

        verify(self, never()).applyEvent(any(), any());
    }

    @Test
    void processWebhook_newEvent_delegatesToSelfApplyEvent() {
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "evt-1");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);
        when(webhookEventRepository.findByEventId("evt-1")).thenReturn(Optional.empty());

        webhookService.processWebhook(RAW_BODY, SIGNATURE);

        verify(self).applyEvent(event, RAW_BODY);
    }

    @Test
    void processWebhook_concurrentDuplicateEvent_recoversGracefully() {
        // Mesma corrida do bug real da FASE 8: duas entregas do MESMO evento
        // quase ao mesmo tempo. A checagem otimista não pegou (ainda não
        // tinha sido gravado), mas a constraint UNIQUE do banco pegou.
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "evt-1");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);
        when(webhookEventRepository.findByEventId("evt-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new WebhookEvent("evt-1", null, PaymentStatus.APPROVED, RAW_BODY, null)));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(self).applyEvent(event, RAW_BODY);

        assertThatCode(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE)).doesNotThrowAnyException();
    }

    @Test
    void processWebhook_concurrentRaceButRecoveryFindsNothing_rethrowsOriginalException() {
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "evt-1");
        DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");
        when(signatureVerifier.isValid(RAW_BODY, SIGNATURE)).thenReturn(true);
        when(jsonMapper.readValue(RAW_BODY, WebhookPaymentRequest.class)).thenReturn(event);
        when(webhookEventRepository.findByEventId("evt-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        doThrow(original).when(self).applyEvent(event, RAW_BODY);

        assertThatThrownBy(() -> webhookService.processWebhook(RAW_BODY, SIGNATURE))
                .isSameAs(original);
    }

    // ---- applyEvent: a mutação de verdade ---------------------------------

    @Test
    void applyEvent_approvedStatus_approvesPaymentAndSavesEvent() {
        Payment payment = newProcessingPayment();
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "evt-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        webhookService.applyEvent(event, RAW_BODY);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).saveAndFlush(captor.capture());
        WebhookEvent saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo("evt-1");
        assertThat(saved.getStatusReceived()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(saved.getPayload()).isEqualTo(RAW_BODY);
        assertThat(saved.getPayment()).isSameAs(payment);
    }

    @Test
    void applyEvent_declinedStatus_declinesPaymentAndSavesEvent() {
        Payment payment = newProcessingPayment();
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.DECLINED, "evt-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        webhookService.applyEvent(event, RAW_BODY);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        verify(webhookEventRepository).saveAndFlush(any(WebhookEvent.class));
    }

    @Test
    void applyEvent_paymentNotFound_throwsPaymentNotFoundException() {
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.APPROVED, "evt-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.applyEvent(event, RAW_BODY))
                .isInstanceOf(PaymentNotFoundException.class);
        verify(webhookEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void applyEvent_paymentAlreadyApproved_throwsAndNeverSavesEvent() {
        // Reentrega tardia de um evento pra um pagamento que já saiu de
        // PROCESSING: a própria entidade barra a transição (máquina de
        // estados da FASE 6), e o evento não é gravado — importante para
        // que um eventId novo, sobre esse mesmo pagamento, não seja
        // silenciosamente perdido.
        Payment payment = newProcessingPayment();
        payment.approve();
        WebhookPaymentRequest event = new WebhookPaymentRequest(1L, PaymentStatus.DECLINED, "evt-2");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> webhookService.applyEvent(event, RAW_BODY))
                .isInstanceOf(InvalidPaymentStateException.class);
        verify(webhookEventRepository, never()).saveAndFlush(any());
    }

    // ---- helpers ---------------------------------------------------------

    private Payment newProcessingPayment() {
        User user = new User("Test User", "test-" + UUID.randomUUID() + "@example.com", "hashed-password", Role.USER);
        Payment payment = new Payment("order-1", user, new BigDecimal("100.00"), PaymentMethod.PIX, "idem-" + UUID.randomUUID());
        payment.startProcessing();
        return payment;
    }
}
