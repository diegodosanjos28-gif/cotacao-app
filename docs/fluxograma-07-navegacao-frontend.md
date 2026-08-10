# Fluxograma 07 — Navegação do Frontend

## Mapa de rotas

```mermaid
flowchart TD
    L["/login (única rota pública)"] -->|"login ok"| D["/ — Dashboard"]
    D -->|"'Nova cotação' (título obrigatório)"| E["/cotacoes/[id]/entrada"]
    D -->|"clique em cotação existente na tabela"| E
    E <--> C["/cotacoes/[id]/comparativo"]
    E <--> M["/cotacoes/[id]/mapa"]
    E <--> A["/cotacoes/[id]/alertas"]
    C <--> M
    C <--> A
    M <--> A
    subgraph NavBar["NavBar (só aparece dentro de /cotacoes/[id]/...)"]
        N1["Entrada de Dados"]
        N2["Comparativo"]
        N3["Mapa de Compra"]
        N4["Alertas"]
        N5["Sair -> logout()"]
    end
```

Não existe painel administrativo — nenhuma rota `/admin/*` no frontend.

## Guarda de autenticação em cada rota

```mermaid
flowchart TD
    A["Qualquer rota exceto /login monta"] --> B["AuthGuard verifica token no localStorage"]
    B --> C{"Token presente?"}
    C -->|"Não"| D["redirect para /login (tela em branco por um instante, sem spinner)"]
    C -->|"Sim"| E["Conteúdo da página renderiza e dispara seus fetches"]
```

## Dentro da Entrada de Dados — composição de componentes

> Reescrito em 2026-07-17 — a antiga `FornecedoresSidebar` com abas simultâneas e
> `FornecedorRespostaBlock` com resolução de aviso inline deram lugar ao fluxo
> sequencial com gate + `ConferenciaModal` (ver Fluxograma 05).

```mermaid
flowchart TD
    A["page.tsx (orquestrador fino)"] --> B["ResumoCotacao — KPIs derivados do /comparativo"]
    A --> C["ListaProdutosSection — colar lista de produtos"]
    A --> D["GuiaFormatacao — ajuda estática, sem lógica"]
    A --> F["FornecedoresCotacoesSection — 1 fornecedor por vez, navegação < >"]
    F --> F1["Slot vazio: FornecedorAutocomplete (busca em todosFornecedores) + '+ Cadastrar novo fornecedor' (abre FornecedorFormModal)"]
    F --> G["FornecedorRespostaBlock (fornecedor atual da cotação)"]
    G --> H["Colar resposta, 'Processar Cotação' -> gerarPreview"]
    H --> K["ConferenciaModal — contadores + tabela Item Base/Resposta/Preço/Status"]
    K --> K1["Linha REVISAR: radios de candidato, 'Editar manualmente', 'Sem oferta deste fornecedor'"]
    K --> K2["'Confirmar e Processar' (desabilitado com REVISAR pendente) -> confirmarResposta -> router.push comparativo"]
    G --> I["'Editar dados' do fornecedor (reabre FornecedorFormModal)"]
    F1 -.->|"'+ Adicionar Fornecedor' só habilita com o último CONFIRMADO"| F
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
