CREATE TABLE cotacao_produto (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cotacao_id      UUID NOT NULL REFERENCES cotacao(id) ON DELETE CASCADE,
    produto_id      UUID REFERENCES produto(id),
    texto_original  VARCHAR(500) NOT NULL,
    quantidade      NUMERIC(10, 3) NOT NULL,
    unidade         VARCHAR(10) NOT NULL,
    ordem           INTEGER NOT NULL
);

CREATE INDEX idx_cotacao_produto_cotacao ON cotacao_produto(cotacao_id);
CREATE INDEX idx_cotacao_produto_produto ON cotacao_produto(produto_id) WHERE produto_id IS NOT NULL;

-- Rede de segurança contra duplicação de item de cotação (ex.: reenvio de lista
-- sobreposta via POST /cotacoes/{id}/lista). Parcial porque produto_id é nullable
-- para linhas ainda não reconciliadas com o catálogo — essas não têm uma chave
-- natural de dedup no banco e são tratadas em CotacaoListaService (dedup por
-- texto_original em nível de aplicação).
CREATE UNIQUE INDEX idx_cotacao_produto_unico_por_produto
    ON cotacao_produto(cotacao_id, produto_id) WHERE produto_id IS NOT NULL;
