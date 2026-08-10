-- fornecedor_produto.atualizado_em existia desde V8, mas criado_em faltava.
-- Necessário porque FornecedorProduto extends TenantAuditEntity (que mapeia ambos).
ALTER TABLE fornecedor_produto
    ADD COLUMN IF NOT EXISTS criado_em TIMESTAMPTZ NOT NULL DEFAULT NOW();
