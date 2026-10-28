package com.example.payment_api.repository;

import com.example.payment_api.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // A chave só identifica um pagamento junto com o dono: dois usuários
    // podem usar a mesma Idempotency-Key sem se enxergarem.
    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Page<Payment> findByUserId(Long userId, Pageable pageable);

    // Usado nos testes de idempotência para confirmar que, mesmo sob corrida,
    // um mesmo usuário nunca fica com mais de UM pagamento com a mesma chave.
    long countByIdempotencyKey(String idempotencyKey);
}
