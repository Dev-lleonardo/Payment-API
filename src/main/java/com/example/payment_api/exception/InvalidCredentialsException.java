package com.example.payment_api.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        // Mensagem genérica de propósito: não revela se o e-mail existe ou
        // se foi a senha que errou, para não facilitar enumeração de contas.
        super("Invalid email or password");
    }
}
