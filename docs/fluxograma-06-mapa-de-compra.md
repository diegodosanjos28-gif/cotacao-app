# Fluxograma 06 — Mapa de Compra (3 Cenários)

## Preparação comum aos 3 cenários

```mermaid
flowchart TD
    A["Para cada item da cotação"] --> B["Filtra ofertas válidas: status=OK, semEstoque=false, fornecedor existe, preço não nulo"]
    B --> C{"Cenário selecionado?"}
    C -->|"MENOR_PRECO"| D["Ver fluxo Menor Preço"]
    C -->|"MELHOR_PRAZO"| E["Ver fluxo Melhor Prazo"]
    C -->|"EQUILIBRADA"| F["Ver fluxo Compra Equilibrada"]
    D --> G["Monta distribuição por fornecedor"]
    E --> G
    F --> G
    G --> H["totalGeral = soma dos subtotais atribuídos"]
    H --> I["totalPior = soma, por item, do MAIOR preço unitário entre todas as ofertas válidas (não é resultado de rodar outro cenário)"]
    I --> J["economiaComparadaAoPior = totalPior - totalGeral"]
    J --> K["Itens sem nenhuma oferta válida -> produtosSemFornecedor (não entram nem em totalGeral nem em totalPior)"]
```

## Cenário: Menor Preço

```mermaid
flowchart LR
    A["Por item"] --> B["Escolhe a oferta de MENOR preço unitário"]
    B --> C{"Empate?"}
    C -->|"Sim"| D["Desempate por fornecedorId (ordem alfabética do UUID — arbitrário, mas determinístico)"]
    C -->|"Não"| E["Fornecedor escolhido"]
    D --> E
    E --> F["Pedido mínimo por fornecedor é IGNORADO neste cenário — só aparece como aviso informativo depois, sem correção"]
```

## Cenário: Melhor Prazo

```mermaid
flowchart LR
    A["Por item"] --> B["Escolhe a oferta do fornecedor com MENOR prazo de entrega parseado"]
    B --> C{"Fornecedor sem prazo parseável (texto ambíguo ou vazio)?"}
    C -->|"Sim"| D["Nunca excluído — mas sempre ordenado por ÚLTIMO (nulls last)"]
    C -->|"Não"| E["Ordenado normalmente pelo nº de dias"]
    D --> F{"Empate de prazo?"}
    E --> F
    F -->|"Sim"| G["Desempate por preço, depois por fornecedorId"]
```

## Cenário: Compra Equilibrada (heurística gulosa — não é ótimo global)

```mermaid
flowchart TD
    A["Baseline = a mesma distribuição do cenário Menor Preço"] --> B["Remove todas as ofertas de fornecedores com status=PENDENTE_DADOS<br/>(não têm pedido mínimo conhecido)"]
    B --> C["Itera até (nº de fornecedores + 1) vezes"]
    C --> D{"Existe fornecedor com pedido mínimo configurado (>0) e total atual < mínimo?"}
    D -->|"Não"| E["Loop encerra — distribuição estável, resposta é montada"]
    D -->|"Sim — pega o PRIMEIRO encontrado nessa ordem"| F["Tenta 'resgatar': move para ele, um item por vez, os itens que ele também oferece e que hoje estão com outro fornecedor — do swap mais vantajoso (mais barato) pro menos vantajoso — até atingir o mínimo"]
    F --> G{"Atingiu o mínimo?"}
    G -->|"Sim"| C
    G -->|"Não, mesmo após mover todos os itens elegíveis"| H["Remove esse fornecedor da rodada por completo: todos os itens dele voltam para a próxima melhor oferta (ou ficam sem fornecedor, se não houver alternativa)"]
    H --> C
    C -->|"Esgotou o limite de iterações sem estabilizar"| E
```

Se o limite de iterações (`fornecedores.size() + 1`) for esgotado sem o algoritmo
estabilizar, a distribuição parcial daquele momento é retornada como final — sem
nenhum sinal explícito de "não convergiu" na resposta.

## Fluxo de finalização a partir do Mapa

```mermaid
flowchart TD
    A["Cenário ativo selecionado na tela"] --> B["Clique em 'Concluir Ordem de Compra'"]
    B --> C{"Algum fornecedor da distribuição está abaixo do próprio pedido mínimo?"}
    C -->|"Sim"| D["Checkbox obrigatório: 'Estou ciente que X ficará abaixo do pedido mínimo' — botão de confirmar fica desabilitado até marcar"]
    C -->|"Não"| E["Botão de confirmar já habilitado"]
    D --> F["Confirma"]
    E --> F
    F --> G["POST /cotacoes/{id}/finalizar"]
    G --> H["Status -> FINALIZADA, cenarioSelecionado gravado"]
    H --> I["Página re-renderiza reativamente (sem navegação de rota) para a visão somente-leitura"]
```
