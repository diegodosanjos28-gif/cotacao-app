# Fluxograma 03 — Ciclo de Vida da Cotação

> Atualizado em 2026-07-17 após o refactor de fluxo sequencial + Conferência (ver
> `docs/PLAN REFACTOR.md` e Fluxograma 05).

## Máquina de estados (`CotacaoStatus`)

```mermaid
flowchart LR
    Start(["POST /cotacoes"]) --> RASCUNHO["RASCUNHO"]
    RASCUNHO -->|"POST .../lista"| EM_ANDAMENTO["EM_ANDAMENTO"]
    EM_ANDAMENTO -->|"POST .../lista (de novo — sem guarda de idempotência, itens só se acumulam)"| EM_ANDAMENTO
    EM_ANDAMENTO -->|"POST .../fornecedores/{id}/resposta (preview, não persiste)"| EM_ANDAMENTO
    EM_ANDAMENTO -->|"POST .../fornecedores/{id}/confirmar (persiste de fato)"| EM_ANDAMENTO
    RASCUNHO -->|"POST .../finalizar (SEM nenhum item — permitido, sem guarda mínima)"| FINALIZADA["FINALIZADA"]
    EM_ANDAMENTO -->|"POST .../finalizar"| FINALIZADA
    FINALIZADA -->|"POST .../lista"| E1["409 'Cotação finalizada não aceita novos itens'"]
    FINALIZADA -->|"POST .../resposta ou .../confirmar"| E2["409 'Cotação finalizada não aceita novas respostas'"]
    FINALIZADA -->|"POST .../finalizar de novo"| E3["409 'Cotação já finalizada'"]
    CANCELADA["CANCELADA<br/>(existe só no enum — nenhum código ou endpoint jamais atribui este status)"]
    style CANCELADA fill:#eee,stroke:#999,stroke-dasharray: 5 5,color:#999
```

## Fluxo funcional ponta a ponta (o que o operador faz na tela)

```mermaid
flowchart TD
    A["Dashboard: título + 'Nova cotação'"] --> B["Cotação criada — RASCUNHO, canalOrigem=WEB"]
    B --> C["Entrada de Dados"]
    C --> C1["Colar lista de produtos"]
    C1 --> D["Status -> EM_ANDAMENTO"]
    C --> C2["'+ Adicionar Fornecedor' (autocomplete ou cadastro novo) — só libera o próximo depois que o atual estiver CONFIRMADO"]
    C2 --> C3["Colar resposta do fornecedor atual, 'Processar Cotação'"]
    C3 --> C4["Conferência abre: contadores OK/Atenção/Revisar"]
    C4 --> E{"Algum item REVISAR sem resolução?"}
    E -->|"Sim"| F["Resolver: aceitar/selecionar candidato, editar manualmente, ou marcar sem oferta"]
    F --> E
    E -->|"Não"| G["'Confirmar e Processar' — persiste, cotacao_fornecedor -> CONFIRMADO"]
    G --> C2
    G --> H["Comparativo: Tabela / Por Produto / Fornecedores"]
    G --> I["Alertas: críticos / atenção / oportunidades (derivado, sem endpoint próprio)"]
    H --> J["Mapa de Compra: Menor Preço / Compra Equilibrada / Melhor Prazo"]
    I --> J
    J --> K["Copiar pedido pronto (WhatsApp) por fornecedor"]
    J --> L["Concluir Ordem de Compra"]
    L --> M["Status -> FINALIZADA, cenarioSelecionado gravado"]
    M --> N["Mapa vira somente-leitura (OrdemFinalizada) — sem troca de cenário, sem copiar pedido"]
```

Fornecedores dentro de uma cotação têm seu próprio estado sequencial
(`cotacao_fornecedor.status`): `PENDENTE` (adicionado, sem resposta processada) →
`PROCESSADO` (preview gerado, ainda não confirmado — reprocessar um fornecedor já
`CONFIRMADO` volta pra `PROCESSADO`) → `CONFIRMADO` (persistido). "+ Adicionar
Fornecedor" só habilita quando o último da lista está `CONFIRMADO` (gate
server-side em `CotacaoFornecedorService.adicionar`, 409 se violado).

## Itens dentro de uma cotação — status de cada oferta (`StatusItem`)

```mermaid
flowchart LR
    OK["OK<br/>(único valor gravado por /confirmar — sempre, mesmo para itens que passaram por Atenção/Revisar no preview)"]
    NI["NAO_IDENTIFICADO<br/>(reservado no enum; ver observação abaixo)"]
    style NI fill:#eee,stroke:#999,stroke-dasharray: 5 5,color:#999
```

`DIVERGENCIA_PRECO`/`DIVERGENCIA_VOLUME` foram **removidos** do enum e da CHECK
constraint no refactor de 2026-07-16/17 (antes só "nunca atribuídos", agora não
existem mais). `PENDENTE_CONFIRMACAO` também saiu do enum — a suspeita de preço de
caixa/fardo virou o motivo `PACKAGE_PRICE_SUSPECTED` do preview (nunca um
`StatusItem` persistido). `NAO_IDENTIFICADO` permanece no enum mas
`ConfirmacaoRespostaService` sempre grava `OK`; as severidades ATENCAO/REVISAR e
seus motivos (`MotivoConferencia`) só existem no `PreviewRespostaResponse` — o que
sobrevive delas após confirmar é a lista de motivos em CSV no campo
`tipo_embalagem_detectado`, metadado informativo. Ver Fluxograma 05 para o pipeline
completo de classificação.

Todo item persistido entra no Mapa de Compra normalmente (status sempre `OK`); só
`semEstoque=true` exclui uma oferta do cálculo.
