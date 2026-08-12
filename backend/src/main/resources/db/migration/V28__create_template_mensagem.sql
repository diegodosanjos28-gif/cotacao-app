-- Prompt 18 — cadastro de templates de mensagem WhatsApp (Meta Template API), por
-- tenant. Exatamente 2 linhas por tenant no máximo (SUCESSO/ERRO) — o "tipo" da
-- mensagem recebida (lista de produtos/resposta de fornecedor/desconhecido) não
-- seleciona qual template usar, vira parâmetro dinâmico dentro do template escolhido
-- (ver WhatsappTemplateMensageriaService). Dado de configuração por tenant, não
-- catálogo compartilhado — segue o padrão fornecedor/TenantAuditEntity (tenant_id NOT
-- NULL + RLS), não o padrão marca (global/nullable).
CREATE TABLE template_mensagem (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenant(id),
    resultado            VARCHAR(10) NOT NULL CHECK (resultado IN ('SUCESSO', 'ERRO')),
    nome_template_meta   VARCHAR(255) NOT NULL,
    idioma               VARCHAR(10) NOT NULL DEFAULT 'pt_BR',
    conteudo             TEXT,
    descricao_parametros TEXT,
    ativo                BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    atualizado_em        TIMESTAMPTZ,

    UNIQUE (tenant_id, resultado)
);

CREATE INDEX idx_template_mensagem_tenant ON template_mensagem(tenant_id);

ALTER TABLE template_mensagem ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON template_mensagem
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());
