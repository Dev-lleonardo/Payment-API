package com.example.payment_api.service;

import com.example.payment_api.AbstractIntegrationTest;
import com.example.payment_api.dto.PaymentRequest;
import com.example.payment_api.entity.PaymentMethod;
import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;
import com.example.payment_api.repository.PaymentRepository;
import com.example.payment_api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Teste de INTEGRAÇÃO, fora do padrão "unitário" que vamos usar no resto
// da FASE 10: aqui a intenção é justamente provocar concorrência real
// contra um banco real. Desde a FASE 10, isso roda contra o Postgres
// descartável do Testcontainers (herdado de AbstractIntegrationTest),
// não mais contra o docker-compose do ambiente de desenvolvimento — o
// teste passa a ser autossuficiente, sem precisar de nada de pé antes.
//
// Este teste já provou a condição de corrida ANTES da correção da FASE 7
// (falhava de propósito, evidenciando o bug de self-invocation do Spring
// AOP) e, depois do fix com self-injection (@Lazy), passou a validar que
// a correção se sustenta: 10 requisições concorrentes com a mesma
// idempotency_key, nenhuma falha, e só 1 pagamento é criado.
@SpringBootTest
class PaymentIdempotencyConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void concurrentRequestsWithSameIdempotencyKeyNeverFailAndCreateOnlyOnePayment()
            throws InterruptedException, ExecutionException, TimeoutException {
        User user = userRepository.save(new User(
                "Concurrency Test",
                "concurrency-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("senha12345"),
                Role.USER
        ));

        String idempotencyKey = "concurrent-" + UUID.randomUUID();
        PaymentRequest request = new PaymentRequest("order-concurrent", new BigDecimal("100.00"), PaymentMethod.PIX);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    // todas as threads esperam aqui até o startLatch.countDown()
                    // abaixo soltar todas de uma vez só, maximizando a chance
                    // de colisão real (em vez de uma "corrida" só na teoria).
                    startLatch.await();
                    paymentService.createPayment(request, idempotencyKey, user.getId());
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        // future.get() aqui só propagaria ExecutionException se o Runnable
        // submetido tivesse lançado — mas ele mesmo já captura tudo internamente
        // (no catch acima) e conta em errorCount. Ainda assim o compilador exige
        // tratar essas exceções checadas, então declaramos no "throws" do método.
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // A constraint UNIQUE do banco garante isso mesmo sem nenhum código
        // Java especial: nunca existe mais de uma linha com a mesma
        // idempotency_key.
        assertThat(paymentRepository.countByIdempotencyKey(idempotencyKey)).isEqualTo(1);

        // Esta é a parte que só passa GRAÇAS à correção da FASE 7 (self-
        // injection + transações separadas): das 10 requisições
        // concorrentes, nenhuma deveria falhar — todas devem receber de
        // volta o mesmo pagamento, com sucesso. Sem o fix, algumas threads
        // recebiam DataIntegrityViolationException e a sessão Hibernate
        // corrompida travava a tentativa de recuperação.
        assertThat(errorCount.get())
                .as("nenhuma requisição concorrente deveria falhar — idempotência de verdade significa retry seguro")
                .isZero();
    }
}
