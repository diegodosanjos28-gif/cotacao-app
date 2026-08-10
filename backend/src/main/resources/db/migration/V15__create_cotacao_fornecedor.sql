-- Estado de progresso de cada fornecedor dentro de uma cotação (fluxo sequencial
-- de Entrada de Dados: PENDENTE -> PROCESSADO (preview gerado) -> CONFIRMADO
-- (Conferência aceita, dados persistidos em cotacao_produto_fornecedor)).
-- tenant_id direto (não join indireto como cotacao_produto/cotacao_produto_fornecedor):
-- tabela nova, sem legado a seguir, mantém consistência com o padrão majoritário
-- (tenant, usuario, fornecedor, produto, cotacao, fornecedor_produto).
CREATE TABLE cotacao_fornecedor (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    cotacao_id    UUID NOT NULL REFERENCES cotacao(id) ON DELETE CASCADE,
    fornecedor_id UUID NOT NULL REFERENCES fornecedor(id),
    ordem         INTEGER NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDENTE'
                     CHECK (status IN ('PENDENTE', 'PROCESSADO', 'CONFIRMADO')),
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    atualizado_em TIMESTAMPTZ,

    UNIQUE (cotacao_id, fornecedor_id)
);

CREATE INDEX idx_cotacao_fornecedor_cotacao ON cotacao_fornecedor(cotacao_id);
CREATE INDEX idx_cotacao_fornecedor_tenant ON cotacao_fornecedor(tenant_id);

ALTER TABLE cotacao_fornecedor ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cotacao_fornecedor
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());
