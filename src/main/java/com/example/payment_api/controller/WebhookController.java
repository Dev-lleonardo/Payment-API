package com.example.payment_api.controller;

import com.example.payment_api.dto.WebhookPaymentRequest;
import com.example.payment_api.exception.ErrorResponse;
import com.example.payment_api.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint liberado no SecurityConfig (não exige JWT) porque quem chama não
// é um usuário logado — é o gateway de pagamento externo. "Público" aqui não
// quer dizer "aberto para qualquer um": a autenticidade de quem chama é
// garantida pela assinatura HMAC (WebhookSignatureVerifier), não por um
// token de usuário.
@RestController
@RequestMapping("/webhooks")
@Tag(name = "Webhooks", description = "Eventos enviados pelo gateway de pagamento externo. Autenticados por assinatura HMAC, não por JWT.")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    // O corpo é recebido como String CRUA, não como WebhookPaymentRequest
    // direto: precisamos dos bytes exatos que chegaram para recalcular o
    // HMAC. Se o Spring desserializasse direto para um DTO, já teríamos
    // perdido a formatação original do JSON (espaços, ordem de campos etc.)
    // e a assinatura recalculada nunca bateria com a recebida.
    //
    // FASE 12: por ser String, o springdoc documentaria o corpo como "string".
    // A anotação do Swagger abaixo (nome completo pra não colidir com o
    // @RequestBody do Spring) só muda a DOCUMENTAÇÃO para mostrar o formato
    // real do JSON — o método continua recebendo a String crua.
    @PostMapping("/payment")
    @Operation(summary = "Recebe um evento de mudança de status de pagamento",
            description = "Só leva pagamentos de PROCESSING para APPROVED ou DECLINED. "
                    + "Reentregas do mesmo eventId são ignoradas (idempotência).")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WebhookPaymentRequest.class)))
    @ApiResponse(responseCode = "200", description = "Evento processado (ou já processado antes)")
    @ApiResponse(responseCode = "400", description = "Payload mal formado, campo obrigatório ausente ou status não suportado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Assinatura HMAC ausente ou inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "paymentId não existe",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Pagamento não está em PROCESSING",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> receivePaymentEvent(@RequestBody String rawBody,
                                                       @Parameter(description = "HMAC-SHA256 do corpo bruto, em hexadecimal, calculado com o segredo compartilhado com o gateway")
                                                       @RequestHeader("X-Webhook-Signature") String signature) {
        webhookService.processWebhook(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
