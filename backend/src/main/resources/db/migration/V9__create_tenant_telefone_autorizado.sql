CREATE TABLE tenant_telefone_autorizado (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    numero_whatsapp  VARCHAR(20) NOT NULL UNIQUE,
    nome_contato     VARCHAR(100),
    ativo            BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_telefone_tenant ON tenant_telefone_autorizado(tenant_id);
CREATE INDEX idx_telefone_numero ON tenant_telefone_autorizado(numero_whatsapp) WHERE ativo = TRUE;
