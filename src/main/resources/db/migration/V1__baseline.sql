-- ============================================================================
-- V1 - Baseline do schema do Pix Simulado.
--
-- Convenções adotadas em todas as migrações:
--   * Valores monetários: NUMERIC(19,2)  -> mapeado para BigDecimal, nunca float.
--   * Datas/horas:        TIMESTAMPTZ    -> sempre gravadas em UTC.
--   * Identificadores:    UUID gerado pela aplicação.
--   * Registros financeiros nunca são apagados nem alterados retroativamente;
--     correções ocorrem por lançamentos inversos (devolução).
--   * Migrações já aplicadas são imutáveis: toda mudança é um novo arquivo V<n>.
--
-- As tabelas de domínio chegam a partir da V2 (Etapa 1 - chaves Pix).
-- ============================================================================

COMMENT ON SCHEMA public IS 'Pix Simulado - ambiente de estudo com dados sintéticos';
