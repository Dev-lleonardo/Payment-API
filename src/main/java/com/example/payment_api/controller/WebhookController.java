package com.example.payment_api.controller;

import com.example.payment_api.service.WebhookService;
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
    @PostMapping("/payment")
    public ResponseEntity<Void> receivePaymentEvent(@RequestBody String rawBody,
                                                       @RequestHeader("X-Webhook-Signature") String signature) {
        webhookService.processWebhook(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
