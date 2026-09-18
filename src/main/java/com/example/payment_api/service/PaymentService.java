package com.example.payment_api.service;

import com.example.payment_api.dto.PaymentRequest;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.entity.User;
import com.example.payment_api.exception.IdempotencyKeyConflictException;
import com.example.payment_api.exception.PaymentNotFoundException;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    // Referência ao PROXY do próprio bean (não "this"). Explicação abaixo,
    // em createPayment().
    private final PaymentService self;

    public PaymentService(PaymentRepository paymentRepository,
                           UserRepository userRepository,
                           @Lazy PaymentService self) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.self = self;
    }

    // ---------------------------------------------------------------
    // A minha primeira versão desta correção (a que você acabou de rodar)
    // tinha find() + saveAndFlush() + startProcessing() + o catch/retry TUDO
    // dentro de um único método @Transactional. Isso quebrou de um jeito
    // instrutivo: quando o saveAndFlush() falha por violação de constraint,
    // o Hibernate marca a SESSÃO INTEIRA como inutilizável — não é só a
    // exceção que "sobe", a sessão/persistence context fica corrompida.
    // Foi exatamente o que apareceu no seu log: "an assertion failure...
    // this can happen if the session is flushed after an exception occurs".
    // Ou seja: catch(DataIntegrityViolationException) dentro do MESMO método
    // @Transactional não adianta, porque qualquer coisa que eu fizesse
    // DEPOIS do catch (inclusive um simples find()) ainda estaria usando
    // a mesma sessão contaminada.
    //
    // A correção de verdade precisa que a tentativa de insert e a busca de
    // recuperação rodem em TRANSAÇÕES (sessões) DIFERENTES. Por isso separei
    // insertAndStartProcessing() como seu próprio método @Transactional, e
    // este método aqui (createPayment) NÃO é mais transacional — ele só
    // orquestra.
    //
    // Um detalhe importante de Spring AOP: @Transactional funciona porque o
    // Spring te dá um PROXY do bean, que intercepta a chamada antes de
    // entrar no método de verdade. Se eu chamasse "this.insertAndStartProcessing(...)"
    // aqui dentro, essa chamada NUNCA passaria pelo proxy — é uma chamada
    // Java comum, de dentro do mesmo objeto — e o @Transactional seria
    // silenciosamente ignorado. Isso é o clássico "problema de
    // self-invocation" do Spring, uma das perguntas mais comuns de
    // entrevista sobre @Transactional. Por isso injetei "self": um campo
    // que aponta pro PROXY do próprio PaymentService (com @Lazy pra evitar
    // dependência circular na inicialização). Chamar self.insertAndStartProcessing(...)
    // passa pelo proxy de verdade, abrindo uma transação nova e genuína.
    public Payment createPayment(PaymentRequest request, String idempotencyKey, Long currentUserId) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), request);
        }

        try {
            return self.insertAndStartProcessing(request, idempotencyKey, currentUserId);
        } catch (DataIntegrityViolationException ex) {
            // A transação de insertAndStartProcessing() já foi totalmente
            // encerrada (rollback) antes da exceção chegar até aqui — este
            // findByIdempotencyKey roda numa transação/sessão nova e limpa,
            // aberta pelo próprio Spring Data JPA por conta do método do
            // repositório ser chamado diretamente.
            Payment concurrentlyCreated = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> ex);
            return sameOrConflict(concurrentlyCreated, request);
        }
    }

    @Transactional
    public Payment insertAndStartProcessing(PaymentRequest request, String idempotencyKey, Long currentUserId) {
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + currentUserId));

        Payment payment = new Payment(request.orderId(), user, request.amount(), request.paymentMethod(), idempotencyKey);

        // saveAndFlush (não save) de propósito: força o INSERT a ir pro
        // banco AGORA, para que uma eventual violação de constraint estoure
        // aqui dentro — e não silenciosamente mais tarde, no commit.
        Payment saved = paymentRepository.saveAndFlush(payment);
        saved.startProcessing();
        return saved;
    }

    @Transactional(readOnly = true)
    public Payment getPayment(Long paymentId, Long currentUserId, boolean isAdmin) {
        Payment payment = findOrThrow(paymentId);
        requireOwnershipOrAdmin(payment, currentUserId, isAdmin);
        return payment;
    }

    @Transactional(readOnly = true)
    public Page<Payment> listPayments(Long currentUserId, boolean isAdmin, Pageable pageable) {
        if (isAdmin) {
            return paymentRepository.findAll(pageable);
        }
        return paymentRepository.findByUserId(currentUserId, pageable);
    }

    @Transactional
    public Payment cancelPayment(Long paymentId, Long currentUserId, boolean isAdmin) {
        Payment payment = findOrThrow(paymentId);
        requireOwnershipOrAdmin(payment, currentUserId, isAdmin);
        payment.cancel();
        return payment;
    }

    private Payment findOrThrow(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    // Se não é dono e não é ADMIN, respondemos como se o recurso não
    // existisse (404), não como "403 Forbidden" — evita confirmar pra um
    // usuário que aquele ID de pagamento existe e é de outra pessoa.
    private void requireOwnershipOrAdmin(Payment payment, Long currentUserId, boolean isAdmin) {
        if (!isAdmin && !payment.getUser().getId().equals(currentUserId)) {
            throw new PaymentNotFoundException(payment.getId());
        }
    }

    // Regra de negócio 9 do briefing: uma Idempotency-Key reaproveitada com
    // dados diferentes deve gerar erro, não silenciosamente devolver o
    // pagamento antigo como se fosse o resultado do novo pedido.
    private Payment sameOrConflict(Payment existing, PaymentRequest request) {
        boolean sameOrder = existing.getOrderId().equals(request.orderId());
        boolean sameAmount = existing.getAmount().compareTo(request.amount()) == 0;
        boolean sameMethod = existing.getPaymentMethod() == request.paymentMethod();

        if (!sameOrder || !sameAmount || !sameMethod) {
            throw new IdempotencyKeyConflictException(existing.getIdempotencyKey());
        }

        return existing;
    }
}
