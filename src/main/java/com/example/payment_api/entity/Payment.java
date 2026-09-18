package com.example.payment_api.entity;

import com.example.payment_api.exception.InvalidPaymentStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    // LAZY é intencional: o JPA por padrão carrega @ManyToOne de forma EAGER,
    // o que faria toda consulta de Payment trazer o User junto sem necessidade.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Payment() {
        // construtor exigido pelo JPA
    }

    public Payment(String orderId, User user, BigDecimal amount, PaymentMethod paymentMethod, String idempotencyKey) {
        this.orderId = orderId;
        this.user = user;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public User getUser() {
        return user;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // ---- Máquina de estados -------------------------------------------
    // As transições vivem aqui, na entidade, em vez de um setStatus() cru
    // no service. Assim é impossível colocar um Payment num status inválido
    // sem passar por uma dessas regras, não importa de onde o código chame.

    /**
     * PENDING -> PROCESSING. Simula o envio do pagamento para o gateway
     * externo; chamado logo após a criação, dentro da mesma transação.
     */
    public void startProcessing() {
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateException(
                    "Cannot start processing a payment with status " + status + "; only PENDING payments can start processing");
        }
        this.status = PaymentStatus.PROCESSING;
    }

    /**
     * PROCESSING -> APPROVED. Chamado pelo processamento do webhook (FASE 8).
     */
    public void approve() {
        if (status != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Cannot approve a payment with status " + status + "; only PROCESSING payments can be approved");
        }
        this.status = PaymentStatus.APPROVED;
    }

    /**
     * PROCESSING -> DECLINED. Chamado pelo processamento do webhook (FASE 8).
     */
    public void decline() {
        if (status != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Cannot decline a payment with status " + status + "; only PROCESSING payments can be declined");
        }
        this.status = PaymentStatus.DECLINED;
    }

    /**
     * PENDING/PROCESSING -> CANCELLED. Um pagamento já APPROVED, DECLINED ou
     * CANCELLED não pode ser cancelado por aqui (regra de negócio 2 do
     * projeto: aprovado exigiria um fluxo de estorno separado, fora de escopo).
     */
    public void cancel() {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.PROCESSING) {
            throw new InvalidPaymentStateException(
                    "Cannot cancel a payment with status " + status + "; only PENDING or PROCESSING payments can be cancelled");
        }
        this.status = PaymentStatus.CANCELLED;
    }
}
