CREATE TABLE produto (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenant(id),
    nome                 VARCHAR(255) NOT NULL,
    marca                VARCHAR(100),
    peso_volume_valor    NUMERIC(10, 3),
    peso_volume_unidade  VARCHAR(5),
    unidade_padrao       VARCHAR(10),
    embalagem_qtd_sugerida INTEGER,
    criado_em            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_produto_tenant ON produto(tenant_id);
CREATE INDEX idx_produto_nome ON produto(tenant_id, nome);
