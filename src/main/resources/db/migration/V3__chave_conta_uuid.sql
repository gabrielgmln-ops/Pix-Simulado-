-- ============================================================================
-- V3 - conta_id das chaves passa a ser UUID (Etapa 3)
--
-- Na Ledger API toda conta é identificada por UUID; a chave Pix aponta para a
-- conta que recebe o crédito, então o tipo precisa ser o mesmo.
--
-- O cast "USING conta_id::uuid" é proposital: se existir algum dado antigo fora
-- do formato, a migração FALHA e nada é alterado (a V3 roda em transação).
-- Preferimos parar e corrigir à mão a converter ou apagar dado silenciosamente.
-- Os índices sobre conta_id são reconstruídos automaticamente pelo PostgreSQL.
-- ============================================================================
ALTER TABLE chave_pix
    ALTER COLUMN conta_id TYPE UUID USING conta_id::uuid;
