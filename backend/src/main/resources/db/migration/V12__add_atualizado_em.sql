-- Adiciona atualizado_em às tabelas gerenciadas por TenantAuditEntity
ALTER TABLE fornecedor               ADD COLUMN IF NOT EXISTS atualizado_em TIMESTAMPTZ;
ALTER TABLE produto                  ADD COLUMN IF NOT EXISTS atualizado_em TIMESTAMPTZ;
ALTER TABLE cotacao                  ADD COLUMN IF NOT EXISTS atualizado_em TIMESTAMPTZ;
ALTER TABLE tenant_telefone_autorizado ADD COLUMN IF NOT EXISTS atualizado_em TIMESTAMPTZ;
-- fornecedor_produto já tem atualizado_em (V8)
