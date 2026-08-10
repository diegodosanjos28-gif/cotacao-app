# QA — Sistema de Cotação PRX

Esta pasta reúne toda a documentação de QA manual/exploratório do sistema.

## Índice

- [`QA-CENARIOS-DE-TESTE.md`](QA-CENARIOS-DE-TESTE.md) — plano de QA geral: cenários
  ponta a ponta por módulo (Auth, Dashboard, Entrada de Dados, Comparativo, Mapa de
  Compra, Alertas, Multi-tenant/Segurança, Erros, Acessibilidade), com passos e
  resultado esperado. Referência primária para saber **o que** testar em cada tela.
- [`05-cenarios-sequenciais-completos.md`](05-cenarios-sequenciais-completos.md) —
  **comece por aqui para testar de ponta a ponta.** 22 cotações completas prontas
  pra rodar: lista de produtos + respostas de 1 a 4 fornecedores em sequência, cada
  cenário testando uma combinação diferente (alta variação, cobertura parcial,
  pedido mínimo, sem estoque, marca/gramagem/embalagem divergente, prazo de
  entrega, finalização). Os documentos 01-04 abaixo são o banco de referência
  granular por trás desses cenários, não o ponto de partida.
- [`06-cenario-3-real-massa.md`](06-cenario-3-real-massa.md) — **massa de campo**: a
  lista base real de 68 linhas + as respostas reais de 6 fornecedores do teste de
  aceitação de 2026-07-28. Diferente dos cenários 01–05 (derivados do código), esta massa
  veio do cliente e revelou o que a suíte sintética não pegava: 5 dos 6 fornecedores não
  escrevem `R$`. Use como regressão obrigatória ao mexer em parsing de preço. Atenção: o
  arquivo está **parcialmente preenchido** — as linhas confirmadas estão lá, a massa
  integral ainda precisa ser colada.
- [`01-lista-de-produtos-inputs.md`](01-lista-de-produtos-inputs.md) — banco de
  cenários de **input de dados prontos para colar** no campo "Lista de produtos" da
  Entrada de Dados (`ParserListaProdutosService`): formatos de unidade, quantidade,
  casos de borda e pegadinhas do parser.
- [`02-resposta-fornecedor-inputs.md`](02-resposta-fornecedor-inputs.md) — o mesmo,
  para o campo "Colar resposta do fornecedor" (`ParserRespostaFornecedorService`):
  formatos de preço, sem estoque, "consultar", linhas com múltiplos produtos, linhas
  a ignorar (saudação/despedida).
- [`03-conferencia-classificacao-inputs.md`](03-conferencia-classificacao-inputs.md) —
  cenários **pareados** (lista base + resposta do fornecedor) para reproduzir
  deliberadamente cada um dos 10 motivos de Atenção/Revisar da Conferência
  (`ClassificacaoConferenciaService`) — marca diferente, gramagem diferente, múltiplas
  opções, item extra, confiança baixa, etc.
- [`04-outros-inputs-texto-livre.md`](04-outros-inputs-texto-livre.md) — campos de
  texto livre menores fora do fluxo de Entrada de Dados: prazo de entrega do
  fornecedor (`PrazoEntregaParser`) e pedido mínimo.

## Como usar os documentos 01–04

Cada cenário segue o formato:

> **ID — Título**
> **Descrição:** o que o cenário exercita e por quê.
> **Cole isto:** bloco de texto literal, pronto para copiar e colar no campo indicado.
> **Resultado esperado:** o que deve acontecer, com o valor/comportamento exato
> derivado do código-fonte do parser/matcher na data em que o cenário foi escrito —
> não uma suposição.

Os blocos de "Cole isto" são **literais** — copie exatamente como está, inclusive
maiúsculas/minúsculas e acentos, a menos que o próprio cenário instrua a variar algo.
Cenários pareados (documento 03) exigem colar o bloco "Lista base" primeiro (cria a
cotação/os itens), depois adicionar um fornecedor e colar o bloco "Resposta" no bloco
desse fornecedor.

## Precisão e validade

Os cenários dos documentos 01–03 foram derivados lendo o código-fonte de
`ParserListaProdutosService`, `ParserRespostaFornecedorService`,
`MatchingProdutoService`, `ConciliacaoRespostaService` e
`ClassificacaoConferenciaService` (`backend/src/main/java/com/prx/cotacao/cotacao/`) e
simulando manualmente cada regex/score contra o texto exato do bloco — não são
suposições de comportamento "razoável". Ainda assim, **são derivação estática, não
execução real**: se o comportamento observado divergir do documentado, o código-fonte
citado em cada cenário é a fonte de verdade — atualize o cenário, não o contrário.
Qualquer mudança nesses arquivos de parser invalida os cenários afetados; ao alterar
um desses services, revalide os documentos 01–03 na mesma leva de commits (mesma regra
já aplicada a `docs/fluxograma-*.md` e `QA-CENARIOS-DE-TESTE.md`).

Alguns cenários (marcados 🔍 **achado a confirmar**) documentam um comportamento que a
leitura do código sugere fortemente, mas que ainda não foi confirmado rodando o
sistema de verdade — tratar como hipótese a validar, não como bug já reportável sem
checar.
