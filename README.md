# Payment API

API REST em Java/Spring Boot que simula o processamento de pagamentos de pedidos, com foco em conceitos essenciais de backend para sistemas de pagamento: idempotência, máquina de estados, webhooks e transações.

> 🚧 **Projeto em desenvolvimento ativo.** Este é um README provisório. A documentação completa — arquitetura, modelo de dados, endpoints, como rodar, decisões técnicas e desafios — será publicada ao final do desenvolvimento.

## Stack

Java 21 · Spring Boot · Spring Security + JWT · PostgreSQL · Flyway · Docker · JUnit 5 + Mockito + Testcontainers · Swagger/OpenAPI (em breve)

## Status atual

- [x] Autenticação (JWT) — registro e login
- [x] CRUD de pagamentos (criar, consultar, listar, cancelar)
- [x] Idempotência via `Idempotency-Key`, com tratamento de concorrência real (testado com requisições simultâneas)
- [x] Webhook de gateway externo, com assinatura HMAC e processamento idempotente
- [x] Autorização por escopo — USER só vê os próprios pagamentos, ADMIN vê todos
- [x] Testes unitários de regras de negócio e services (Mockito) — 64 testes passando
- [ ] Testes de integração dos controllers (MockMvc + Testcontainers) — em andamento
- [ ] Docker Compose completo (aplicação + banco)
- [ ] Documentação Swagger/OpenAPI
- [ ] README completo, com diagrama de arquitetura

---

Desenvolvido como projeto de portfólio, fase a fase, com foco em entender profundamente cada decisão técnica — não só em ter o código funcionando.
