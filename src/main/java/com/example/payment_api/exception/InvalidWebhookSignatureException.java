package com.example.payment_api.exception;

public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Invalid or missing webhook signature");
    }
}
