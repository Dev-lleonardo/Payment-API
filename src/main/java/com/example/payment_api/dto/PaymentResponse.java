package com.example.payment_api.dto;

import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.PaymentMethod;
import com.example.payment_api.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        String orderId,
        Long userId,
        BigDecimal amount,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        String idempotencyKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getUser().getId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getIdempotencyKey(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
