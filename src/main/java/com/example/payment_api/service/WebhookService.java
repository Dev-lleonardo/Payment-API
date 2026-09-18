package com.example.payment_api.service;

import com.example.payment_api.dto.WebhookPaymentRequest;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.PaymentStatus;
import com.example.payment_api.entity.WebhookEvent;
import com.example.payment_api.exception.InvalidWebhookException;
import com.example.payment_api.exception.InvalidWebhookSignatureException;
import com.example.payment_api.exception.PaymentNotFoundException;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.WebhookEventRepository;
import com.example.payment_api.security.WebhookSignatureVerifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

// Simula o endpoint que um gateway de pagamento externo (Stripe, Mercado
// Pago, Pagar.me...) chamaria para nos avisar que o status de um pagamento
// mudou. É o inverso do fluxo normal: aqui é OUTRO SISTEMA que nos diz o
// que aconteceu, então não podemos confiar cegamente em nada que chegar.
// Duas garantias são obrigatórias:
//   1) autenticidade — provar que quem mandou é realmente o gateway
//      (assinatura HMAC, ver WebhookSignatureVerifier);
//   2) idempotência — processar o mesmo evento duas vezes (reentrega,
//      timeout + retry do gateway, etc.) não pode duplicar efeito nenhum.
@Service
public class WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final WebhookSignatureVerifier signatureVerifier;

    // Spring Boot 4 passou a usar Jackson 3 como padrão, e o bean que ele
    // auto-configura e disponibiliza pra injeção é JsonMapper
    // (tools.jackson.databind.json.JsonMapper), não mais o ObjectMapper
    // clássico do Jackson 2 (com.fasterxml.jackson.databind.ObjectMapper).
    // O Jackson 2 continua no classpath só em runtime, por compatibilidade
    // com libs de terceiros que ainda dependem dele (é o caso do jjwt-jackson,
    // que usamos pra JWT) — mas não é ele que o Spring expõe como bean pra
    // nosso código injetar. Usar o JsonMapper aqui garante que estamos
    // reaproveitando a MESMA instância configurada pelo Spring para todo o
    // resto da aplicação (mesmas configurações, mesmos módulos registrados),
    // em vez de criar um `new ObjectMapper()` avulso com config divergente.
    private final JsonMapper jsonMapper;

    // Mesmo padrão de auto-injeção com @Lazy usado em PaymentService (FASE 7):
    // processWebhook() chama self.applyEvent() para que o @Transactional de
    // applyEvent() passe pelo proxy do Spring AOP. Uma chamada "this.applyEvent(...)"
    // aqui dentro seria invisível para o proxy e a transação simplesmente não
    // aconteceria (self-invocation problem).
    private final WebhookService self;

    public WebhookService(WebhookEventRepository webhookEventRepository,
                           PaymentRepository paymentRepository,
                           WebhookSignatureVerifier signatureVerifier,
                           JsonMapper jsonMapper,
                           @Lazy WebhookService self) {
        this.webhookEventRepository = webhookEventRepository;
        this.paymentRepository = paymentRepository;
        this.signatureVerifier = signatureVerifier;
        this.jsonMapper = jsonMapper;
        this.self = self;
    }

    public void processWebhook(String rawBody, String signatureHeader) {
        if (!signatureVerifier.isValid(rawBody, signatureHeader)) {
            throw new InvalidWebhookSignatureException();
        }

        WebhookPaymentRequest event = parse(rawBody);
        validate(event);

        // Checagem otimista ANTES de mexer no pagamento: se este eventId já
        // foi processado, não podemos simplesmente tentar de novo — o
        // pagamento já não está mais em PROCESSING (já foi aprovado/recusado
        // da primeira vez), então approve()/decline() rejeitaria a transição
        // com InvalidPaymentStateException (409), mesmo sendo uma reentrega
        // legítima do MESMO evento. Isso é diferente do caso de criação de
        // pagamento (FASE 7): lá o insert em si É a operação idempotente; já
        // aqui a operação idempotente (gravar o WebhookEvent) só acontece
        // DEPOIS de uma mutação de estado que só é válida na primeira vez.
        // Por isso o webhook precisa da checagem explícita aqui, e não só do
        // catch de constraint violation.
        if (webhookEventRepository.findByEventId(event.eventId()).isPresent()) {
            return;
        }

        try {
            self.applyEvent(event, rawBody);
        } catch (DataIntegrityViolationException ex) {
            // Backstop para concorrência: se DUAS requisições com o mesmo
            // eventId passarem pela checagem acima quase ao mesmo tempo
            // (antes de qualquer uma commitar), a constraint UNIQUE no banco
            // garante que só uma delas grava o WebhookEvent — a outra cai
            // aqui. Não é erro: confirmamos que o evento existe e paramos.
            webhookEventRepository.findByEventId(event.eventId())
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional
    public void applyEvent(WebhookPaymentRequest event, String rawBody) {
        Payment payment = paymentRepository.findById(event.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(event.paymentId()));

        // A própria entidade decide se a transição é válida (mesma máquina
        // de estados da FASE 6/7). Se o pagamento já não estiver em
        // PROCESSING, approve()/decline() lança InvalidPaymentStateException
        // e a exceção se propaga — o evento não é gravado, então um retry
        // futuro com o MESMO eventId pode ser reprocessado do zero.
        if (event.status() == PaymentStatus.APPROVED) {
            payment.approve();
        } else {
            payment.decline();
        }

        WebhookEvent webhookEvent = new WebhookEvent(
                event.eventId(),
                payment,
                event.status(),
                rawBody,
                LocalDateTime.now()
        );

        // saveAndFlush (não save) pelo mesmo motivo da FASE 7: precisamos que
        // a violação da constraint UNIQUE(event_id) estoure AQUI, dentro do
        // try/catch de processWebhook(), e não silenciosamente mais tarde
        // quando o Spring der flush no fim da transação.
        webhookEventRepository.saveAndFlush(webhookEvent);
    }

    private WebhookPaymentRequest parse(String rawBody) {
        try {
            // No Jackson 3, readValue() já lança exceção unchecked
            // (JacksonException, que estende RuntimeException) em vez da
            // antiga JsonProcessingException (checked) do Jackson 2 — não
            // muda nossa lógica aqui, mas explica por que não há "throws"
            // declarado neste método mesmo lidando com parsing de JSON.
            return jsonMapper.readValue(rawBody, WebhookPaymentRequest.class);
        } catch (Exception ex) {
            throw new InvalidWebhookException("Malformed webhook payload");
        }
    }

    private void validate(WebhookPaymentRequest event) {
        if (event.paymentId() == null) {
            throw new InvalidWebhookException("paymentId is required");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new InvalidWebhookException("eventId is required");
        }
        // De propósito só aceitamos APPROVED/DECLINED aqui: são os únicos
        // status que fazem sentido um gateway "notificar depois" — PENDING e
        // PROCESSING são estados internos nossos, e CANCELLED só pode
        // acontecer por ação do próprio usuário (endpoint /cancel), nunca
        // por notificação externa.
        if (event.status() != PaymentStatus.APPROVED && event.status() != PaymentStatus.DECLINED) {
            throw new InvalidWebhookException("Unsupported webhook status: " + event.status());
        }
    }
}
