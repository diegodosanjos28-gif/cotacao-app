-- Prompt 20 — tabela de referência genérica que substitui EventoWhatsApp como fonte de
-- "que ação de negócio o sistema tomou em resposta a uma mensagem recebida"
-- (AcaoClienteEnum, pacote notificacao.acaocliente) × "resultado dessa ação"
-- (ResultadoAcaoCliente, renomeado de ResultadoNotificacao). Tabela GLOBAL — sem
-- tenant_id, o mesmo catálogo vale para todo tenant (mesmo espírito do padrão "marca"
-- citado no comentário do V28). Populada por AcaoClienteSetupRunner no boot
-- (SmartInitializingSingleton, mesmo padrão de AdminBootstrapRunner), NUNCA por INSERT
-- nesta migration: o enum Java AcaoClienteEnum é a fonte da verdade de quais linhas devem
-- existir, não o SQL — ver javadoc de AcaoClienteSetupRunner.
CREATE TABLE acao_cliente (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    acao      VARCHAR(30) NOT NULL
                  CHECK (acao IN ('INSERIR_PRODUTOS', 'REGISTRAR_RESPOSTA', 'NAO_IDENTIFICADO')),
    resultado VARCHAR(10)
                  CHECK (resultado IN ('SUCESSO', 'ERRO')),
    descricao VARCHAR(255) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Postgres trata NULL como distinto em UNIQUE simples — sem os 2 índices parciais
-- abaixo, 2 instâncias da aplicação subindo ao mesmo tempo (deploy rolling) poderiam
-- inserir 2 linhas NAO_IDENTIFICADO (resultado NULL) sem violar nenhuma constraint,
-- quebrando o pressuposto de singleton que
-- AcaoClienteCenarioRepository.findByAcaoAndResultadoIsNull() depende.
CREATE UNIQUE INDEX uq_acao_cliente_com_resultado ON acao_cliente (acao, resultado) WHERE resultado IS NOT NULL;
CREATE UNIQUE INDEX uq_acao_cliente_sem_resultado ON acao_cliente (acao) WHERE resultado IS NULL;

ALTER TABLE acao_cliente ENABLE ROW LEVEL SECURITY;

-- Leitura sempre liberada: catálogo global sem tenant_id, e todo tenant precisa
-- resolvê-lo em contexto NÃO admin (WhatsappTemplateMensageriaService roda por request
-- de webhook, tenant comum). Escrita só em contexto admin — só AcaoClienteSetupRunner
-- escreve aqui (seta TenantContext.setAdmin(true)); nenhuma rota HTTP de tenant grava
-- nesta tabela.
--
-- 4 policies separadas por operação, não uma única USING/WITH CHECK genérica (achado
-- de security-reviewer): no Postgres, WITH CHECK NUNCA é avaliado em DELETE — só
-- USING é (só INSERT/UPDATE têm "linha nova" pra checar). Uma policy única com
-- USING(true) deixaria DELETE liberado pra qualquer sessão não-admin, apesar da
-- intenção documentada acima — exatamente o mesmo bean que lê esta tabela em contexto
-- de tenant comum (WhatsappTemplateMensageriaService, via webhook) herda
-- transitivamente o mesmo repository que também expõe delete/deleteById.
CREATE POLICY acao_cliente_leitura ON acao_cliente
    FOR SELECT USING (true);

CREATE POLICY acao_cliente_insercao ON acao_cliente
    FOR INSERT WITH CHECK (is_admin_request());

CREATE POLICY acao_cliente_atualizacao ON acao_cliente
    FOR UPDATE USING (is_admin_request()) WITH CHECK (is_admin_request());

CREATE POLICY acao_cliente_exclusao ON acao_cliente
    FOR DELETE USING (is_admin_request());
