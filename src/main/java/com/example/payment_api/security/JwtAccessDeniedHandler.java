package com.example.payment_api.security;

import com.example.payment_api.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

// Entra em ação quando a requisição ESTÁ autenticada (JWT válido), mas o
// usuário não tem permissão para aquele recurso especificamente.
//
// Vale registrar uma decisão consciente aqui: hoje, nenhum endpoint desta
// API é bloqueado inteiro por role (não existe algo como "só ADMIN pode
// bater nesse endpoint"). A distinção USER/ADMIN é sempre de ESCOPO DE
// DADOS — "veja só os seus pagamentos" vs. "veja todos" — e essa regra
// mora no PaymentService (requireOwnershipOrAdmin), não em
// @PreAuthorize/hasRole no controller. Por isso, na prática, este handler
// não é acionado pelo fluxo atual da aplicação. Mantemos ele mesmo assim
// por completude e consistência: se um endpoint genuinamente restrito a
// ADMIN for adicionado no futuro, o formato de erro já está pronto.
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JwtAccessDeniedHandler.class);

    private final JsonMapper jsonMapper;

    public JwtAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        try {
            ErrorResponse body = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.FORBIDDEN.value(),
                    HttpStatus.FORBIDDEN.getReasonPhrase(),
                    "You do not have permission to access this resource",
                    request.getRequestURI()
            );

            byte[] payload = jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);

            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setContentLength(payload.length);
            response.getOutputStream().write(payload);
            response.getOutputStream().flush();
        } catch (Exception ex) {
            log.error("Failed to write access-denied error response for {}", request.getRequestURI(), ex);
            throw ex;
        }
    }
}
