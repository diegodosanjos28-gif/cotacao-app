-- Ajuste manual da distribuição do Mapa de Compra: força um item pra um fornecedor
-- específico (fornecedor_id_override não-nulo) ou remove o item da ordem
-- (fornecedor_id_override nulo — diferente de "sem oferta nenhuma", esse item tem
-- oferta válida, só foi excluído por escolha do operador). Um registro por item
-- ajustado, não por cenário: sobrevive à troca de cenário no Mapa de Compra (ver
-- TODO-PROTOTYPE.md item 2). tenant_id direto, mesmo padrão de cotacao_fornecedor
-- (V15).
CREATE TABLE cotacao_ajuste_manual (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenant(id),
    cotacao_id             UUID NOT NULL REFERENCES cotacao(id) ON DELETE CASCADE,
    cotacao_produto_id     UUID NOT NULL REFERENCES cotacao_produto(id) ON DELETE CASCADE,
    fornecedor_id_override UUID REFERENCES fornecedor(id),
    criado_por             UUID NOT NULL REFERENCES usuario(id),
    criado_em              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    atualizado_em          TIMESTAMPTZ,

    UNIQUE (cotacao_id, cotacao_produto_id)
);

CREATE INDEX idx_cotacao_ajuste_manual_cotacao ON cotacao_ajuste_manual(cotacao_id);
CREATE INDEX idx_cotacao_ajuste_manual_tenant ON cotacao_ajuste_manual(tenant_id);

ALTER TABLE cotacao_ajuste_manual ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cotacao_ajuste_manual
    USING (is_admin_request() OR tenant_id = current_tenant_id())
    WITH CHECK (is_admin_request() OR tenant_id = current_tenant_id());
