-- Snapshot congelado do Mapa de Compra no momento da finalização (POST
-- /cotacoes/{id}/finalizar): a tela de uma cotação FINALIZADA passa a mostrar
-- exatamente essa distribuição, não mais o resultado recalculado ao vivo pelo
-- algoritmo do cenário — evita que a tela mude retroativamente se dado subjacente
-- mudar depois (fornecedor inativado, preço reconfirmado, etc).
-- Guardado como TEXT (JSON serializado via Jackson), não jsonb: não precisamos de
-- query/índice sobre o conteúdo, só leitura/escrita do blob inteiro — TEXT evita
-- depender de um UserType/conversor JSONB customizado sem necessidade real.
ALTER TABLE cotacao ADD COLUMN mapa_final_snapshot TEXT;
