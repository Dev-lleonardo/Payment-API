package com.example.payment_api.dto;

import com.example.payment_api.entity.PaymentMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Teste UNITÁRIO das anotações de Bean Validation do PaymentRequest — não
// passa por Spring nem por controller nenhum: usamos o Validator do
// próprio Jakarta Bean Validation diretamente, o MESMO mecanismo que o
// Spring aciona por baixo dos panos quando valida um @RequestBody antes
// de chamar o controller. Isso prova que as mensagens de erro que a API
// devolve em produção são exatamente estas.
//
// Complementa o PaymentTest: aquele testa a máquina de estados da
// ENTIDADE; este testa a validação de ENTRADA do DTO. São regras
// diferentes, verificadas em pontos diferentes do fluxo — por isso dois
// arquivos, não um só.
class PaymentRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void validRequest_hasNoViolations() {
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), PaymentMethod.PIX);

        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void amountZero_violatesPositive() {
        PaymentRequest request = new PaymentRequest("order-1", BigDecimal.ZERO, PaymentMethod.PIX);

        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("amount must be greater than zero");
    }

    @Test
    void amountNegative_violatesPositive() {
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("-10.00"), PaymentMethod.PIX);

        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("amount must be greater than zero");
    }

    @Test
    void amountNull_violatesNotNull() {
        PaymentRequest request = new PaymentRequest("order-1", null, PaymentMethod.PIX);

        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("amount is required");
    }

    @Test
    void blankOrderId_violatesNotBlank() {
        PaymentRequest request = new PaymentRequest("   ", new BigDecimal("100.00"), PaymentMethod.PIX);

        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("orderId is required");
    }

    @Test
    void nullPaymentMethod_violatesNotNull() {
        PaymentRequest request = new PaymentRequest("order-1", new BigDecimal("100.00"), null);

        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("paymentMethod is required");
    }
}
