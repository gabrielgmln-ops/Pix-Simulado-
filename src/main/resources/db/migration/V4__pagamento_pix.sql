-- ============================================================================
-- V4 - Pagamentos Pix (Etapa 3)
--
-- Um registro por EndToEndId. A chave primária é a garantia final de
-- idempotência: duas requisições com o mesmo E2E nunca geram dois pagamentos,
-- mesmo que cheguem no mesmo milissegundo (INSERT ... ON CONFLICT DO NOTHING).
--
-- Ciclo de vida (coluna estado):
--   PENDENTE  -> reservado, chamada à Ledger em andamento
--   LIQUIDADO -> Ledger confirmou a transferência (final)
--   REJEITADO -> Ledger recusou com certeza, nada foi debitado (final)
--   INCERTO   -> timeout/erro de rede/5xx: não sabemos se debitou. A
--                reconciliação reenvia com a MESMA Idempotency-Key até ter
--                resposta definitiva; a Ledger nunca executa duas vezes.
-- ============================================================================
CREATE TABLE pagamento_pix (
    end_to_end_id        VARCHAR(32)   PRIMARY KEY,
    hash_pedido          VARCHAR(64)   NOT NULL,      -- SHA-256 (hex) do pedido normalizado
    conta_origem_id      UUID          NOT NULL,
    conta_destino_id     UUID          NOT NULL,
    chave_destino        VARCHAR(77)   NOT NULL,
    valor                NUMERIC(19,2) NOT NULL,
    descricao            VARCHAR(140),
    estado               VARCHAR(10)   NOT NULL,
    ledger_transacao_id  UUID,
    motivo_codigo        VARCHAR(40),
    motivo_mensagem      VARCHAR(255),
    tentativas           INTEGER       NOT NULL DEFAULT 0,
    criado_em            TIMESTAMPTZ   NOT NULL,
    atualizado_em        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_pagamento_pix_e2e     CHECK (end_to_end_id ~ '^E[0-9]{20}[A-Za-z0-9]{11}$'),
    CONSTRAINT ck_pagamento_pix_hash    CHECK (hash_pedido ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_pagamento_pix_valor   CHECK (valor > 0),
    CONSTRAINT ck_pagamento_pix_estado  CHECK (estado IN ('PENDENTE', 'LIQUIDADO', 'REJEITADO', 'INCERTO')),
    CONSTRAINT ck_pagamento_pix_contas  CHECK (conta_origem_id <> conta_destino_id),
    -- Liquidado sempre aponta para a transação da Ledger; os demais, nunca.
    CONSTRAINT ck_pagamento_pix_ledger  CHECK ((estado = 'LIQUIDADO') = (ledger_transacao_id IS NOT NULL))
);

-- A reconciliação procura apenas os incertos, do mais antigo para o mais novo.
CREATE INDEX ix_pagamento_pix_incerto ON pagamento_pix (atualizado_em) WHERE estado = 'INCERTO';
