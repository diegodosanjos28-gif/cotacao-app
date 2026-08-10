-- Catálogo de marcas usado por MatchingProdutoService.extrairMarca. tenant_id NULL =
-- marca default global, visível a todo tenant (inclusive tenants criados no futuro —
-- não há hoje nenhum hook de onboarding de tenant onde plugar um backfill por-tenant,
-- então o default é modelado como dado global em vez de copiado linha a linha).
-- Um tenant pode adicionar suas próprias marcas (tenant_id = seu próprio id), mas nunca
-- inserir/alterar uma linha global — ver policy abaixo.
CREATE TABLE marca (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenant(id),
    nome      VARCHAR(100) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unicidade separada por particionamento (global vs. por tenant) porque Postgres trata
-- NULL como distinto em UNIQUE simples — duas marcas globais com o mesmo nome não
-- seriam pegas por um UNIQUE(tenant_id, nome) comum.
CREATE UNIQUE INDEX idx_marca_global_unica ON marca (nome) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX idx_marca_tenant_unica ON marca (tenant_id, nome) WHERE tenant_id IS NOT NULL;

ALTER TABLE marca ENABLE ROW LEVEL SECURITY;

-- Leitura: linhas globais (tenant_id IS NULL) + linhas do próprio tenant. Escrita: só o
-- próprio tenant, nunca uma linha global (isso protege o catálogo default de ser
-- alterado por um tenant comum; só migrations, rodando como cotacao_admin com
-- BYPASSRLS, escrevem linhas globais).
CREATE POLICY tenant_isolation ON marca
    USING (is_admin_request() OR tenant_id IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());

-- Seed default: porte literal da lista MARCAS_CONHECIDAS que vivia hardcoded em
-- MatchingProdutoService (mesmas ~72 marcas, mesmos valores) — agora como dado global
-- em vez de constante Java, para permitir extensão por tenant sem redeploy.
INSERT INTO marca (tenant_id, nome) VALUES
    (NULL, 'heinz'), (NULL, 'hellmanns'), (NULL, 'hellmann'), (NULL, 'uniao'),
    (NULL, 'união'), (NULL, 'liza'), (NULL, 'marata'), (NULL, 'amafil'),
    (NULL, 'docesucar'), (NULL, 'coca-cola'), (NULL, 'coca cola'), (NULL, 'pepsi'),
    (NULL, 'guarana'), (NULL, 'guaraná'), (NULL, 'antarctica'), (NULL, 'skol'),
    (NULL, 'brahma'), (NULL, 'sadia'), (NULL, 'perdigao'), (NULL, 'perdigão'),
    (NULL, 'seara'), (NULL, 'friboi'), (NULL, 'swift'), (NULL, 'camil'),
    (NULL, 'tio joao'), (NULL, 'tio joão'), (NULL, 'kicaldo'), (NULL, 'yoki'),
    (NULL, 'vitarella'), (NULL, 'adria'), (NULL, 'renata'), (NULL, 'barilla'),
    (NULL, 'nissin'), (NULL, 'maggi'), (NULL, 'knorr'), (NULL, 'arisco'),
    (NULL, 'fugini'), (NULL, 'sakura'), (NULL, 'predilecta'), (NULL, 'quero'),
    (NULL, 'cica'), (NULL, 'etti'), (NULL, 'pomarola'), (NULL, 'cirio'),
    (NULL, 'elefante'), (NULL, 'tang'), (NULL, 'clight'), (NULL, 'royal'),
    (NULL, 'dr.oetker'), (NULL, 'fleischmann'), (NULL, 'dona benta'), (NULL, 'sol'),
    (NULL, 'bunge'), (NULL, 'soya'), (NULL, 'salada'), (NULL, 'cocinero'),
    (NULL, 'primor'), (NULL, 'abc'), (NULL, 'neve'), (NULL, 'scott'),
    (NULL, 'personal'), (NULL, 'mili'), (NULL, 'sulani'), (NULL, 'qualita'),
    (NULL, 'omo'), (NULL, 'brilhante'), (NULL, 'tixan'), (NULL, 'ype'),
    (NULL, 'minuano'), (NULL, 'limpol'), (NULL, 'bombril'), (NULL, 'assolan');
