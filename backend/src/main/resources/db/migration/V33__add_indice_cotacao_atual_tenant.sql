-- Landing "Cotação atual" (refactor Entrada de Dados, tenant-wide): a query busca a
-- cotação RASCUNHO/EM_ANDAMENTO mais recente do tenant inteiro, sem filtro por
-- criadoPor. idx_cotacao_atividade (V5) não cobre esse caso porque é parcial só pra
-- status='EM_ANDAMENTO' (não inclui RASCUNHO); idx_cotacao_whatsapp_usuario (V24) é
-- parcial por criado_por+canal_origem=WHATSAPP, filtro que esta query
-- deliberadamente não usa (decisão: "cotação atual" é da loja, não do usuário).
CREATE INDEX idx_cotacao_atual_tenant ON cotacao (tenant_id, ultima_atividade_em DESC)
    WHERE status IN ('RASCUNHO', 'EM_ANDAMENTO');
