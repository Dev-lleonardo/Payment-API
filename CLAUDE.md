# Payment API — contexto para o Claude Code

Este arquivo é lido automaticamente pelo Claude Code sempre que uma sessão é aberta nesta pasta.
Ele existe pra preservar o contrato de trabalho e o histórico técnico construídos com o Leonardo
numa sessão anterior (Claude/Cowork), pra continuar exatamente do mesmo jeito, sem perder contexto.

## Quem é o Leonardo e qual é o objetivo real deste projeto

Leonardo é estudante de ADS, cursando pra virar backend Java júnior (Spring Boot), criador de
conteúdo parceiro da Rocketseat. Este projeto — uma Payment API que simula processamento de
pagamentos — é o projeto principal do portfólio dele para entrevistas de vaga júnior CLT.

**O objetivo NÃO é só ter o projeto funcionando.** É entender profundamente cada decisão técnica
o suficiente pra defendê-la numa entrevista técnica. Código gerado sem explicação, ou fases
inteiras resolvidas de uma vez sem passar pelo Leonardo, vão contra o propósito do projeto —
mesmo que o resultado final funcione.

## Contrato de trabalho (não pular isso)

- **Desenvolvimento fase a fase.** Nunca resolver várias fases de uma vez, nem pular etapas do
  roadmap sem o Leonardo pedir explicitamente.
- Em cada passo: explicar o objetivo, os conceitos envolvidos, mostrar a estrutura de arquivos,
  escrever o código, **explicar o código** (o quê, por quê, qual problema resolve, que
  alternativas existem, como seria em produção), mostrar os comandos pra rodar, explicar como
  testar, e **esperar confirmação/resultado do Leonardo antes de avançar pro próximo passo.**
- Preferir sempre a solução mais simples que resolve o problema, e explicar quando uma solução
  mais complexa faria sentido num sistema real — sem implementar a complexa à toa.
- Sem Lombok. Records pra DTOs. Enums pra estados. Constructor injection. Nomes em inglês no
  código, comentários podem ser em português (é o padrão já usado no projeto).
- Não adicionar bibliotecas/tecnologias além da stack combinada sem explicar antes o motivo.

## Stack obrigatória

Java 21, Spring Boot 4.1.1, Spring Web, Spring Data JPA, Spring Security + JWT, PostgreSQL,
Flyway, Bean Validation, Maven, Docker/Docker Compose, JUnit 5, Mockito, Testcontainers,
Swagger/OpenAPI.

## Onde as coisas estão

- Pacote raiz: `com.example.payment_api`
- Camadas: `controller/ service/ repository/ entity/ dto/ exception/ security/ config/`
- Repositório no GitHub (já publicado): https://github.com/Dev-lleonardo/Payment-API
- App roda em `http://localhost:8080`, Postgres em `localhost:5432` via `docker-compose.yml`
  (só necessário pra rodar a aplicação de verdade — os testes de integração usam Testcontainers
  e não dependem disso).

## Status atual do projeto (ver histórico de commits pra detalhes)

FASE 10 (Testes) em andamento. Concluído e validado até aqui:

1. **10.1** — Infraestrutura Testcontainers (`AbstractIntegrationTest`, Singleton Container).
2. **10.2** — Testes unitários de regras de negócio (`PaymentTest`, `PaymentRequestValidationTest`) — 27 testes.
3. **10.3** — Testes unitários do `PaymentService` com Mockito — 14 testes.
4. **10.4** — Testes unitários do `WebhookService` com Mockito — 13 testes.
5. **10.5a** — `AuthControllerIT` (MockMvc + Testcontainers, `/auth/register` e `/auth/login`) — 9 testes.

**Total: 64 testes passando.**

**Próximo passo: 10.5b — `PaymentControllerIT`.** Ler `controller/PaymentController.java` antes de
escrever. Cobertura planejada: criar pagamento com `Idempotency-Key` (incluindo reenvio da mesma
chave), consultar/listar respeitando escopo USER (só os próprios) vs ADMIN (todos), cancelar
(incluindo tentativas de transição de estado inválida).

Depois: **10.5c** — `WebhookControllerIT` (assinatura HMAC via `WebhookSignatureVerifier.sign(...)`,
processamento de evento, idempotência). Depois: **10.6** — revisão da suíte completa
(`./mvnw clean test`). Depois: **FASE 11** (Docker), **FASE 12** (Swagger/OpenAPI),
**FASE 13** (README completo + diagrama de arquitetura), **FASE 14** (revisão final estilo
entrevista técnica + perguntas baseadas no código real).

