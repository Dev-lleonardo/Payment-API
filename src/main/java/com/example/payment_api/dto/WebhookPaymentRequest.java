package com.example.payment_api.dto;

import com.example.payment_api.entity.PaymentStatus;

// Sem @NotNull/@NotBlank aqui de propósito: este DTO não passa pelo @Valid
// do Spring, porque precisamos ler o corpo da requisição como String CRUA
// primeiro (pra calcular o HMAC sobre os bytes exatos que chegaram) e só
// depois desserializar. A validação dos campos é feita manualmente em
// WebhookService, depois da assinatura já ter sido conferida.
public record WebhookPaymentRequest(
        Long paymentId,
        PaymentStatus status,
        String eventId
) {
}
