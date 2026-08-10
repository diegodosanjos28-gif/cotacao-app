CREATE TABLE fornecedor (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL REFERENCES tenant(id),
    nome                      VARCHAR(255) NOT NULL,
    prazo_entrega_padrao      VARCHAR(100),
    condicao_pagamento_padrao VARCHAR(100),
    pedido_minimo_padrao      NUMERIC(12, 2),
    observacoes_padrao        TEXT,
    status                    VARCHAR(20) NOT NULL DEFAULT 'ATIVO'
                                  CHECK (status IN ('ATIVO', 'PENDENTE_DADOS', 'INATIVO')),
    origem_cadastro           VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
                                  CHECK (origem_cadastro IN ('MANUAL', 'WHATSAPP_AUTO')),
    criado_em                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fornecedor_tenant ON fornecedor(tenant_id);
