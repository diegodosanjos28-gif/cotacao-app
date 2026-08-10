# Fluxograma 04 — Parsing da Lista de Produtos e Matching com o Catálogo

## Parser da lista colada pelo comprador (`ParserListaProdutosService`)

```mermaid
flowchart TD
    A["Texto colado — 1 linha por item"] --> B["Split por linha, trim, ignora linhas vazias"]
    B --> C{"Linha é só um número (ex: '42')?"}
    C -->|"Sim"| D["Descartada por completo — não vira item, nem 'não identificado'"]
    C -->|"Não"| E{"Bate com: qtd + unidade reconhecida + nome?<br/>(un, und, fd, cx, pct, kg, l, lt, dz)"}
    E -->|"Sim"| F["quantidade, unidade e nome extraídos corretamente. parseOk=true"]
    E -->|"Não"| G{"Bate com: número + espaço + texto (unidade não reconhecida)?"}
    G -->|"Sim (ex: '3 caixas de leite')"| H["quantidade FORÇADA para 1 (o '3' é descartado!) unidade='un', nome = LINHA INTEIRA. parseOk=false"]
    G -->|"Não (linha não começa com número)"| I["quantidade=1, unidade='un', nome = LINHA INTEIRA. parseOk=false"]
    F --> J["CotacaoProduto criado, ordem sequencial preservada"]
    H --> J
    I --> J
    J --> K{"parseOk == true?"}
    K -->|"Não"| L["Nunca tenta matching — fica sem produtoId"]
    K -->|"Sim"| M["Tenta matching contra o catálogo (MatchingProdutoService)"]
```

## Matching por similaridade (`MatchingProdutoService.calcSimilaridade`)

```mermaid
flowchart TD
    A["normTxt: remove acentos (NFD), minúsculo, só [a-z0-9 ], colapsa espaços"] --> B["Extrai peso/volume — só a PRIMEIRA ocorrência da string (ex: '500ml' em '... 500ml + 1kg' ignora o '1kg')"]
    B --> C{"Ambos os textos têm peso/volume, MESMA unidade, valores DIFERENTES?<br/>(ex: 60g vs 500g)"}
    C -->|"Sim"| D["Score forçado = 0.0 — tratado como produto diferente, mesmo com alta sobreposição textual"]
    C -->|"Não"| E["Jaccard = |interseção de tokens| / MAX(tokensA, tokensB)<br/>(não é Jaccard padrão — mais tolerante que união)"]
    E --> F{"Peso/volume igual em unidade E valor?"}
    F -->|"Sim"| G["+0.2 de bônus, capado em 1.0"]
    F -->|"Não"| H["Score final = Jaccard"]
    G --> H
    H --> I{"score >= 0.6 (SCORE_MINIMO_MATCH)?"}
    I -->|"Sim"| J["matched = true — vincula ao produto do catálogo com o MAIOR score (empate: primeiro da lista)"]
    I -->|"Não"| K["matched = false — produtoId fica nulo"]
```

Se nenhum item do catálogo bater, `MatchingProdutoService` cria uma entrada nova em
`Produto` a partir do nome — o catálogo cresce organicamente; não existe
`POST /produtos` para criação manual direta.
