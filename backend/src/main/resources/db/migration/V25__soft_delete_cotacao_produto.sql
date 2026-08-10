-- Soft delete de cotacao_produto (Prompt 12 — Grid Unificado de Entrada de Dados).
-- Exclusão de item passa a ser sempre soft-delete (regra 2 do CLAUDE.md, mesmo
-- racional de auditoria do snapshot de embalagem): a linha nunca é apagada
-- fisicamente, só marcada como removida, e some do Comparativo/Mapa a partir daí.
ALTER TABLE cotacao_produto
    ADD COLUMN removido_em  TIMESTAMPTZ NULL,
    ADD COLUMN removido_por UUID NULL REFERENCES usuario(id);

-- Índice dedicado às leituras de negócio (Comparativo, Mapa, grid, matching de
-- resposta de fornecedor) — todas passam a filtrar removido_em IS NULL.
CREATE INDEX idx_cotacao_produto_vivo ON cotacao_produto(cotacao_id) WHERE removido_em IS NULL;

-- A unique parcial original (V6) não sabia de removido_em — sem recriá-la, adicionar
-- de volta um item cujo produto foi soft-deleted colidiria com a linha morta e
-- falharia com um 409 genérico de constraint em vez de suceder normalmente.
DROP INDEX idx_cotacao_produto_unico_por_produto;
CREATE UNIQUE INDEX idx_cotacao_produto_unico_por_produto
    ON cotacao_produto(cotacao_id, produto_id)
    WHERE produto_id IS NOT NULL AND removido_em IS NULL;
