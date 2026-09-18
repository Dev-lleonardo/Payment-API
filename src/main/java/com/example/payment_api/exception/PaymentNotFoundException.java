package com.example.payment_api.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long paymentId) {
        super("Payment not found: " + paymentId);
    }
}
