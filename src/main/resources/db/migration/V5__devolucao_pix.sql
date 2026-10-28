-- ============================================================================
-- V5 - Devoluções Pix (Etapa 4)
--
-- A devolução é uma NOVA transferência na Ledger, no sentido inverso
-- (recebedor -> pagador), ligada ao pagamento original pela FK. O original nunca
-- é alterado nem apagado: o valor já devolvido é sempre a soma desta tabela,
-- do mesmo jeito que o saldo da Ledger é a soma dos lançamentos.
--
-- Mesmo ciclo de vida do pagamento (PENDENTE / LIQUIDADO / REJEITADO / INCERTO)
-- e mesma garantia de idempotência: a chave primária é o identificador da
-- devolução (prefixo D), também usado como Idempotency-Key na Ledger.
-- ============================================================================
CREATE TABLE devolucao_pix (
    id_devolucao            VARCHAR(32)   PRIMARY KEY,
    end_to_end_id_original  VARCHAR(32)   NOT NULL REFERENCES pagamento_pix (end_to_end_id),
    hash_pedido             VARCHAR(64)   NOT NULL,      -- SHA-256 (hex) do pedido normalizado
    conta_origem_id         UUID          NOT NULL,      -- recebedor do pagamento original
    conta_destino_id        UUID          NOT NULL,      -- pagador do pagamento original
    valor                   NUMERIC(19,2) NOT NULL,
    motivo_devolucao        VARCHAR(4)    NOT NULL,
    descricao               VARCHAR(140),
    estado                  VARCHAR(10)   NOT NULL,
    ledger_transacao_id     UUID,
    motivo_codigo           VARCHAR(40),
    motivo_mensagem         VARCHAR(255),
    tentativas              INTEGER       NOT NULL DEFAULT 0,
    criado_em               TIMESTAMPTZ   NOT NULL,
    atualizado_em           TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_devolucao_pix_id      CHECK (id_devolucao ~ '^D[0-9]{20}[A-Za-z0-9]{11}$'),
    CONSTRAINT ck_devolucao_pix_hash    CHECK (hash_pedido ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_devolucao_pix_valor   CHECK (valor > 0),
    -- Códigos inspirados no padrão ISO 20022 usado pelo Bacen:
    -- MD06 = solicitada pelo cliente, BE08 = erro operacional, FR01 = fraude.
    CONSTRAINT ck_devolucao_pix_motivo  CHECK (motivo_devolucao IN ('MD06', 'BE08', 'FR01')),
    CONSTRAINT ck_devolucao_pix_estado  CHECK (estado IN ('PENDENTE', 'LIQUIDADO', 'REJEITADO', 'INCERTO')),
    CONSTRAINT ck_devolucao_pix_contas  CHECK (conta_origem_id <> conta_destino_id),
    CONSTRAINT ck_devolucao_pix_ledger  CHECK ((estado = 'LIQUIDADO') = (ledger_transacao_id IS NOT NULL))
);

-- Soma do que já foi devolvido de um pagamento e listagem por pagamento.
CREATE INDEX ix_devolucao_pix_original ON devolucao_pix (end_to_end_id_original);

-- A reconciliação procura apenas as incertas, da mais antiga para a mais nova.
CREATE INDEX ix_devolucao_pix_incerto ON devolucao_pix (atualizado_em) WHERE estado = 'INCERTO';
