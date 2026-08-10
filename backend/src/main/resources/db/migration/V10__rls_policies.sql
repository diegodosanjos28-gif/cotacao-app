-- Cria roles de aplicação.
-- cotacao_app: role de produção, sujeito ao RLS.
-- cotacao_admin: role com BYPASSRLS para migrações e operações de manutenção (não usado pelo app em runtime).
-- O app conecta sempre como cotacao_app e usa app.is_admin=true para bypassar RLS programaticamente.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cotacao_app') THEN
        CREATE ROLE cotacao_app LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cotacao_admin') THEN
        CREATE ROLE cotacao_admin LOGIN BYPASSRLS;
    END IF;
END
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cotacao_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cotacao_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO cotacao_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO cotacao_admin;

-- O GRANT acima só alcança tabelas que já existem quando esta migration roda (V1-V9).
-- Isso faz esta migration rodar ANTES de qualquer tabela nova (V11+), e ALTER DEFAULT
-- PRIVILEGES garante que toda tabela/sequence criada depois por este mesmo role
-- (o role admin/Flyway) já nasça com grant para cotacao_app/cotacao_admin, sem
-- precisar repetir o GRANT em cada migration nova.
-- IMPORTANTE: só vale enquanto migrations futuras rodarem com o MESMO role
-- configurado em spring.flyway.user hoje. Se essa credencial mudar, rode este
-- ALTER DEFAULT PRIVILEGES de novo com o novo role (ou conceda manualmente).
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cotacao_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cotacao_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO cotacao_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO cotacao_admin;

-- Senha de login do role de runtime, vinda de um placeholder Flyway
-- (spring.flyway.placeholders.app_db_password, por sua vez de APP_DB_PASSWORD).
-- Nunca hardcoded. O app conecta em runtime como cotacao_app (não como o role
-- admin/superuser usado só para rodar migrations) — é isso que faz o RLS deste
-- arquivo ser de fato avaliado pelo Postgres, em vez de ignorado por uma conexão
-- superuser. Rotação de senha depois do deploy inicial deve ser feita fora do
-- Flyway (ALTER ROLE direto), não editando este arquivo.
ALTER ROLE cotacao_app WITH LOGIN PASSWORD '${app_db_password}';

-- Helper: retorna o tenant_id da sessão como UUID, ou NULL se vazio/inválido.
-- Usado em todas as policies para evitar erro de cast de string vazia para UUID.
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS UUID AS $$
DECLARE
    raw TEXT := current_setting('app.current_tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN RETURN NULL; END IF;
    RETURN raw::UUID;
EXCEPTION WHEN invalid_text_representation THEN RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- Helper: retorna true quando o request é de ADMIN_PRX.
CREATE OR REPLACE FUNCTION is_admin_request() RETURNS BOOLEAN AS $$
BEGIN
    RETURN COALESCE(current_setting('app.is_admin', true), '') = 'true';
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- tenant: visível apenas para admin PRX.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_admin_only ON tenant
    USING (is_admin_request())
    WITH CHECK (is_admin_request());

-- usuario: operador vê apenas o próprio tenant; admin vê todos.
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usuario
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());

-- fornecedor
ALTER TABLE fornecedor ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fornecedor
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());

-- produto
ALTER TABLE produto ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON produto
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());

-- cotacao
ALTER TABLE cotacao ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cotacao
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());

-- cotacao_produto: isolamento via cotacao pai
ALTER TABLE cotacao_produto ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cotacao_produto
    USING (
        is_admin_request()
        OR cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = current_tenant_id())
    )
    WITH CHECK (
        is_admin_request()
        OR cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = current_tenant_id())
    );

-- cotacao_produto_fornecedor: isolamento via cotacao_produto → cotacao
ALTER TABLE cotacao_produto_fornecedor ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cotacao_produto_fornecedor
    USING (
        is_admin_request()
        OR cotacao_produto_id IN (
            SELECT cp.id FROM cotacao_produto cp
            JOIN cotacao c ON c.id = cp.cotacao_id
            WHERE c.tenant_id = current_tenant_id()
        )
    )
    WITH CHECK (
        is_admin_request()
        OR cotacao_produto_id IN (
            SELECT cp.id FROM cotacao_produto cp
            JOIN cotacao c ON c.id = cp.cotacao_id
            WHERE c.tenant_id = current_tenant_id()
        )
    );

-- fornecedor_produto (fase futura)
ALTER TABLE fornecedor_produto ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fornecedor_produto
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());

-- tenant_telefone_autorizado
ALTER TABLE tenant_telefone_autorizado ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_telefone_autorizado
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());
