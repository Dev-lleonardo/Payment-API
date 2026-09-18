package com.example.payment_api.controller;

import com.example.payment_api.dto.PaymentRequest;
import com.example.payment_api.dto.PaymentResponse;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@Validated
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request,
                                                    @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                                    Authentication authentication) {
        Payment payment = paymentService.createPayment(request, idempotencyKey, currentUserId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getById(@PathVariable Long id, Authentication authentication) {
        Payment payment = paymentService.getPayment(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    @GetMapping
    public ResponseEntity<Page<PaymentResponse>> list(Authentication authentication, Pageable pageable) {
        Page<PaymentResponse> page = paymentService
                .listPayments(currentUserId(authentication), isAdmin(authentication), pageable)
                .map(PaymentResponse::from);
        return ResponseEntity.ok(page);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentResponse> cancel(@PathVariable Long id, Authentication authentication) {
        Payment payment = paymentService.cancelPayment(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // O JwtAuthenticationFilter (FASE 6) guarda o id do usuário como
    // principal e a role como authority "ROLE_<role>" — esses dois helpers
    // só leem de volta o que o filtro já colocou no SecurityContext.
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
