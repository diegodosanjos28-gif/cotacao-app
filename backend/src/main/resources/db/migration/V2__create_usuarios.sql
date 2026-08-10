CREATE TABLE usuario (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenant(id),
    email       VARCHAR(255) NOT NULL UNIQUE,
    senha_hash  VARCHAR(255) NOT NULL,
    papel       VARCHAR(20) NOT NULL
                    CHECK (papel IN ('ADMIN_PRX', 'OPERADOR_CLIENTE')),
    ativo       BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usuario_tenant ON usuario(tenant_id) WHERE tenant_id IS NOT NULL;
CREATE INDEX idx_usuario_email ON usuario(email);
CREATE INDEX idx_usuario_tenant_papel ON usuario(tenant_id, papel) WHERE tenant_id IS NOT NULL;