## Modelo de dados

- **users**: id, name, email (UNIQUE), password_hash, role (USER/ADMIN), created_at, updated_at
- **payments**: id, order_id, user_id (FK), amount (>0), payment_method (PIX/CREDIT_CARD/DEBIT_CARD),
  status (PENDING/PROCESSING/APPROVED/DECLINED/CANCELLED), idempotency_key (UNIQUE), created_at, updated_at
- **webhook_events**: id, event_id (UNIQUE), payment_id (FK), status_received, payload (JSONB),
  processed_at, created_at

## Máquina de estados do pagamento

```
PENDING → PROCESSING → APPROVED (terminal)
                     → DECLINED (terminal)
PENDING/PROCESSING → CANCELLED (terminal)
CANCELLED → (nenhuma transição de saída)
```
Webhook só pode levar PROCESSING → APPROVED/DECLINED.

## Lições técnicas já aprendidas (não repetir os mesmos bugs)

- **Spring Boot 4 usa Jackson 3 por padrão.** O bean que o Spring disponibiliza para injeção é
  `tools.jackson.databind.json.JsonMapper` (Jackson 3), NÃO `com.fasterxml.jackson.databind.ObjectMapper`
  (Jackson 2).
- **`spring-boot-starter-parent` NÃO gerencia versão de `org.testcontainers:*`.** O BOM do Spring
  Boot gerencia `org.springframework.boot:spring-boot-testcontainers`, mas não os módulos
  `org.testcontainers:*` — precisam de `<version>` explícita (property `testcontainers.version`,
  hoje `1.21.4`).
- **Spring Boot 4 modularizou os pacotes das anotações de teste**, não só as dependências. Antes
  existia um jar central `spring-boot-test-autoconfigure` com tudo em
  `org.springframework.boot.test.autoconfigure.*`. Agora cada starter de teste
  (`spring-boot-webmvc-test`, `-data-jpa-test`, etc.) carrega suas próprias classes de
  autoconfiguração, com pacote renomeado pra bater com o artefato. Ex.: `@AutoConfigureMockMvc`
  agora é `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- **Self-invocation do Spring AOP**: chamadas internas (`this.metodo()`) não passam pelo proxy do
  Spring, então `@Transactional` não é respeitado. Solução usada no projeto: self-injection com
  `@Lazy` (campo `self` injetado na própria classe), usado em `PaymentService` e `WebhookService`
  pra separar transações (ex.: insert numa transação, recuperação de conflito de idempotência
  noutra).
- **Idempotência de webhook**: nunca mutar o recurso antes de garantir que o evento não foi
  processado — checar `findByEventId(...)` ANTES de aplicar o efeito, não depois.
- **Nunca desenvolver dentro de pasta sincronizada por OneDrive/Dropbox/Drive.** Já causou bytecode
  compilado desatualizado mesmo com `.java` correto no disco. O projeto já foi migrado pra
  `C:\dev\payment-api` — mantenha fora de qualquer pasta com sincronização em nuvem.
- **PowerShell**: `-Dtest=Classe1,Classe2` sem aspas quebra por causa da vírgula — sempre envolver
  em aspas. Comandos git colados em bloco rodam na ordem em que foram digitados, não na ordem
  "lógica" (ex.: `git branch -M main` precisa rodar ANTES de `git push -u origin main`).
- **Mockito `UnnecessaryStubbingException`**: usar `lenient().when(...)` em helpers de teste
  compartilhados quando nem todo teste que usa o helper realmente invoca o método stubado.
- **Mock de método `void`**: `when(x.metodoVoid()).thenThrow(...)` não compila. Sintaxe certa:
  `doThrow(...).when(x).metodoVoid(...)`.

## Padrão recorrente de bugs até agora

A maioria dos erros na FASE 10 não foi de lógica de negócio — essa já estava validada manualmente
nas fases anteriores — e sim de mecânica de ferramenta: stub desnecessário do Mockito, sintaxe de
mock pra método void, import de pacote que mudou de lugar entre versões do Spring Boot. Vale
mencionar isso numa entrevista: escrever teste bem também exige entender a ferramenta e acompanhar
mudanças de versão, não só a regra de negócio.
