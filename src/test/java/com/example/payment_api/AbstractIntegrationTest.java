package com.example.payment_api;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

// Classe base para os testes de INTEGRAÇÃO da FASE 10: sobe um Postgres
// real em Docker (via Testcontainers), só para a duração dos testes — sem
// depender do docker-compose do ambiente de desenvolvimento estar de pé,
// nem de nenhum Postgres instalado manualmente. O Flyway roda as migrations
// de verdade contra esse banco, igual rodaria em produção.
//
// Padrão "Singleton Container": o container é criado e iniciado UMA VEZ,
// num bloco estático — e nunca é parado explicitamente por nós. Ele fica
// vivo e é REAPROVEITADO por todas as classes de teste que estendem esta
// base (static é por classe, não por instância: todas as subclasses
// enxergam o MESMO container). Só morre quando a JVM do Maven termina —
// o próprio Testcontainers cuida da limpeza via um container auxiliar
// chamado Ryuk. Sem isso, cada classe de teste pagaria o custo de subir
// um Postgres do zero (alguns segundos) — com várias classes de
// integração, esse custo soma rápido.
//
// @ServiceConnection (Spring Boot 3.1+): dispensa configurar manualmente
// spring.datasource.url/username/password via @DynamicPropertySource — o
// Spring detecta esse container e conecta a aplicação nele automaticamente
// quando o contexto de teste sobe.
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
