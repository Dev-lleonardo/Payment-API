package com.example.payment_api.repository;

import com.example.payment_api.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Page<Payment> findByUserId(Long userId, Pageable pageable);

    // Usado no teste de concorrência da FASE 7 para confirmar que, mesmo sob
    // corrida, nunca existe mais de UM pagamento com a mesma chave.
    long countByIdempotencyKey(String idempotencyKey);
}
