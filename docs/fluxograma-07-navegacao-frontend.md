# Fluxograma 07 — Navegação do Frontend

## Mapa de rotas

> Atualizado em 2026-08-20 (refactor Entrada de Dados, leva final) — a rota separada
> `/cotacoes/[id]/entrada` **deixou de existir**. A antiga tab fixa "Entrada de Dados"
> do NavBar (que ou navegava pra lá ou abria um modal de criação, dependendo de haver
> cotação ativa) virou uma landing tenant-wide própria, `/entrada`, e o
> `AprovacaoModal` (produto + fornecedores + conferência) passou a ser hospedado
> **direto nela** — a pedido explícito do usuário ("a tela de fundo do modal deve ser
> a tela Entrada de Dados Cotação atual"). "Revisar e aprovar"/"Detalhes" não navegam
> mais pra lugar nenhum: buscam os dados completos da cotação sob demanda e abrem o
> modal por cima da própria landing, que continua visível atrás dele. O botão fixo
> "+ Nova Cotação" também foi removido — criar cotação passou a ser só pelo CTA do
> card vazio de `/entrada`.

```mermaid
flowchart TD
    L["/login (única rota pública)"] -->|"login ok"| D["/ — Dashboard"]
    D -->|"clique em cotação existente na tabela"| C["/cotacoes/[id]/comparativo"]
    NAV["NavBar — tab 'Entrada de Dados'"] --> ED["/entrada — landing tenant-wide"]
    ED -->|"'Criar cotação pela web' (card vazio)"| C
    ED -->|"'Revisar e aprovar' / 'Detalhes' (card da cotação atual) — abre o AprovacaoModal SEM navegar"| ED
    ED -->|"'Reabrir' (mini card de cotação anterior, FINALIZADA)"| C
    C <--> M["/cotacoes/[id]/mapa"]
    C <--> A["/cotacoes/[id]/alertas"]
    M <--> A
    subgraph NavBar2["NavBar (dentro de /cotacoes/[id]/...)"]
        N1["Entrada de Dados -> sempre /entrada"]
        N2["Comparativo"]
        N3["Mapa de Compra"]
        N4["Alertas"]
        N5["Sair -> logout()"]
    end
```

`ADMIN_PRX` tem um painel próprio sob `/admin/*` (`/admin/tenants`, `/admin/tenants/[id]`,
`/admin/administradores`) — fora do escopo deste fluxograma, focado no fluxo de negócio
do tenant (`OPERADOR_CLIENTE`).

## Guarda de autenticação em cada rota

```mermaid
flowchart TD
    A["Qualquer rota exceto /login monta"] --> B["AuthGuard verifica token no localStorage"]
    B --> C{"Token presente?"}
    C -->|"Não"| D["redirect para /login (tela em branco por um instante, sem spinner)"]
    C -->|"Sim"| E["Conteúdo da página renderiza e dispara seus fetches"]
```

## Landing tenant-wide (`/entrada`) — composição de componentes

> Nova em 2026-08-20 (Fase B do refactor da Entrada de Dados). Substitui o antigo
> ponto de entrada fixo do NavBar. Na leva final do mesmo refactor (mesmo dia), passou
> também a hospedar o `AprovacaoModal` diretamente — ver seção seguinte.

```mermaid
flowchart TD
    A["entrada/page.tsx"] --> B["CotacaoAtualCard — card pulsando (RASCUNHO/EM_ANDAMENTO mais recente do tenant) ou estado vazio com CTA 'Criar cotação pela web'"]
    A --> C["CotacoesAnterioresCarrossel — paginação por cursor, só FINALIZADA; 'Fechar' é soft-hide client-side (localStorage por tenant), não backend"]
    A --> D["HallFornecedores — modo 'ativa' (lê CotacaoAtualResponse.fornecedores) ou modo 'histórico' (fetch GET /fornecedores/historico) quando não há cotação em andamento"]
```

## `AprovacaoModal`, hospedado na própria landing — composição de componentes

> Reescrito em 2026-08-20 (múltiplas levas do refactor da Entrada de Dados, a pedido
> explícito do usuário): "o fluxo agora deve ser centralizado no novo modal; a aba do
> fundo deve ser excluída" → "o fluxo Web deve usar o mesmo modal do WhatsApp, até
> mesmo na etapa de produto" → "a tela de fundo do modal deve ser a tela Entrada de
> Dados Cotação atual". O resultado final: **não existe mais a rota
> `/cotacoes/[id]/entrada`**. `CotacaoAtualCard` ("Revisar e aprovar"/"Detalhes") e o
> CTA "Criar cotação pela web" não navegam — buscam os dados completos da cotação
> (`buscarCotacao`+`listarFornecedores`+`listarFornecedoresDaCotacao`+`buscarLista`+
> catálogo) sob demanda e abrem o `AprovacaoModal` direto sobre a própria landing
> (`entrada/page.tsx`), que continua visível (card + carrossel + Hall) atrás dele.
> Fechar o modal refaz a busca de `buscarCotacaoAtual()` pra landing refletir qualquer
> mudança (itens, fornecedores, status). Web e WhatsApp usam exatamente o mesmo modal,
> inclusive na etapa de produto — a diferença entre os dois canais é só quais botões/
> campos de **captura** aparecem, nunca a estrutura de revisão.

```mermaid
flowchart TD
    A["entrada/page.tsx"] -->|"'Revisar e aprovar' / 'Detalhes' / 'Criar cotação pela web'"| F["Busca dados completos da cotação sob demanda"]
    F --> M["AprovacaoModal (abre sobre a própria landing)"]
    M -->|"onClose"| R["Refaz buscarCotacaoAtual() — landing reflete mudanças"]

    M --> S1["Aba 1 — Conferência da Lista Base"]
    S1 --> G["GridProdutosSection completo (autocomplete, paginação, filtro, ordenar por status)"]
    G -->|"canalOrigem == WEB"| G1["'+ Adicionar Produto' e 'Colar do WhatsApp' visíveis"]
    G -->|"canalOrigem == WHATSAPP"| G2["Só corrigir linha existente — sem botões de adicionar (a AI já populou a lista)"]
    S1 -->|"cotação WHATSAPP com listaRevisada=false"| AJ["Banner de aviso + rodapé troca para 'Concluir ajuste e seguir para aprovação' (concluirAjusteLista) — aba 2 fica desabilitada até concluir"]
    S1 -->|"'Lista base conferida' (ou concluir ajuste)"| S2

    M --> S2["Aba 2 — Conferência das Cotações"]
    S2 --> H["ConferenciaFornecedoresTab — dono de todo o ciclo de vida do fornecedor"]
    H -->|"0 fornecedores, canalOrigem == WEB"| H1["FornecedoresSidebar (catálogo, '+ Novo', selecionar e adicionar)"]
    H -->|"0 fornecedores, canalOrigem == WHATSAPP"| H2["Mensagem de espera — sem ponto de adição manual"]
    H -->|"fornecedor PENDENTE, canalOrigem == WEB"| H3["FornecedorCapturaCard — dados comerciais + colar resposta + 'Processar Resposta Cotação'"]
    H -->|"fornecedor PENDENTE, canalOrigem == WHATSAPP"| H4["Mensagem de espera (resposta chega sozinha pelo webhook)"]
    H -->|"fornecedor PROCESSADO"| K["ConferenciaPendente — contadores + candidatos por item REVISAR/ATENCAO/OK"]
    K -->|"'Confirmar e Processar' (desabilitado com REVISAR pendente)"| H
    H -->|"fornecedor CONFIRMADO"| L["ConferenciaConfirmadaReadOnly — somente leitura"]
    H -.->|"'+ Adicionar Fornecedor' só habilita com o último CONFIRMADO"| H1

    S2 -->|"todos os fornecedores CONFIRMADO"| Z["'Lançar para Comparativo e Mapa de Compra' -> finalizarCotacao -> router.push comparativo"]
```

## Estado da tela de Mapa de Compra conforme o status da cotação

```mermaid
flowchart TD
    A["/cotacoes/[id]/mapa monta"] --> B["Carrega os 3 cenários em paralelo (Promise.all)"]
    B --> C{"cotacao.status == FINALIZADA?"}
    C -->|"Sim"| D["Visão OrdemFinalizada: somente leitura, sem troca de cenário, sem copiar pedido, sem botão Concluir"]
    C -->|"Não"| E["Visão ativa: 3 cards de cenário clicáveis, troca instantânea (sem novo fetch), copiar pedido, botão Concluir Ordem de Compra"]
    E -->|"finaliza com sucesso"| C
```
