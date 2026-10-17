package com.example.payment_api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

// FASE 12: metadados da documentação OpenAPI.
//
// @SecurityScheme só DECLARA que existe um esquema chamado "bearerAuth"
// (header Authorization: Bearer <jwt>) — é isso que faz o botão "Authorize"
// aparecer no Swagger UI. Quais endpoints exigem esse esquema é dito em cada
// controller com @SecurityRequirement(name = "bearerAuth"), não aqui: assim
// /auth/** e /webhooks/payment não aparecem com cadeado na documentação,
// batendo com o que o SecurityConfig realmente libera.
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Payment API",
        version = "v1",
        description = "API REST que simula processamento de pagamentos: idempotência, "
                + "máquina de estados, webhooks com assinatura HMAC e autenticação JWT."
))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
}
