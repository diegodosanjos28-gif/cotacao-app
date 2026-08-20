-- Autocomplete de Produto (paginação server-side, achado 08-20: catálogo cresce sem
-- limite por tenant) passou a rodar ProdutoRepository.buscarPorNome a cada tecla
-- digitada (debounced), em vez de uma vez só no carregamento da tela. O padrão
-- LIKE '%termo%' tem wildcard à esquerda — btree comum (idx_produto_nome, V4) não
-- serve pra esse formato, cada busca faria sequential scan do catálogo inteiro do
-- tenant. pg_trgm quebra o nome em trigramas e indexa via GIN, tornando ILIKE
-- '%termo%' sargável independente do tamanho do catálogo. Ver ProdutoRepository:
-- a query trocou de LOWER(nome) LIKE LOWER(...) pra nome ILIKE ... porque o operador
-- precisa bater exatamente com o suportado pelo índice (gin_trgm_ops cobre ILIKE
-- direto na coluna, não uma expressão LOWER() em cima dela).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_produto_nome_trgm ON produto USING GIN (nome gin_trgm_ops);
