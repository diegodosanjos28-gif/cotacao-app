# Documentação — Sistema de Cotação PRX

## Documento principal

[`documentacao-tecnica-sistema-cotacao-prx.md`](documentacao-tecnica-sistema-cotacao-prx.md)
— arquitetura, modelo de dados, API e lógica de negócio, com status de aceite por
módulo (seção 0) contra o escopo contratado (v1 até 15/08/2026). Movido da raiz do
repo para cá em 21/07/2026.

## Fluxogramas (Mermaid)

Diagramas do comportamento **real e atual** do sistema (não do desenho original da
proposta) — gerados a partir de leitura direta do código-fonte em 2026-07-13.
Renderizam nativamente no GitHub/GitLab e no VS Code (extensão *Markdown Preview
Mermaid Support* ou similar).

| Arquivo | Conteúdo |
|---|---|
| [`fluxograma-00-arquitetura-geral.md`](fluxograma-00-arquitetura-geral.md) | Stack, pipeline de uma requisição, camadas de defesa multi-tenant (resumo) |
| [`fluxograma-01-autenticacao-sessao.md`](fluxograma-01-autenticacao-sessao.md) | Login, renovação automática de token (401→refresh→retry), replay detection, logout |
| [`fluxograma-02-multitenancy-rls.md`](fluxograma-02-multitenancy-rls.md) | Resolução de tenant, Hibernate Filter vs RLS, bypass de admin, `DbRoleGuard` |
| [`fluxograma-03-ciclo-vida-cotacao.md`](fluxograma-03-ciclo-vida-cotacao.md) | Máquina de estados de `CotacaoStatus`, fluxo funcional ponta a ponta, `StatusItem` |
| [`fluxograma-04-parsing-lista-produtos.md`](fluxograma-04-parsing-lista-produtos.md) | Parser da lista colada + matching por similaridade contra o catálogo |
| [`fluxograma-05-resposta-fornecedor-avisos.md`](fluxograma-05-resposta-fornecedor-avisos.md) | Parser da resposta do fornecedor, determinação de status, resolução de aviso de embalagem |
| [`fluxograma-06-mapa-de-compra.md`](fluxograma-06-mapa-de-compra.md) | Os 3 algoritmos (Menor Preço / Melhor Prazo / Compra Equilibrada) e o fluxo de finalização |
| [`fluxograma-07-navegacao-frontend.md`](fluxograma-07-navegacao-frontend.md) | Mapa de rotas, guarda de autenticação, composição de componentes da Entrada de Dados |

## O que **não** está nos fluxogramas (porque não existe no sistema hoje)

Módulo WhatsApp, painel administrativo, Histórico de Preços/Economia, ajuste manual
no Mapa de Compra. Ver `qa/QA-CENARIOS-DE-TESTE.md` seção 11 para a lista completa de
gaps conhecidos e `TODO-PROTOTYPE.md` (raiz do repo) para o detalhe de cada um.

## Outros documentos nesta pasta

- [`qa/`](qa/README.md) — pasta de QA: plano de cenários manuais/exploratórios
  (`qa/QA-CENARIOS-DE-TESTE.md`) e bancos de input de dados prontos para copiar e
  colar, cobrindo lista de produtos, resposta de fornecedor e classificação da
  Conferência.

## Mantendo os fluxogramas atualizados

Estes diagramas descrevem o comportamento observado no código em 2026-07-13. Ao
alterar uma regra de negócio (parser, matching, algoritmo do Mapa, máquina de estados
da cotação, pipeline de auth/RLS), atualize o `.md` correspondente no mesmo commit —
um fluxograma desatualizado é pior do que nenhum, porque engana quem confia nele.
