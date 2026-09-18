package com.example.payment_api.security;

import com.example.payment_api.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

// Entra em ação quando uma requisição SEM autenticação válida (token
// ausente, expirado ou adulterado) tenta acessar um endpoint protegido.
//
// Sem este bean, quem responde é o comportamento PADRÃO do Spring Security
// — corpo genérico, sem o formato JSON que padronizamos no
// GlobalExceptionHandler ({timestamp, status, error, message, path}). O
// motivo é estrutural: essa falha acontece dentro da FILTER CHAIN do
// Spring Security, ANTES da requisição chegar no DispatcherServlet/
// controller. Como o @RestControllerAdvice só intercepta exceções
// lançadas de dentro de um controller, ele nunca vê esse caso — por isso
// precisamos de um AuthenticationEntryPoint próprio, escrevendo a resposta
// diretamente no HttpServletResponse.
//
// Escrevemos via OutputStream (não Writer) e definimos Content-Length
// explicitamente porque este ponto roda fora do ciclo normal do Spring
// MVC — não há HttpMessageConverter automático aqui, então a
// serialização e a escrita da resposta são responsabilidade nossa.
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    public JwtAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Authentication is required to access this resource",
                request.getRequestURI()
        );

        byte[] payload = jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        response.getOutputStream().flush();
    }
}
