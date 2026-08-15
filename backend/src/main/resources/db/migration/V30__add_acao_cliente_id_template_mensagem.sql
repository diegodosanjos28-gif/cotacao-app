-- Prompt 20 — substitui as colunas evento/resultado de template_mensagem por uma FK
-- única pra acao_cliente (ver V29). NULLABLE por necessidade, não por esquecimento:
-- Flyway roda esta migration ANTES de qualquer bean Spring existir — acao_cliente
-- ainda está VAZIA neste momento. AcaoClienteSetupRunner (afterSingletonsInstantiated,
-- depois de todos os beans) é quem popula acao_cliente E faz o backfill desta coluna.
-- Nenhuma request real chega ao app com acao_cliente_id NULL: o SetupRunner roda antes
-- do Tomcat abrir os connectors (mesma garantia usada por AdminBootstrapRunner). NÃO
-- adicionar NOT NULL aqui nem em migration futura próxima sem antes confirmar em
-- produção que o backfill já rodou pelo menos uma vez.
ALTER TABLE template_mensagem ADD COLUMN acao_cliente_id UUID REFERENCES acao_cliente(id);

CREATE INDEX idx_template_mensagem_acao_cliente ON template_mensagem(acao_cliente_id);

-- Lista ORDENADA de identificadores do catálogo (CatalogoParametrosNotificacao), na
-- ordem em que preenchem {{1}}, {{2}}, ... no envio real pra Meta — não fazia parte do
-- schema V28 original, mas continua fazendo parte do desenho independente da troca de
-- evento/resultado por acao_cliente_id.
ALTER TABLE template_mensagem ADD COLUMN parametros_ordenados TEXT[] NOT NULL DEFAULT '{}';

-- `resultado` deixa de ser escrito pelo Hibernate (removido da entidade
-- TemplateMensagem) — relaxado para NULLABLE, NÃO apagado: é a única fonte pra
-- AcaoClienteSetupRunner distinguir SUCESSO/ERRO no backfill dos templates legados
-- (lido via JdbcTemplate puro, fora do mapeamento JPA). Mesmo tratamento já dado a
-- descricao_parametros (coluna órfã mantida). DROP COLUMN fica pra uma migration de
-- limpeza futura, só depois do backfill confirmado em produção.
ALTER TABLE template_mensagem ALTER COLUMN resultado DROP NOT NULL;

-- template_mensagem_tenant_id_resultado_key (UNIQUE(tenant_id, resultado) da V28) não
-- modela mais a regra de negócio — a vaga agora é 1:1 com acao_cliente_id.
ALTER TABLE template_mensagem DROP CONSTRAINT template_mensagem_tenant_id_resultado_key;

-- Sem WHERE: NULL é distinto em UNIQUE simples, então múltiplas linhas legadas com
-- acao_cliente_id NULL no MESMO tenant coexistem sem violar nada — exatamente a janela
-- transitória entre esta migration e o backfill do AcaoClienteSetupRunner.
CREATE UNIQUE INDEX uq_template_mensagem_tenant_acao_cliente
    ON template_mensagem (tenant_id, acao_cliente_id);

-- Backup durável do conteúdo descartado no backfill (achado de security-reviewer):
-- quando um tenant tinha os 2 templates legados (SUCESSO e ERRO) configurados,
-- AcaoClienteSetupRunner promove o de ERRO e DELETA o de SUCESSO — dado real de
-- produção, não só limpeza interna (mensagem visível a cliente final). Gravar aqui
-- ANTES do DELETE dá recuperação manual sem depender de retenção/grep de log. Tabela
-- de auditoria interna, sem RLS de tenant (só ADMIN_PRX ou acesso direto ao banco
-- deveriam ler isto).
CREATE TABLE template_mensagem_legado_backup (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_mensagem_id   UUID NOT NULL,
    tenant_id              UUID NOT NULL,
    resultado              VARCHAR(10) NOT NULL,
    nome_template_meta     VARCHAR(255) NOT NULL,
    idioma                 VARCHAR(10) NOT NULL,
    conteudo               TEXT,
    descricao_parametros   TEXT,
    arquivado_em           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE template_mensagem_legado_backup ENABLE ROW LEVEL SECURITY;

CREATE POLICY template_mensagem_legado_backup_admin ON template_mensagem_legado_backup
    USING (is_admin_request())
    WITH CHECK (is_admin_request());
