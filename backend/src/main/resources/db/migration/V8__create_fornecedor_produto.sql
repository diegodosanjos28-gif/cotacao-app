-- Fase futura: catálogo direto de fornecedores parceiros.
-- Tabela criada agora para evitar migration disruptiva depois; nenhum endpoint a usa no MVP.
CREATE TABLE fornecedor_produto (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    fornecedor_id   UUID NOT NULL REFERENCES fornecedor(id),
    produto_id      UUID NOT NULL REFERENCES produto(id),
    preco_referencia NUMERIC(12, 2),
    embalagem_qtd   INTEGER,
    atualizado_em   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    origem          VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
                        CHECK (origem IN ('MANUAL', 'API_PARCEIRO')),

    UNIQUE (fornecedor_id, produto_id)
);

CREATE INDEX idx_fp_tenant ON fornecedor_produto(tenant_id);
