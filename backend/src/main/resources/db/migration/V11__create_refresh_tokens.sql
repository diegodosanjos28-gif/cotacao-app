-- Sem RLS de propósito (exceção à regra 1 do CLAUDE.md — "toda tabela com dado de
-- tenant tem tenant_id + policy de RLS"): esta tabela não tem tenant_id, é escopada
-- por usuario_id, e todo acesso hoje em AuthService é por PK (jti) ou por
-- usuario_id explícito (nunca um scan sem filtro) — ver RefreshTokenRepository.
-- Login/refresh (TenantFilter) rodam com RLS bypassado (is_admin_request()), então
-- se esta tabela tivesse uma policy dependente de tenant_id/current_tenant_id() ela
-- seria irrelevante nesse caminho de qualquer forma. Risco residual: um endpoint
-- futuro que faça um findAll()/listagem administrativa nesta tabela não teria
-- nenhum backstop de banco — hoje não existe esse endpoint. Achado de
-- security-reviewer em 2026-07-09.
CREATE TABLE refresh_token (
    jti        UUID PRIMARY KEY,
    usuario_id UUID NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    usado      BOOLEAN NOT NULL DEFAULT FALSE,
    expira_em  TIMESTAMPTZ NOT NULL,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_token_usuario ON refresh_token(usuario_id);
