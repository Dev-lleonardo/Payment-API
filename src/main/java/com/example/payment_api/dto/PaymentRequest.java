package com.example.payment_api.dto;

import com.example.payment_api.entity.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentRequest(

        @NotBlank(message = "orderId is required")
        String orderId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount,

        @NotNull(message = "paymentMethod is required")
        PaymentMethod paymentMethod
) {
}
