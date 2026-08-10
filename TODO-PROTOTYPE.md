# TODO — gaps de backend descobertos na migração de UX/UI do protótipo

Este arquivo lista funcionalidades do protótipo `COTA_TESTE_29_06_-_V5.html` que **não
foram construídas** na passada de UI/UX atual porque dependem de dado, campo ou
endpoint que o backend não expõe hoje. Cada item aqui é uma lacuna real, não uma
escolha de design — a UI foi construída só com o que a API já suporta, para não
silenciar/fingir dado que não existe. Ver também
`docs/documentacao-tecnica-sistema-cotacao-prx.md` seção 11 (Histórico de Preços e
Economia), que já tem desenho completo + `Prompt 11`.

## 1. Histórico de Preços e Economia (módulo inteiro)

Já documentado em detalhe na seção 11 da doc técnica (modelo de dados
`historico_preco_produto` / `historico_economia_cotacao`, endpoints
`GET /produtos/{id}/historico-precos` e `GET /economia`, `Prompt 11`). Não repito aqui
— só linkando. **Bloqueador**: nenhuma persistência entre cotações existe hoje.

## 2. Mover produto manualmente entre fornecedores no Mapa de Compra

Protótipo: `abrirMoverProduto(prodId)` permite ajustar manualmente a distribuição de
um cenário (tirar um produto do fornecedor sugerido e atribuir a outro), mostrando o
impacto no total (mais/menos/igual).

**Gap**: `GET /cotacoes/{id}/mapa?cenario=` sempre recalcula a distribuição pura pelo
algoritmo do cenário — não existe conceito de "ajuste manual" persistido em lugar
nenhum do modelo de dados. Para portar isso, precisa de:
- Uma tabela nova (ex. `cotacao_ajuste_manual`: `cotacao_id, cotacao_produto_id,
  fornecedor_id_override, criado_por, criado_em`, com `tenant_id`+RLS) guardando os
  overrides por cotação (não por cenário — um ajuste manual deveria sobreviver a troca
  de cenário, como no protótipo).
- `GET /mapa?cenario=` passaria a aplicar os overrides por cima da distribuição
  calculada antes de devolver a resposta.
- Um endpoint novo, ex. `PUT /cotacoes/{id}/mapa/ajustes` ou
  `POST .../itens/{cotacaoProdutoId}/mover` para gravar/remover um override.

**Enquanto isso não existe**: Mapa de Compra é 100% somente-leitura (como já era antes
desta passada de UI), só com os cenários calculados pelo backend.

## 3. Categorias finas de aviso em `buildSupplierReview` (marca/peso/múltiplas opções)

Protótipo classifica cada item de resposta de fornecedor em 7 categorias:
`missing_item`, `multiple_options`, `brand_changed`, `weight_changed`,
`package_price_suspected`, `low_confidence_match`, `extra_item`.

**Gap**: o backend só expõe o enum `StatusItem` com 5 valores (`OK`,
`DIVERGENCIA_PRECO`, `DIVERGENCIA_VOLUME`, `NAO_IDENTIFICADO`,
`PENDENTE_CONFIRMACAO`). Não existe:
- Detecção/flag de "marca trocada" (não há campo de marca solicitada vs. ofertada em
  `ItemRespostaResponse`).
- Sinalização de "múltiplas opções" quando o fornecedor manda mais de uma linha que
  daria match no mesmo produto — hoje o parser resolve isso implicitamente (pega um
  match), sem expor a ambiguidade pro frontend.
- Distinção entre "item extra não pedido" (`extra_item`) e "item não identificado"
  (`nao_identificado`) — hoje os dois caem em `NAO_IDENTIFICADO`.

**O que foi construído em vez disso**: a revisão por fornecedor foi implementada
agrupando visualmente os 5 status reais em 3 baldes (ok / atenção / revisão), sem
inventar as categorias mais finas. Se o negócio validar que essa granularidade faz
diferença real de operação, os 3 pontos acima viram trabalho de backend (parser +
DTO) antes de a UI poder mostrá-los de verdade.

## 4. `POST /avisos/{cpfId}/resolver` retorna a entidade JPA crua, não um DTO

Não é uma feature faltando, é uma correção de contrato pendente: o endpoint devolve
`CotacaoProdutoFornecedor` (com `versao`, nomes de campo internos) em vez de um
`AvisoResponse` dedicado. A UI atual consome só os campos que precisa e ignora o
resto, mas isso é frágil — qualquer refactor de entidade quebra o frontend em
silêncio. Recomendação: criar um DTO de resposta próprio antes de expandir esse fluxo
(passa por `db-schema-guardian` se mexer em entidade).

## 5. `cotacaoProdutoId` não confiável para itens `NAO_IDENTIFICADO`

Bug conhecido, documentado no próprio código do backend
(`FornecedorRespostaService`, linhas 106-116): quando um item não dá match em nenhum
produto, o FK cai no primeiro item da cotação por constraint de not-null. A UI evita
depender desse campo para itens nesse status; qualquer feature futura que precise
"qual produto exatamente é esse item não identificado" precisa antes de o backend
permitir FK nula ali (ou modelar de outra forma).
