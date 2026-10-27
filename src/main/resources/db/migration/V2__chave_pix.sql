-- ============================================================================
-- V2 - Chaves Pix (Etapa 1)
-- ============================================================================
CREATE TABLE chave_pix (
    id            UUID         PRIMARY KEY,
    tipo          VARCHAR(10)  NOT NULL,
    valor         VARCHAR(77)  NOT NULL,           -- 77 = maior chave possível (e-mail)
    conta_id      VARCHAR(64)  NOT NULL,           -- conta na Ledger API
    titular_nome  VARCHAR(120) NOT NULL,
    titular_cpf   VARCHAR(11)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    criada_em     TIMESTAMPTZ  NOT NULL,
    excluida_em   TIMESTAMPTZ,

    -- Defesa em profundidade: o banco rejeita estados que a aplicação nunca deveria gerar.
    CONSTRAINT ck_chave_pix_tipo        CHECK (tipo IN ('CPF', 'EMAIL', 'ALEATORIA')),
    CONSTRAINT ck_chave_pix_status      CHECK (status IN ('ATIVA', 'EXCLUIDA')),
    CONSTRAINT ck_chave_pix_titular_cpf CHECK (titular_cpf ~ '^[0-9]{11}$'),
    CONSTRAINT ck_chave_pix_exclusao    CHECK ((status = 'EXCLUIDA') = (excluida_em IS NOT NULL))
);

-- Unicidade apenas entre chaves ATIVAS: após a exclusão lógica, o valor pode ser
-- recadastrado (inclusive em outra conta) sem apagar o histórico.
CREATE UNIQUE INDEX ux_chave_pix_valor_ativa ON chave_pix (valor) WHERE status = 'ATIVA';

-- Atende à contagem do limite por conta e à listagem por conta.
CREATE INDEX ix_chave_pix_conta_ativa ON chave_pix (conta_id) WHERE status = 'ATIVA';
