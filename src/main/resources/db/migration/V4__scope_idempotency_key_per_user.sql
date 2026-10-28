-- A Idempotency-Key passa a ser única POR USUÁRIO, não no sistema todo.
-- Com a UNIQUE global da V2, um usuário que reusasse a chave de outro
-- (com o mesmo corpo) recebia o pagamento desse outro usuário.
--
-- Nova migration em vez de editar a V2: o Flyway guarda o checksum de cada
-- migration já aplicada, e alterar uma delas quebra a validação em todo
-- banco que já rodou a versão antiga.
ALTER TABLE payments DROP CONSTRAINT payments_idempotency_key_key;

ALTER TABLE payments
    ADD CONSTRAINT uq_payments_user_idempotency_key UNIQUE (user_id, idempotency_key);
