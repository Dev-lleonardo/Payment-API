package com.example.payment_api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt: hash com salt embutido e custo configurável (fator de
        // trabalho), feito para ser lento de propósito — dificulta ataques
        // de força bruta/rainbow table mesmo se o banco vazar. Nunca é
        // "desfeito"; login compara hash com matches(), não decripta nada.
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     JwtAuthenticationFilter jwtAuthenticationFilter,
                                                     JwtAuthenticationEntryPoint authenticationEntryPoint,
                                                     JwtAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API stateless sem cookies de sessão: CSRF não se aplica aqui
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/webhooks/payment").permitAll()
                        .anyRequest().authenticated()
                )
                // Roda antes do filtro padrão de usuário/senha do Spring Security,
                // já que nosso login não usa esse mecanismo — é só JWT.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // FASE 9: sem isso, uma falha de autenticação/autorização que
                // acontece AQUI na filter chain (antes de chegar em qualquer
                // controller) é respondida pelo comportamento padrão do Spring
                // Security — não pelo formato JSON do nosso GlobalExceptionHandler,
                // porque @RestControllerAdvice só enxerga exceções lançadas de
                // dentro de um controller. authenticationEntryPoint cobre "sem
                // autenticação válida" (401); accessDeniedHandler cobre
                // "autenticado, mas sem permissão" (403) — hoje não temos nenhum
                // endpoint restrito por role (a distinção USER/ADMIN é de escopo
                // de dados, tratada no PaymentService), mas registramos os dois
                // por completude e consistência de formato de erro.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }
}
