CREATE TABLE usuario_telefone_autorizado (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id       UUID NOT NULL REFERENCES usuario(id),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    numero_whatsapp  VARCHAR(20) NOT NULL UNIQUE,
    nome_contato     VARCHAR(100),
    ativo            BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    atualizado_em    TIMESTAMPTZ
);

CREATE INDEX idx_usuario_telefone_tenant ON usuario_telefone_autorizado(tenant_id);
CREATE INDEX idx_usuario_telefone_usuario ON usuario_telefone_autorizado(usuario_id);
CREATE INDEX idx_usuario_telefone_numero ON usuario_telefone_autorizado(numero_whatsapp) WHERE ativo = TRUE;

ALTER TABLE usuario_telefone_autorizado ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usuario_telefone_autorizado
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());
