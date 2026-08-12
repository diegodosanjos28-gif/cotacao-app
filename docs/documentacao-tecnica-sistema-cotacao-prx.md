# Documentação Técnica — Sistema de Cotação PRX

**Versão:** 2.3
**Data:** 12/08/2026 (Prompt 18 — confirmação WhatsApp trocou de texto livre para
Message Templates da Meta cadastrados por tenant, ver 10.7/10.8; correção de
imprecisões pré-existentes encontradas durante esse trabalho, não introduzidas por
ele: o Painel Admin PRX estava registrado como "não iniciado" nas seções 0/5/13 desde
antes do Prompt 12, quando na verdade já está em produção há dias — corrigido nesta
revisão; e o estado real do deploy em produção, seção 9, também estava defasado —
Caddy/HTTPS e containerização do frontend já existem no repo, CI/CD continua não
existindo)
v2.2 em 10/08/2026: sincronização com o código do módulo WhatsApp (seção 10),
implementado e funcional desde 09/08/2026 — o corpo da seção 10 já estava atualizado
prompt a prompt e não mudou de conteúdo, só os marcadores de status. v2.1 em
24/07/2026: catálogo de marcas por tenant, divergência comparativa por mediana no
Comparativo, decisões "Adicionar à lista"/"Associar a outro item" para item extra,
sinônimos de unidade na lista base — ver 3.2, 5, 6.4, 6.6, 6.8. v2.0 em 21/07/2026 já
refletia o estado real do código; v1.0 em 02/07/2026 era o plano pré-construção)
**Autor:** PRX (Nicolas)
**Status:** Em implementação — v1 majoritariamente construído (web + WhatsApp + Admin PRX prontos), prazo contratual 15/08/2026. Ver seção 0 para status de aceite por módulo.

---

## 0. Status de Aceite (21/07/2026, tabela corrigida em 12/08 — ver nota abaixo)

Snapshot do que está de fato construído e testado hoje, contra o escopo contratado da
proposta comercial (v1 completo até 15/08/2026, incluindo o módulo WhatsApp). Para o
plano operacional dia-a-dia (prompts, gates, matriz de subagents), ver
`PLANO-DE-IMPLEMENTACAO.md`; para o roadmap completo, seção 13.

| Módulo | Status | Observação |
|---|---|---|
| Auth JWT + multi-tenancy (RLS) | ✅ Pronto | Access 15min + refresh 7d com rotação/replay detection; RLS dual-layer (Hibernate `@Filter` + policy Postgres); testes de isolamento cross-tenant passando (`MultiTenantIsolationTest`, `EmbalagemSnapshotIsolationTest`) |
| Painel Admin PRX (`/admin/tenants`) | ✅ Pronto (em produção) | **Correção 12/08: esta linha estava desatualizada desde antes do Prompt 12** — o painel já existe e está acessível em produção, não é mais um path reservado sem controller. CRUD de tenants, CRUD de administradores (`ADMIN_PRX`, sem tenant), e dentro do detalhe de cada tenant: CRUD de usuários (`OPERADOR_CLIENTE`) e, desde o Prompt 18, aba de Templates de Mensagem WhatsApp (ver 10.7/10.8). Bootstrap do primeiro `ADMIN_PRX` via env vars (`ADMIN_BOOTSTRAP_EMAIL`/`ADMIN_BOOTSTRAP_PASSWORD`). Gate único `.requestMatchers("/admin/**").hasRole("ADMIN_PRX")` em `SecurityConfig` |
| Cadastro de fornecedores/produtos | ✅ Pronto | CRUD completo de fornecedores (soft-delete); produtos só GET/PUT via API — catálogo nasce do matching da lista (`CotacaoListaService`, corrigido 01/08: até então nada criava produto novo, catálogo nunca saía do zero, ver nota na seção 3.2) |
| Entrada de lista de produtos | ✅ Pronto | Parser + upsert idempotente (reenviar a lista não duplica linha) |
| Resposta de fornecedor + Conferência | ✅ Pronto | Fluxo **sequencial com gate**: um fornecedor por vez, resposta vira preview (não persiste), operador revisa no modal de Conferência (OK/Atenção/Revisar com motivo), só persiste em `/confirmar` — só então libera o próximo fornecedor. Reescrito por completo em 16/07 |
| Comparativo + Mapa de Compra (3 cenários) | ✅ Pronto | Menor Preço / Compra Equilibrada / Melhor Prazo, com ajuste manual de distribuição **fora de escopo** (ver `TODO-PROTOTYPE.md` item 2) |
| Finalização de cotação | ✅ Pronto | `POST /finalizar` |
| Frontend (Dashboard, Entrada, Comparativo, Mapa, Alertas) | ✅ Pronto | Fiel ao protótipo `"COTA&TESTA - 14.07 - V5.html"`; sem SSR, JWT em localStorage |
| Histórico de Preços (seção 11) | ✅ Pronto | Consulta derivada ao vivo (sem tabela nova) sobre `cotacao_produto_fornecedor`/`cotacao_produto`/`cotacao`/`produto` — ver nota de implementação em 11.4 |
| Economia (seção 11) | ❌ Não iniciado | Nenhuma tabela, nenhum endpoint — tela do protótipo fica fora do v1 até este módulo ser priorizado |
| **Módulo WhatsApp** (webhook, classificador, roteamento) | ✅ Pronto (09/08; confirmação por template desde 12/08) | Webhook Meta com validação de assinatura HMAC + idempotência por `message_id`, classificador por marcador explícito com tolerância a erro de digitação (Levenshtein), roteamento lista/resposta com janela de 48h, matching fuzzy de fornecedor com criação automática `PENDENTE_DADOS`/`WHATSAPP_AUTO`, e desde o Prompt 15 compartilha o MESMO núcleo de persistência do canal Web (`RespostaFornecedorCoreService` — sempre preview + Conferência, nunca auto-confirma). Desde o Prompt 18 (12/08), a confirmação de recebimento é enviada via Meta Message Template (antes era texto livre) — ver 10.7/10.8. Detalhe completo na seção 10 |
| CI/CD + deploy em produção (droplet DigitalOcean) | ⚠️ Parcial | **Correção 12/08:** `docker-compose.yml` já orquestra backend + frontend (com `Dockerfile` próprio) + Caddy com HTTPS automático (`Caddyfile` na raiz, API prefixada em `/api`) — mais avançado do que a v2.2 registrava. Ainda falta: GitHub Actions (nenhum `.github/workflows/`), stack de monitoramento (Netdata/Dozzle/Tailscale/Sentry) — ver seção 9 |
| Testes de integração com Testcontainers | ✅ Configurado e em uso | 23 arquivos de teste JUnit no backend, incluindo os 2 testes de isolamento RLS citados acima |
| Testes E2E frontend (Playwright) | ❌ Não iniciado | Dependência instalada, sem `playwright.config` nem specs — só 3 testes Vitest (unitários/componente) |

**Risco principal para o prazo de 15/08:** o módulo WhatsApp já está pronto; o deploy
em produção (droplet + Caddy + CI/CD + monitoramento, seção 9) é o bloco que ainda
falta para fechar o escopo contratado do v1. **Atualização 12/08:** Caddy/HTTPS e
containerização do frontend já estão implementados (ver correção na linha acima) —
o que falta de fato é CI/CD (GitHub Actions) e a stack de monitoramento, um escopo
menor do que a v2.2 desta doc registrava.

---

## 1. Visão Geral

### 1.1 Problema

Hoje o dono do mercado cota preços com fornecedores via WhatsApp, manualmente: manda a lista de produtos, recebe respostas em texto livre, e compara preços "na mão" (papel, planilha ou mentalmente). Não há histórico, não há comparação estruturada, e o processo não escala.

### 1.2 Objetivo do sistema

Digitalizar o fluxo **sem mudar a forma como o cliente já trabalha**. O cliente continua mandando lista de produtos e colando respostas de fornecedores — só que agora o sistema estrutura isso, compara preços automaticamente, sinaliza inconsistências (preço de caixa informado como se fosse unidade, divergência de peso/volume entre o que foi pedido e o que foi ofertado) e gera o pedido final pronto para colar de volta no WhatsApp do fornecedor.

O módulo WhatsApp (ver seção 10) cobre a parte mais repetitiva do fluxo — a entrada de dados — diretamente pelo canal que o cliente já usa, sem exigir abrir o navegador para essa etapa. É parte do escopo do v1, não uma fase futura.

### 1.3 Princípios de design (herdados do protótipo e da experiência com o projeto Zanon)

- **Não superengenharia.** Stack simples, hospedagem gerenciada, sem infraestrutura própria de containers/Kubernetes.
- **Simplicidade para o cliente final** acima de tudo — o comprador não deve precisar aprender um sistema novo, só continuar mandando texto.
- **Extensibilidade pensada, não construída antes da hora.** O módulo de "fornecedores parceiros" (catálogo direto de produtos por fornecedor) é preparado no modelo de dados, mas não implementado agora.
- **Multi-tenant desde o primeiro dia** — mesmo que hoje só exista um tenant real (Mercado Zanon), a arquitetura já nasce pronta para vender a outros mercados.

---

## 2. Arquitetura

```
┌─────────────────┐        HTTPS/JSON        ┌──────────────────────┐
│   Next.js (SPA)  │ ───────────────────────▶ │   Spring Boot API     │
│   (mesmo droplet) │ ◀─────────────────────── │   (mesmo droplet)     │
└─────────────────┘                           └──────────┬───────────┘
        ▲                                                 │ JDBC
        │ Caddy (proxy reverso, HTTPS)        ┌───────────▼───────────┐
        └──────────────────────────────────── │  PostgreSQL gerenciado │
                                                │  (instância separada) │
                                                └───────────────────────┘

Fase 2 (não iniciada):
┌──────────────┐   webhook   ┌──────────────────────┐
│  Meta Cloud   │────────────▶│ Spring Boot API        │
│  WhatsApp API │◀────────────│ (mesmo backend)        │
└──────────────┘   resposta   └──────────────────────┘
```

> **Mudança em relação ao plano original (v1.0, 02/07):** a hospedagem deixou de ser
> Railway/Render/Vercel e passou a ser um **droplet único na DigitalOcean** com Docker
> Compose + Caddy, banco gerenciado em instância separada (decisão registrada no
> `CLAUDE.md`). Hoje só o backend está containerizado (`docker-compose.yml` +
> `backend/Dockerfile`, uso local/dev) — frontend containerizado, Caddy e o restante do
> deploy de produção ainda não existem no repo (ver seção 9).

### 2.1 Stack

| Camada | Tecnologia | Justificativa |
|---|---|---|
| Frontend | Next.js (React) + TypeScript, 100% client-side (sem SSR de dado autenticado) | Já é a stack que você domina; JWT em localStorage, sem cookie de sessão |
| Backend | Spring Boot 3 + Java 21 | Você já usa; ecossistema maduro para JWT, JPA, validação, testes |
| Banco | PostgreSQL (multi-tenant via RLS) | Mesma abordagem usada no projeto Zanon — já validada por você |
| Auth | JWT (access + refresh, com rotação e detecção de replay) | Stateless, simples de escalar |
| Hospedagem backend + frontend | Droplet único DigitalOcean, Docker Compose | Custo fixo previsível, sem depender de free tier de terceiros; Netdata/Dozzle só via Tailscale, nunca no firewall público |
| Banco gerenciado | Instância separada (fora do droplet de aplicação) | Backups automáticos, isolado do ciclo de deploy da aplicação |
| Proxy reverso | Caddy (HTTPS automático) | Planejado, ainda não commitado no repo |
| CI/CD | GitHub Actions | Planejado, ainda não commitado no repo |
| Observabilidade | Spring Boot Actuator + logs estruturados (JSON); Netdata/Dozzle via Tailscale + Sentry planejados | Lean; nada disso está implementado ainda (ver seção 8) |

### 2.2 Multi-tenancy: schema único + RLS (não schema-per-tenant)

Decisão: **um único schema Postgres, com `tenant_id` em toda tabela sensível, isolado via Row-Level Security (RLS)**, igual ao que já foi validado no Gestão de Caixa da Zanon.

Alternativa descartada: schema-per- tenant (um schema Postgres por cliente). Motivo: complica migrations (rodar N vezes por deploy), complica connection pooling, e não traz benefício real para o volume esperado (dezenas de mercados, não milhares). RLS com `tenant_id` é mais simples de operar e mais barato.

Cada requisição autenticada carrega o `tenant_id` no JWT; a aplicação define isso como variável de sessão Postgres (`SET app.current_tenant_id`) e as políticas RLS filtram automaticamente. **Isso evita a classe de bug mais perigosa em sistemas multi-tenant: esquecer um `WHERE tenant_id = ?` em alguma query.**

---

## 3. Modelo de Dados

### 3.1 Visão geral das entidades

```
Tenant (Mercado/Cliente da PRX)
  └── Usuario (admin PRX ou operador do cliente)
  └── Fornecedor (cadastro persistente, reusado entre cotações)
  └── Produto (catálogo interno do tenant)
  └── Cotacao
        └── CotacaoProduto (linha da lista pedida)
              └── CotacaoProdutoFornecedor (preço ofertado por 1 fornecedor para 1 item)
        └── CotacaoFornecedor (estado do gate sequencial: 1 fornecedor por vez na cotação)
  └── FornecedorProduto (Fase futura — catálogo direto de parceiros)
```

### 3.2 Detalhamento das entidades

#### `tenant`
Representa um cliente da PRX (um mercado). Visível apenas ao painel admin.

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| nome_fantasia | varchar | ex: "Mercado Zanon" |
| razao_social | varchar | opcional |
| cnpj | varchar | único, opcional no MVP |
| status | enum(ATIVO, SUSPENSO, TRIAL) | |
| plano | enum | preparo para cobrança futura, não usado ainda |
| criado_em | timestamptz | |

#### `usuario`
| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK nullable | **nulo para admin PRX** (admin não pertence a um tenant) |
| email | varchar | único |
| senha_hash | varchar | bcrypt |
| papel | enum(ADMIN_PRX, OPERADOR_CLIENTE) | |
| ativo | boolean | |
| criado_em | timestamptz | |

> Nota de segurança: `ADMIN_PRX` enxerga todos os tenants (painel administrativo); `OPERADOR_CLIENTE` só enxerga o próprio `tenant_id`, imposto via RLS.

#### `tenant_telefone_autorizado`
> **[DEPRECATED — substituída por `usuario_telefone_autorizado` em 02/08/2026, ver
> seção 10.12]** Nunca chegou a ser consumida por nenhum controller/service — sem dado
> real a migrar. Identificação de telefone no WhatsApp passou a ser por usuário, não
> por tenant (um tenant pode ter vários usuários, cada um com seus próprios números).

Números de telefone autorizados a mandar mensagens em nome de um tenant pelo canal WhatsApp. Um tenant pode ter mais de um (dono + gerente, por exemplo).

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK | RLS |
| numero_whatsapp | varchar | formato E.164, único no sistema |
| nome_contato | varchar nullable | só para exibição no painel |
| ativo | boolean | |
| criado_em | timestamptz | |

> Mensagem recebida de um número não cadastrado é descartada e logada para auditoria — nunca cria cotação ou fornecedor automaticamente. Isso é a única fronteira de segurança do canal WhatsApp: quem manda mensagem do número certo é tratado como o próprio tenant.

#### `usuario_telefone_autorizado`
Números de WhatsApp autorizados a mandar mensagem em nome de um **usuário** (não do tenant inteiro) — substitui `tenant_telefone_autorizado` acima. Um usuário pode ter mais de um número (dono + WhatsApp da loja, por exemplo). Gerenciado pelo próprio usuário via `GET/POST/DELETE /usuarios/me/telefones` — não é rota admin.

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| usuario_id | uuid FK usuario | dono do número |
| tenant_id | uuid FK tenant | desnormalizado (redundante com `usuario.tenant_id`), só para manter a policy de RLS simples e igual às demais tabelas |
| numero_whatsapp | varchar | formato E.164, **único no sistema inteiro** (não só por tenant) — um número pertence a exatamente um usuário |
| nome_contato | varchar nullable | só para exibição no painel |
| ativo | boolean | |
| criado_em | timestamptz | |

> Mensagem recebida de um número não cadastrado em nenhum `usuario_telefone_autorizado` é descartada e logada — nunca cria cotação ou fornecedor. Mesma fronteira de segurança de `tenant_telefone_autorizado`, agora resolvendo para `usuario_id` (e, por consequência, `tenant_id` via `usuario`) em vez de `tenant_id` diretamente.

#### `fornecedor`
Cadastro persistente por tenant — sobrevive entre cotações, exatamente como no protótipo (a lista "Fornecedores" reaproveitada a cada nova cotação).

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK | RLS |
| nome | varchar | |
| prazo_entrega_padrao | varchar nullable | texto livre ("2 dias úteis") — igual ao protótipo |
| condicao_pagamento_padrao | varchar nullable | ("Boleto 14 dias") |
| pedido_minimo_padrao | numeric(12,2) nullable | |
| observacoes_padrao | text | |
| **status** | enum(ATIVO, **PENDENTE_DADOS**, INATIVO) | ver nota abaixo |
| origem_cadastro | enum(MANUAL, WHATSAPP_AUTO) | auditoria — de onde esse fornecedor veio |
| criado_em | timestamptz | |

> **Sobre `PENDENTE_DADOS`:** um fornecedor criado automaticamente via WhatsApp (o sistema só tem o nome, extraído da mensagem) nasce com `prazo_entrega_padrao`, `condicao_pagamento_padrao` e `pedido_minimo_padrao` nulos e status `PENDENTE_DADOS`. Ele participa normalmente da comparação de preço (Tabela Comparativa), mas fica de fora do cenário "Compra Equilibrada" do Mapa de Compra — que depende de saber o pedido mínimo para fazer sentido. O painel destaca fornecedores pendentes com um aviso visível até o operador completar os dados manualmente. Essa auditoria manual é, por design, parte do valor entregue pelo sistema — não um defeito a esconder.

#### `produto`
Catálogo interno do tenant — **não é o item da cotação**, é a referência mestre (ex: "Sazon Legumes 60g").

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK | RLS |
| nome | varchar | grafia original preservada (não normalizada/minúscula) — ver nota abaixo |
| marca | varchar | extraída via heurística (lista de marcas conhecidas, igual `extrairMarca()` do protótipo) |
| peso_volume_valor | numeric | ex: 60 |
| peso_volume_unidade | varchar(5) | g/kg/ml/l |
| unidade_padrao | varchar(10) | un/cx/fd/pct/kg/lt/dz |
| **embalagem_qtd_sugerida** | integer nullable | **valor de conveniência**, não autoritativo — ver seção 3.4 |
| criado_em | timestamptz | |

> **Nota (01/08, correção de gap real):** até 01/08 nada no backend criava uma linha
> nova em `produto` — `CotacaoListaService.processarLista` só *comparava* contra o
> catálogo existente (`MatchingProdutoService.conciliarProdutosCotados`); quando nada
> batia, `cotacao_produto.produto_id` ficava nulo para sempre. Como todo tenant novo
> começa com catálogo vazio, isso significava que o catálogo nunca saía do zero —
> Comparativo/Mapa de Compra nunca expuseram o problema (toleram `produto_id` nulo,
> caindo pro texto bruto), mas Histórico de Preços (seção 11), que exige identidade de
> catálogo por desenho, ficava sempre vazio. Corrigido: quando o matching não encontra
> um produto existente com score suficiente (`SCORE_MINIMO_MATCH = 0.6`), um `Produto`
> novo é criado a partir da linha parseada (`nome`, `marca` via `extrairMarca()`,
> `peso_volume_valor`/`unidade` via `extrairPesoVolume()`, `unidade_padrao` do parser,
> `embalagem_qtd_sugerida` via `detectarEmbalagemPorTexto()` quando detectado) e
> imediatamente adicionado ao catálogo usado pelo resto da MESMA lista colada
> (matching incremental — evita duplicar quando o mesmo produto aparece duas vezes na
> mesma lista com grafia levemente diferente). `nome` é armazenado com a grafia
> original, não normalizado/minúsculo como este texto dizia antes — o matching já
> normaliza no momento da comparação (`normTxt` dentro de `calcSimilaridade`), então a
> forma armazenada não afeta a qualidade do match, só a exibição em
> Comparativo/Mapa/Histórico (armazenar em minúsculo sem acento pioraria a UI à toa).
> Ver `CotacaoListaService.processarLista` e `CotacaoListaServiceMatchingTest`.

#### `cotacao`
Uma instância de processo de cotação (equivale a "iniciar nova cotação" no protótipo/bot).

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK | RLS |
| criado_por | uuid FK usuario nullable | nulo quando `canal_origem = WHATSAPP` |
| titulo | varchar | descrição livre dada pelo usuário ao iniciar ("Cotação semanal 02/07") |
| status | enum(RASCUNHO, EM_ANDAMENTO, FINALIZADA, CANCELADA) | |
| canal_origem | enum(WEB, WHATSAPP) | de onde a cotação foi iniciada |
| **ultima_atividade_em** | timestamptz | atualizado a cada mensagem WhatsApp processada — ver regra de expiração na seção 10 |
| **lista_revisada** | boolean, default TRUE | (02/08/2026) sinaliza se o usuário já revisou a lista recebida via WhatsApp antes de seguir para a Conferência. **Nota (04/08, Prompt 12):** a tela dedicada "Ajuste de Lista" (`/ajuste-lista`) foi removida — esse gate agora é uma renderização condicional dentro da própria tela `/cotacoes/{id}/entrada` (grid unificado, ver seção 12), não mais uma rota própria. Cotações web nunca passam por esse modo — nascem TRUE e nunca mudam; só o webhook (canal_origem=WHATSAPP) grava FALSE na criação. |
| cenario_selecionado | enum(MENOR_PRECO, EQUILIBRADA, MELHOR_PRAZO) nullable | espelha os 3 cenários do Mapa de Compra |
| finalizada_em | timestamptz nullable | |
| criado_em | timestamptz | |

#### `cotacao_produto`
Uma linha da lista de produtos colada pelo cliente (o "15un sazon legumes 60g").

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| cotacao_id | uuid FK | |
| produto_id | uuid FK nullable | resolvido pelo matching (`conciliarProdutosCotados` no protótipo); nulo enquanto "não identificado" |
| texto_original | varchar | linha exatamente como colada, para auditoria |
| quantidade | numeric | |
| unidade | varchar(10) | |
| ordem | integer | preserva ordem de digitação |
| **removido_em** | timestamptz nullable | **Nota (04/08, Prompt 12):** soft-delete — nunca `DELETE` físico. `IS NULL`/`IS NOT NULL` funciona como o flag; item removido some do Comparativo/Mapa/matching de resposta de fornecedor, mas fica preservado para auditoria. Ver §3.5. |
| **removido_por** | uuid FK usuario nullable | quem excluiu o item pelo grid (`CotacaoProdutoItemService.remover`) |

#### `cotacao_produto_fornecedor`
**Núcleo do sistema.** Liga um item pedido a um preço ofertado por um fornecedor específico.

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| cotacao_produto_id | uuid FK | |
| fornecedor_id | uuid FK | |
| texto_original | text | linha original da resposta do fornecedor |
| preco_informado | numeric(12,2) | preço bruto, como veio no texto |
| preco_unitario_calculado | numeric(12,2) | preço já normalizado por unidade — o que entra na comparação |
| **embalagem_qtd_confirmada** | integer nullable | **snapshot imutável** — ver 3.4; protegido por `@Version` (concorrência) desde 11/07 |
| tipo_embalagem_detectado | varchar(200) | caixa/fardo/pacote (heurística `detectarEmbalagemPorPreco()`/`detectarEmbalagemPorTexto()`) + CSV informativo dos motivos de Atenção/Revisar |
| sem_estoque | boolean | |
| confianca_match | numeric(3,2) | score 0–1 do matching por similaridade |
| status | enum(**OK, NAO_IDENTIFICADO, PENDENTE_CONFIRMACAO**) | reduzido de 5 para 3 valores em 16/07 — `DIVERGENCIA_PRECO`/`DIVERGENCIA_VOLUME` removidos por nunca serem usados; sempre gravado como `OK` na confirmação (ver nota abaixo) |
| resolvido_por | uuid FK usuario nullable | quem confirmou uma divergência de embalagem (`AvisoService.resolver`) |
| criado_em | timestamptz | |

> **Correção (24/07, v2.1):** `status_conferencia` (OK/ATENCAO/REVISAR) e
> `motivo_conferencia` (os 10 códigos de divergência: `BRAND_CHANGED`, `WEIGHT_CHANGED`,
> `WEIGHT_ADDED`, `VOLUME_ADDED`, `PACKAGE_QTY_ADDED`, `PACKAGE_QTY_CHANGED`,
> `PACKAGE_PRICE_SUSPECTED`, `MULTIPLE_OPTIONS`, `EXTRA_ITEM`, `LOW_CONFIDENCE_MATCH`)
> **não são colunas separadas** desta tabela — a versão anterior desta seção descrevia
> como se fossem. Na prática eles só existem como estado **transitório do preview**
> (`ItemConferencia`/`StatusConferencia`/`MotivoConferencia`, em memória, nunca
> persistidos individualmente), com uma exceção parcial: **múltiplos** motivos
> simultâneos por linha (não um só) são persistidos como **CSV** em
> `tipo_embalagem_detectado` no momento da confirmação (ex.:
> `"BRAND_CHANGED,LOW_CONFIDENCE_MATCH"`), puramente informativo — não afeta `status`
> nem nenhuma regra de negócio depois de confirmado. Ver `ClassificacaoConferenciaService`
> e a seção 6.6 abaixo.

#### `cotacao_fornecedor`
**Novo (V15, 16/07)** — não existia no modelo original. Controla o **gate sequencial**:
um fornecedor por vez dentro de uma cotação, na ordem em que foi adicionado.

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK | RLS direto (diferente de `cotacao_produto`/`cotacao_produto_fornecedor`, que só isolam via join-path) |
| cotacao_id | uuid FK | |
| fornecedor_id | uuid FK | |
| ordem | integer | posição na sequência de fornecedores da cotação |
| status | enum(PENDENTE, PROCESSADO, CONFIRMADO) | `PROCESSADO` = resposta em preview, ainda não persistida; `CONFIRMADO` = passou pela Conferência e liberou o próximo fornecedor |
| criado_em | timestamptz | |

> `CotacaoFornecedorService.adicionar()` recusa (409) adicionar um novo fornecedor
> enquanto o último da sequência não estiver `CONFIRMADO` — é o mecanismo que impõe "um
> fornecedor por vez" descrito na seção 6.7.

#### `marca` (novo, V16, 24/07)
Catálogo de marcas conhecidas, usado por `MatchingProdutoService.extrairMarca` (seção
6.6). Substitui a lista fixa que antes vivia hardcoded no Java.

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK **nullable** | `NULL` = marca default global, visível a **todo** tenant (seed de ~72 marcas na própria migration); preenchido = marca cadastrada pelo próprio tenant |
| nome | varchar(100) | |
| criado_em | timestamptz | |

> Exceção deliberada à regra geral de `tenant_id NOT NULL`: como não existe hoje nenhum
> fluxo de criação de tenant em runtime onde plugar um seed automático por-tenant, o
> default global é modelado como linhas com `tenant_id IS NULL`, visíveis a todo tenant
> (inclusive futuros) via RLS — em vez de copiado linha a linha em cada tenant existente.
> A policy de RLS permite leitura de linhas globais + próprias, mas **nunca** permite a
> um tenant comum escrever uma linha global (`WITH CHECK` exige `tenant_id =
> current_tenant_id()`). Revisado e aprovado pelo `db-schema-guardian` em 24/07.

#### `fornecedor_produto` (Fase futura — extensibilidade, não populada ainda)
Preparado para a feature de "parceria direta com fornecedores", onde o fornecedor mantém seu próprio catálogo de preços fora do ciclo de cotação.

| Campo | Tipo | Observação |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid FK | |
| fornecedor_id | uuid FK | |
| produto_id | uuid FK | |
| preco_referencia | numeric(12,2) | |
| embalagem_qtd | integer nullable | |
| atualizado_em | timestamptz | |
| origem | enum(MANUAL, API_PARCEIRO) | |

> Esta tabela só existe no schema desde já para não exigir migration disruptiva quando a feature for construída. Nenhuma tela ou endpoint a usa no MVP.

### 3.3 Diagrama ER (texto)

```
tenant 1───N usuario
usuario 1───N usuario_telefone_autorizado
tenant 1───N fornecedor
tenant 1───N produto
tenant 1───N cotacao
tenant 1───N fornecedor_produto (futuro)
tenant 1───N marca (tenant_id nullable — NULL = global, visível a todo tenant)

cotacao 1───N cotacao_produto
cotacao_produto N───1 produto (nullable)
cotacao_produto 1───N cotacao_produto_fornecedor
cotacao_produto_fornecedor N───1 fornecedor

cotacao 1───N cotacao_fornecedor
cotacao_fornecedor N───1 fornecedor

fornecedor_produto N───1 fornecedor
fornecedor_produto N───1 produto
```

### 3.4 O gap da embalagem: análise e decisão de design

**O problema que você descreveu:** ao identificar que 1 caixa de um produto contém, por exemplo, 12 unidades, você queria atualizar o produto-base globalmente, de forma que futuras cotações já viessem com a conversão pronta.

**Inconsistência identificada:** o mesmo produto pode ser vendido em tamanhos de embalagem **diferentes por fornecedor** (Fornecedor A: caixa com 10; Fornecedor B: caixa com 12, mesmo SKU/marca). Se `embalagem_qtd` vive só no `Produto` (global, um valor único), o último fornecedor que informar o número sobrescreve o anterior — e todos os fornecedores que nunca confirmaram esse número passam a herdar um valor que pode não ser o deles. Além disso, se esse valor mudar depois que uma cotação já foi finalizada, ele **não pode alterar retroativamente** o cálculo daquela cotação — senão você perde a integridade do histórico de preços (a base de "Sem histórico" / variação histórica que já aparece no protótipo depende disso estar certo).

**Decisão adotada (refletida no modelo acima):**

1. `cotacao_produto_fornecedor.embalagem_qtd_confirmada` é a **fonte da verdade** para aquela cotação específica — um snapshot, gravado uma vez e nunca reescrito depois de a cotação ser finalizada.
2. `produto.embalagem_qtd_sugerida` é só um **valor de conveniência** para pré-preencher o campo na *próxima* cotação (economiza clique, não decide preço).
3. Esse valor de conveniência só é atualizado automaticamente quando **múltiplos fornecedores confirmam o mesmo número** (ex: 2 de 3 fornecedores dizem "12 unidades") — se houver divergência entre fornecedores, o sistema mantém o campo como sugestão de menor confiança ou pede confirmação manual do operador, mas nunca sobrescreve silenciosamente com o valor de um único fornecedor.
4. Cada linha na tela de Tabela Comparativa mostra, quando aplicável, "conforme informado por [Fornecedor X]" — transparência sobre de onde veio o número, em vez de tratar como um fato universal do produto.

Isso resolve o problema descrito sem abrir mão do ganho de produtividade que você queria (menos digitação repetida).

> **Nota (24/07):** o gatilho concreto do item 1 é o campo "Un. por cx/fd" do modal de
> Conferência (`ConferenciaModal.tsx`), exibido quando o motivo `PACKAGE_PRICE_SUSPECTED`
> aparece numa linha — o operador informa a quantidade por embalagem ali,
> `AvisoService.resolver` grava o snapshot uma única vez (`embalagemQtdConfirmada`,
> nunca reescrito depois) e recalcula `precoUnitarioCalculado = precoInformado /
> embalagemQtd`. A regra de dados acima (snapshot por cotação, nunca sobrescrita global)
> é o que essa tela aciona — só faltava a referência cruzada entre as duas.

### 3.5 A trava de edição pós-conferência: análise e decisão de design

**O problema (Prompt 12 — Grid Unificado de Entrada de Dados):** ao generalizar o grid
editável (antes exclusivo da tela "Ajuste de Lista" do WhatsApp) para virar a interface
primária de entrada de produtos também no fluxo Web, o operador passou a poder editar
produto/quantidade/unidade de qualquer item a qualquer momento — inclusive depois de um
fornecedor já ter confirmado uma resposta para aquele item específico.

**Inconsistência identificada:** `cotacao_produto_fornecedor` guarda o que o fornecedor
de fato ofertou para aquele item (preço, embalagem, texto original) — se o operador muda
depois a quantidade ou troca o produto associado ao item base, a resposta do fornecedor
já confirmada fica ligada a um item diferente do que ela realmente descreve, sem que
nada sinalize a divergência. Diferente do gap da embalagem (§3.4, que é sobre um valor
de conveniência global), aqui o risco é silenciar uma divergência entre dois dados que
deveriam sempre concordar por construção: o que o item pede e o que o fornecedor
respondeu pra aquele item.

**Decisão adotada:**

1. Assim que existir **pelo menos uma linha em `cotacao_produto_fornecedor`** para um
   `cotacao_produto` (criada tanto por `ConfirmacaoRespostaService.confirmar` — fluxo
   Web, depois da Conferência — quanto por
   `WhatsappRespostaFornecedorService.persistirAutomaticamente` — fluxo WhatsApp,
   direto, sem preview), esse item fica **travado para edição** de produto/quantidade/
   unidade: `CotacaoProdutoItemService.editar` passa a lançar `ConflictException`.
2. **Excluir continua permitido** mesmo com o item travado — vira soft-delete (§3.2,
   `removido_em`), preservando o registro para auditoria em vez de apagar a
   divergência.
3. **Adicionar um item novo** continua permitido a qualquer momento, mesmo após
   conferências já confirmadas — o item novo nasce sem nenhuma resposta de fornecedor
   vinculada, então não há nada para travar.
4. **Exceção conhecida e aceita:** `CotacaoListaService.processarLista` (usado pelo
   paste manual e pela ingestão de mensagens WhatsApp) continua fazendo upsert-por-match
   — se uma nova mensagem/paste casar com o produtoId ou textoOriginal de um item já
   travado, ele ainda é atualizado por essa via. Essa assimetria entre os dois
   caminhos de escrita foi identificada durante a implementação e deliberadamente não
   fechada agora: `processarLista` está fora do escopo deste prompt (é o motor de
   ingestão do WhatsApp, precisa continuar acumulando mensagens antes da revisão
   humana), e fechar essa lacuna exigiria repensar aquele fluxo, não só o grid.

Isso resolve o risco de divergência silenciosa no caminho principal (edição manual pelo
grid) sem impedir o operador de corrigir um erro (via exclusão + readição).

> **Nota (08/08, Prompt 15 — unificação Web/WhatsApp do processamento de resposta de
> fornecedor):** `WhatsappRespostaFornecedorService.persistirAutomaticamente`, citado no
> item 1 acima, **não existe mais**. O canal WhatsApp deixou de persistir em
> `cotacao_produto_fornecedor` fora do fluxo de Conferência — agora, `cotacao_produto_fornecedor`
> só é criado por `ConfirmacaoRespostaService.confirmar`, o único gate de persistência do
> sistema, para os dois canais, sem exceção. A trava de edição descrita neste item
> continua funcionando exatamente igual, só que agora dispara no MESMO momento pros dois
> canais (após confirmação explícita), em vez de quase instantaneamente pro WhatsApp
> (que persistia sem revisão humana). Ver seção 10.6 para o detalhe completo da
> unificação.

---

## 4. Multi-tenancy e Segurança

- **JWT** com claims: `sub` (usuario_id), `tenant_id` (nulo para admin PRX), `papel`.
- Interceptor/filter do Spring Boot injeta `SET app.current_tenant_id = '<uuid>'` na conexão antes de cada query.
- Políticas RLS em toda tabela com `tenant_id`:
  ```sql
  CREATE POLICY tenant_isolation ON cotacao
    USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
  ```
- Admin PRX usa uma role de banco separada que **ignora** RLS (`BYPASSRLS`), restrita ao painel administrativo.
- Senhas: bcrypt. Tokens: access curto (15min) + refresh (7 dias), rotação de refresh token.
- HTTPS obrigatório (planejado via Caddy no droplet — ver seção 9; ainda não implementado).
- Rate limiting global por IP (token bucket, 35 de capacidade, reposição de 10 tokens/s — retunado em 15/07 após estourar em uso real).

---

## 5. API (estado real, 21/07/2026)

| Recurso | Endpoints reais | Status |
|---|---|---|
| Auth | `POST /auth/login`, `POST /auth/refresh` | ✅ |
| Admin | `GET/POST/PUT /admin/tenants`, `GET/POST/PUT /admin/tenants/{id}`, `GET/POST/PUT/POST.../reset-senha /admin/tenants/{id}/usuarios`, `GET/POST/PUT /admin/administradores`, `GET/POST/PUT /admin/tenants/{id}/templates-mensagem` | ✅ **Correção 12/08 — esta linha estava desatualizada.** Painel Admin PRX completo: CRUD de tenants, CRUD de administradores (`ADMIN_PRX`), CRUD de usuários por tenant (com reset de senha), e desde o Prompt 18 CRUD de templates de mensagem WhatsApp por tenant (ver 10.7/10.8) — todos sob o gate `.requestMatchers("/admin/**").hasRole("ADMIN_PRX")` |
| Fornecedores | `GET/POST/PUT/DELETE /fornecedores` | ✅ |
| Produtos | `GET /produtos` (busca para matching), `PUT /produtos/{id}` | ✅ (sem `POST` direto — catálogo cresce via matching da lista OU, desde 04/08, via `nomeProdutoLivre` no `POST /produtos` da cotação abaixo — mesmo pipeline resolver-ou-criar, nunca um cadastro de produto solto) |
| Cotações | `POST /cotacoes` (inicia), `GET /cotacoes`, `GET /cotacoes/{id}` | ✅ |
| Item da cotação | `POST /cotacoes/{id}/produtos`, `PATCH/DELETE /cotacoes/{id}/produtos/{cotacaoProdutoId}` | ✅ **Prompt 12 (04/08)**: `POST` novo (adição manual pelo grid, `produtoId` OU `nomeProdutoLivre`). `DELETE` agora é soft-delete incondicional (antes bloqueava com 409 se já houvesse resposta de fornecedor confirmada — ver §3.5). `PATCH` agora lança 409 se o item já tem resposta de fornecedor confirmada (trava de edição pós-conferência, §3.5) |
| Entrada de lista | `POST /cotacoes/{id}/lista` (parse + upsert idempotente) | ✅ |
| Importação em massa (grid) | `POST /cotacoes/{id}/produtos/importar-texto` | ✅ **novo (Prompt 12, 04/08)**: modal "Colar do WhatsApp" do grid unificado — mesmo parser de `POST .../lista`, mas sempre-append (nunca upsert); linha cujo produto já está vivo na cotação é ignorada e reportada como `duplicado`, não falha a importação inteira |
| Fornecedor da cotação (gate) | `GET/POST /cotacoes/{id}/fornecedores` | ✅ (novo — adiciona fornecedor à sequência; recusa 409 se o anterior não estiver `CONFIRMADO`) |
| Resposta de fornecedor (preview) | `POST /cotacoes/{id}/fornecedores/{fornId}/resposta` | ✅ **mudou de comportamento**: não persiste mais direto, devolve `PreviewRespostaResponse` pra Conferência |
| Confirmação da Conferência | `POST /cotacoes/{id}/fornecedores/{fornId}/confirmar` | ✅ **corrigido em 23/07** (commit `8be70f1`): não é mais delete+insert — upsert real por `cotacao_produto_id`, item não mencionado na resposta atual é preservado intacto. `ResolucaoItemRequest.tipo` ganhou `ADICIONAR_A_LISTA`/`ASSOCIAR_A_ITEM` (24/07) para os itens `EXTRA_ITEM` (antes sempre descartados na confirmação, sem forma de referenciá-los no contrato) — ver 6.6 |
| Resolução de avisos | `POST /cotacoes/{id}/avisos/{cpfId}/resolver` | ✅ (retorna a entidade JPA crua, não um DTO — dívida técnica registrada em `TODO-PROTOTYPE.md`) |
| Comparativo | `GET /cotacoes/{id}/comparativo` | ✅ **campo novo (24/07)**: `PrecoFornecedor.divergenciaComparativa` (boolean, computado ao vivo) — ver 6.4 |
| Mapa de compra | `GET /cotacoes/{id}/mapa?cenario=MENOR_PRECO` | ✅ (somente-leitura; sem ajuste manual de distribuição) |
| Finalização | `POST /cotacoes/{id}/finalizar` | ✅ |
| Mensagem WhatsApp (texto pra copiar) | `GET /cotacoes/{id}/fornecedores/{fornId}/mensagem` | ✅ (gera texto — não envia, não é o webhook da seção 10) |
| Webhook WhatsApp | `GET/POST /whatsapp/webhook` | ✅ handshake de verificação (`GET`) + recepção de mensagens (`POST`), assinatura HMAC + idempotência — ver seção 10 |
| Simulação WhatsApp (dev/QA) | `POST /dev/whatsapp/simular` | ✅ só fora do perfil `prod` — monta e assina um payload real, chama o próprio webhook (seção 10.8) |
| Histórico/Economia | — | ❌ **não existe** — ver seção 11 |

---

## 6. Lógica de negócio a portar do protótipo

O protótipo já tem lógica de parsing e matching validada visualmente com o cliente. Ela deve ser **portada para services Java no backend** (não reimplementada do zero) — o comportamento já é conhecido e aprovado.

### 6.1 Parser de lista de produtos
Regex: `^(\d+)\s*(un|und|fd|cx|pct|kg|lt?|dz)\s+(.+)$` — quantidade + unidade + nome. Fallback para linha sem unidade explícita (assume `un`, quantidade 1 se não numérica).

> **Atualização (24/07):** a regex acima é a versão original (só abreviações). Desde
> 24/07 o parser reconhece também formas por extenso — `unidade(s)`, `fardo(s)`
> (+ `f` solto), `caixa(s)`, `pacote(s)`, `litro(s)`, `dúzia(s)`/`duzia(s)` — via um
> dicionário `UNIT_ALIASES` (mesmo padrão do `MEDIDA_ALIASES` já existente em
> `MatchingProdutoService`), normalizando o token capturado para a forma-padrão-curta
> antes de virar `LinhaParseada.unidade()`.

> **Nota (04/08, Prompt 12 — Grid Unificado de Entrada de Dados):** a tela de entrada
> de produtos do fluxo Web (`/cotacoes/{id}/entrada`) e a do fluxo WhatsApp (antes uma
> rota própria, `/ajuste-lista`, removida) convergiram para o mesmo componente de grid
> editável. O textarea de paste permanente da tela Web foi descontinuado em favor de um
> modal ("Colar do WhatsApp") que importa o texto colado como itens novos no grid —
> decisão de design motivada pelo pedido do cliente de poder adicionar/editar/excluir
> produtos individualmente durante a cotação, algo inviável com texto livre solto.
> `POST /cotacoes/{id}/produtos/importar-texto` reaproveita este mesmo parser (seção
> 6.1) e o pipeline de matching (seção 6.3), mas nunca faz upsert — cada linha vira uma
> `CotacaoProduto` nova, ou é ignorada (reportada como duplicada) se o produto já está
> vivo na cotação. Ver §3.5 pra trava de edição pós-conferência introduzida no mesmo
> prompt.

### 6.2 Parser de resposta de fornecedor
Reconhece linhas de preço (`Produto - R$ 4,89`), linhas de "sem estoque", e ignora linhas de cabeçalho/saudação (regex de skip: "bom dia", "pagamento", "entrega", etc.).

### 6.3 Matching por similaridade (`calcSimilaridade`)
Compara texto do fornecedor com o catálogo de produtos: tokeniza, remove stopwords, calcula score de sobreposição de tokens, com bônus se peso/volume batem exatamente e penalidade (score zero) se peso/volume batem em unidade mas divergem em valor (ex: "500ml" vs "250ml" do mesmo termo — sinal de produto diferente, não de erro de digitação).

### 6.4 Detecção de divergência de preço (possível preço de caixa)

> **Correção (24/07, v2.1):** a versão anterior desta seção descrevia este comportamento
> como se já estivesse implementado e ligado a um fluxo de aviso — não estava. O único
> código pré-existente parecido (`MatchingProdutoService.detectarEmbalagemPorPreco`,
> heurística de **razão** preço-item/mediana ≥ 3×) nunca foi chamado por nenhum
> service/controller — código morto. O parágrafo original descrevia a intenção, não o
> estado real do código naquele momento.

**Implementado em 24/07** (`ComparativoService.temDivergenciaComparativa`): para cada
item com preço confirmado de ≥2 fornecedores, calcula a mediana dos preços dos **outros**
fornecedores (excluindo o próprio e excluindo `semEstoque=true`) e marca
`PrecoFornecedor.divergenciaComparativa=true` quando `preço − mediana ≥ R$10,00`.
Diferente do parágrafo original: (a) é **direcional** — só dispara para o preço mais
alto, nunca para o mais baixo (senão os dois lados de um par díspar seriam sinalizados
simetricamente, o que não faz sentido pra "suspeita de preço de caixa/fardo"); (b) é
computado **ao vivo** a cada `GET /comparativo`, sem tabela de aviso nem persistência —
consistente com o resto do Comparativo, que já lê os dados direto do banco a cada
chamada; (c) não há hoje um modal "Informar Unidades da Embalagem" acionado a partir
desta divergência — o operador já tem esse fluxo separadamente via `AvisoService.resolver`
(ver 3.4), não conectado a este sinal. Cobertura de teste: T-12 da especificação técnica
do Motor de Conferência (ver 6.6).

### 6.5 Cenários do Mapa de Compra
Três algoritmos de distribuição fornecedor→produto:
- **Menor Preço:** para cada item, escolhe o fornecedor com menor preço, ignorando pedido mínimo.
- **Compra Equilibrada:** tenta concentrar em menos fornecedores respeitando pedidos mínimos.
- **Melhor Prazo:** prioriza fornecedores com menor prazo de entrega, com desempate por preço.

### 6.6 Motor de matching/classificação — reescrito em 16/07 (protótipo 14/07)

O protótipo foi trocado durante a implementação (`COTA_TESTE_29_06_-_V5.html` →
`"COTA&TESTA - 14.07 - V5.html"`) com o algoritmo de matching reescrito entre as duas
versões. A seção 6.3 acima descreve o comportamento herdado; o motor real hoje é mais
rico:

- `extrairPesoVolume`/`calcSimilaridade` unificam peso e volume num modelo
  `dimensao`/`valorBase` único (em vez de tratar como dois eixos separados).
- `conciliarEnhanced` roda uma 2ª passada (`calcSimilaridadeSemVolume`) quando o score
  da 1ª é baixo, antes de desistir do match.
- `ConciliacaoRespostaService.agrupar` (porte de `_srAgrupar`) redistribui candidatos
  ambíguos entre itens em até 5 rodadas, quando mais de uma linha da resposta poderia
  casar com o mesmo produto.
- `ClassificacaoConferenciaService` (porte de `buildSupplierReview`) classifica cada
  item em `status_conferencia` (OK/ATENCAO/REVISAR) com um dos 10 `motivo_conferencia`
  listados na seção 3.2 — cascata que só escala, nunca desescala, alimentando o modal
  de Conferência (seção 6.7).
- `extrairMarca` portado (catálogo de marcas conhecidas + heurística de palavra
  capitalizada — desde 24/07 o catálogo é a tabela `marca`, não mais lista Java
  hardcoded; ver 6.8).

### 6.7 Fluxo sequencial de fornecedor + Conferência (não estava no plano original)

O fluxo de resposta de fornecedor não é mais "cola texto → persiste direto". Hoje é
sequencial e gated, um fornecedor por vez dentro da cotação:

1. `POST /cotacoes/{id}/fornecedores` adiciona o próximo fornecedor à sequência
   (`cotacao_fornecedor`, `ordem` incremental) — recusa com 409 se o fornecedor anterior
   ainda não estiver `CONFIRMADO`.
2. `POST .../fornecedores/{fornId}/resposta` roda o pipeline de parsing/matching/
   classificação (6.6) mas **não persiste** — devolve um preview e marca o fornecedor
   como `PROCESSADO`.
3. O operador revisa o preview no modal de Conferência (contadores OK/Atenção/Revisar
   recalculados ao vivo no frontend conforme resolve cada linha) e resolve os itens
   `REVISAR` (seleciona candidato, edita manualmente, ou marca sem oferta).
4. `POST .../fornecedores/{fornId}/confirmar` (`ConfirmacaoRespostaService`) roda o
   mesmo pipeline de novo a partir do texto reenviado (sem estado de rascunho — decisão
   consciente) e aí sim persiste em `cotacao_produto_fornecedor`, marcando o fornecedor
   `CONFIRMADO` — só então o próximo fornecedor pode ser adicionado.

Resolução do cliente na Conferência só pode se aplicar a item `REVISAR` — restrição
adicionada depois que o `security-reviewer` apontou que, sem ela, dava para fabricar
preço num item já `OK`/`Atenção`.

> **Correção (24/07, v2.1):** a versão anterior desta seção dizia "idempotente via
> delete+insert" — **isto nunca foi correto** e virou uma regressão real (corrigida no
> commit `8be70f1`, 23/07): a confirmação fazia `deleteAll` incondicional das linhas já
> confirmadas antes de reinserir, então qualquer item já confirmado numa rodada anterior
> e não mencionado de novo na resposta atual **desumia**. Hoje é **upsert real** por
> `cotacao_produto_id`: item não mencionado é preservado intacto
> (`ItemConferencia.preservado`), e só "sem oferta" explícito apaga uma linha existente.
> Ver `ClassificacaoConferenciaService.mesclarComConfirmacoesAnteriores`.

### 6.8 Alinhamento com a Especificação Técnica do Motor de Conferência (24/07)

Uma sessão anterior (16/07) já havia portado a maior parte do motor de conferência
(seção 6.6) a partir de uma versão anterior da especificação técnica do "Motor de
Conferência do Fornecedor" (documento de referência externo, fora deste repositório).
Um diagnóstico em 24/07, comparando o código real contra a versão mais recente dessa
especificação, encontrou a maior parte do comportamento já correto e testado — as
lacunas reais fechadas nesta revisão foram:

1. **Divergência comparativa por mediana** (T-12) — ver 6.4.
2. **"Adicionar à lista" / "Associar a outro item"** para item extra (`EXTRA_ITEM`):
   antes, um item ofertado pelo fornecedor sem correspondência na lista base era
   **sempre descartado silenciosamente** na confirmação — o contrato
   (`ResolucaoItemRequest.itemBaseId`) nem permitia referenciá-lo. Agora
   `TipoResolucao.ADICIONAR_A_LISTA` cria um `CotacaoProduto` novo na lista base
   compartilhada (com dedup por nome normalizado, e `quantidade`/`unidade` herdados do
   que o fornecedor informou, ou `1`/`un` de fallback), e `ASSOCIAR_A_ITEM` liga
   manualmente a um item base já existente. Nenhuma coluna nova foi necessária para
   marcar o produto como "exclusivo do fornecedor" — isso já é automático no modelo
   atual: só o fornecedor que confirmou ganha linha em `cotacao_produto_fornecedor`,
   os demais simplesmente não têm preço para aquele item.
3. **Catálogo de marcas por tenant** (tabela `marca`, seção 3.2) — substitui a lista
   fixa hardcoded em `MatchingProdutoService`, seedada com as mesmas ~72 marcas como
   default global, extensível por tenant.
4. **Sinônimos de unidade na lista base** (seção 6.1).

Pontos que a especificação documenta como comportamento **latente/deliberadamente não
implementado** e que esta revisão manteve como estão, sem "corrigir" silenciosamente
(decisão consciente, não descuido):
- `PACKAGE_PRICE_SUSPECTED` continua inerte para itens **com** preço (só dispara para
  item `precoPendente`/"consultar") — o parser nunca gera `precoBase` diferente de
  `unidade`/`sem_preco`, então o caminho pra item com preço nunca é alcançado. A
  suspeita real de preço de caixa/fardo em item com preço é coberta pela divergência
  comparativa (item 1 acima), não por este código.
- O diff de reprocessamento (mantidos/removidos/adicionados/alterados) continua parcial
  — o backend sabe dizer "preservado" (não mencionado) e "preço anterior" (pra badge de
  Atualização no modal de Conferência), mas não expõe as 4 categorias formais. No
  protótipo original esse diff nem chega a ser renderizado (código morto por lá também).

---

## 7. Testes (estado real, 21/07/2026)

| Tipo | Estado |
|---|---|
| Unitário backend (JUnit5) | ✅ 23 arquivos de teste, cobrindo parsers, matching, os 3 cenários do Mapa de Compra, conferência/classificação |
| Integração RLS (Testcontainers + Postgres real) | ✅ Configurado e em uso (`TestcontainersConfig`) — `MultiTenantIsolationTest` e `EmbalagemSnapshotIsolationTest` cobrem isolamento cross-tenant e a regra de snapshot da seção 3.4 |
| Componente/unitário frontend (Vitest) | ⚠️ Só 3 arquivos (`api.test.ts`, `useAsync.test.tsx`, `ProdutosAdicionadosSection.test.tsx`) |
| E2E frontend (Playwright) | ❌ Dependência instalada, sem `playwright.config` nem specs — verificação ponta a ponta até agora só manual, não automatizada |
| Contrato | Não formalizado como suite própria — cobertura via testes de integração dos controllers |

Testes de RLS são os mais importantes do sistema — um vazamento de dados entre tenants é o pior cenário possível para um SaaS B2B. Gaps conhecidos de teste (não bloqueiam v1, mas registrados): `JwtService` sem teste unitário isolado de expiração/assinatura inválida, sem teste de payload inválido em Login/Refresh, sem teste do papel `ADMIN_PRX` (não há o que testar — endpoint não existe ainda), sem teste de expiração de refresh token.

Cobertura da revisão de 24/07 (seção 6.8): T-12 (divergência comparativa) em
`ComparativoServiceTest`; "Adicionar à lista"/"Associar a outro item" em
`ConfirmacaoRespostaServiceTest`; isolamento multi-tenant do catálogo de marcas em
teste próprio de RLS; sinônimos de unidade em `ParserListaProdutosServiceTest`. Matriz
completa de referência: especificação técnica do Motor de Conferência do Fornecedor
(documento externo, ver seção 6.8).

---

## 8. Observabilidade (lean) — planejada, ainda não implementada

Para o volume inicial (poucos tenants), não se justifica uma stack completa de
Prometheus/Grafana/Loki self-hosted. Plano (nada abaixo está implementado hoje, além do
Actuator):

- **Spring Boot Actuator** (`/actuator/health`, `/actuator/metrics`) habilitado — ✅ já existe (usado até pelo healthcheck do `docker-compose.yml`).
- **Netdata + Dozzle** no droplet — acesso **só via Tailscale, nunca no firewall público** (regra 4 do `CLAUDE.md`). Não implementado.
- **Sentry (free tier)** para captura de exceções não tratadas no backend e frontend. Não implementado.
- Métrica de negócio (cotações criadas/finalizadas por dia). Não implementado — depende do painel admin (seção 5), que também não existe.

---

## 9. Deploy e Infraestrutura (estado real, 21/07/2026; corrigido em 12/08 — ver nota)

> Plano mudou de Railway/Render/Vercel (v1.0) para **droplet único DigitalOcean**, hoje
> refletido no `CLAUDE.md` e mais avançado no repo do que a v2.2 desta doc registrava.

> **Correção 12/08 (achado do diagnóstico do Prompt 18, não introduzido por ele):** a
> v2.2 desta seção registrava "sem containerização do frontend, sem Caddy" como se
> fosse zero código de infra — isso já estava desatualizado antes mesmo deste prompt.
> O estado real, confirmado pelo histórico de commits (vários `fix caddy to route as
> https secure`, `Prefixar toda a API com /api pra simplificar roteamento do Caddy`):

- **Backend:** ✅ `backend/Dockerfile` (multi-stage Maven → JRE Alpine) + serviço `backend` em `docker-compose.yml` (raiz do repo), orquestrado junto com banco/frontend/Caddy — sem publish de imagem em registry externo, sem deploy automatizado (isso continua fora, ver CI abaixo).
- **Frontend:** ✅ `frontend/Dockerfile` + serviço `frontend` em `docker-compose.yml` — **correção**: a v2.2 desta doc registrava isso como inexistente.
- **Proxy reverso / HTTPS:** ✅ `Caddyfile` na raiz do repo, HTTPS automático via `{$CADDY_DOMAIN}`, roteamento `reverse_proxy /api/* backend:8080` + `reverse_proxy frontend:3000` — **correção**: a v2.2 desta doc registrava isso como inexistente. API do backend foi prefixada com `/api` especificamente para simplificar essa regra de roteamento.
- **Banco:** planejado como instância gerenciada separada do droplet de aplicação — backups automáticos diários. Em produção hoje roda como serviço `db` do próprio `docker-compose.yml` (não confirmado se já migrou para instância gerenciada separada — não há evidência disso no repo). Localmente, Postgres de dev/teste continua no container `cotacao-test-db`.
- **Domínio:** parametrizado via `CADDY_DOMAIN` no `.env` (não commitado, como esperado para secret/config de ambiente) — não é possível confirmar o valor real a partir do repo.
- **CI:** ❌ ainda sem `.github/workflows/` — nenhum pipeline roda testes em PR. Continua sendo o maior gap real de infraestrutura.
- **Secrets:** variáveis de ambiente usadas tanto localmente quanto no `docker-compose.yml` de produção (`JWT_SECRET`, `WHATSAPP_*`, `ADMIN_BOOTSTRAP_*`, `APP_CORS_ALLOWED_ORIGINS`, `CADDY_DOMAIN`) — sem default em `application-prod.yml` para nenhuma credencial sensível (boot falha explicitamente se não setada), padrão consistente em todo o projeto.
- **Firewall:** regra já definida no `CLAUDE.md` (Netdata/Dozzle só via Tailscale) — nenhum dos dois está no `docker-compose.yml` hoje, então a regra ainda não tem o que proteger; segue sem stack de monitoramento provisionada.

**O que de fato falta para fechar o v1 (revisado 12/08):** CI/CD (GitHub Actions) e a
stack de monitoramento (Netdata/Dozzle/Tailscale/Sentry) — não mais "toda a
infraestrutura", como a v2.2 desta seção dava a entender. O módulo WhatsApp (seção 10)
está pronto desde 09/08.

---

## 10. Módulo WhatsApp (v1 — desenho simplificado, sem conversa/estado)

> **Status em 09/08/2026: pronto e funcional.** O desenho abaixo é o que está de fato
> implementado (não um plano) — construído em três fases (`9804635` webhook +
> classificador + roteamento, `0612d57` marcador explícito substituindo a heurística de
> `R$`, Prompt 15/`11e006c` unificação do núcleo de persistência com o canal Web) e
> reorganizado em pacotes por responsabilidade no Prompt 16/17 (`whatsapp.webhook` /
> `whatsapp.canal` / `whatsapp.envio`, ver nota em 10.8). Cobertura de teste em
> `backend/src/test/java/com/prx/cotacao/whatsapp/` (assinatura, webhook end-to-end,
> classificador, resolução de fornecedor, roteamento de resposta). Endpoint de
> simulação para QA/seed local em `POST /dev/whatsapp/simular` (perfil `!prod`,
> `WhatsappSimulacaoController`) — assina e envia um payload real pro próprio
> `/whatsapp/webhook`, exercitando o caminho completo. O que falta para v1 é só o
> deploy em produção (seção 9), não este módulo.

### 10.1 Objetivo e princípio de design

Cobrir o passo mais importante e mais repetitivo da operação — a **entrada de dados** (lista de produtos e respostas de fornecedores) — diretamente pelo WhatsApp oficial da PRX, sem exigir que o cliente abra o navegador para essa parte.

Diferente do desenho original (bot conversacional com botões e máquina de estados, avaliado e descartado por complexidade desnecessária para o problema real), este módulo **não conduz uma conversa**. Ele **classifica e roteia mensagens recebidas**, com uma regra simples e sem passos: o cliente manda texto no formato que já usa hoje, o sistema entende o que é e faz a coisa certa. Toda decisão que exige julgamento (qual fornecedor priorizar, resolver uma divergência de marca, finalizar a cotação) continua acontecendo no sistema web — o WhatsApp é o funil de entrada de dados, a auditoria é no painel.

Essa divisão de responsabilidade — WhatsApp automatiza a captura, o painel web é onde a expertise de revisão acontece — **é o próprio valor agregado do produto**, não uma limitação a esconder do cliente.

### 10.2 Identificação do tenant

Cada tenant cadastra os números de telefone autorizados a mandar mensagens em seu nome (`tenant_telefone_autorizado`, seção 3.2). Mensagem de número não cadastrado é descartada e logada — nunca cria cotação ou fornecedor. Essa é a única fronteira de segurança do canal: o WhatsApp não autentica identidade além do número de origem, então o cadastro prévio de número é o que garante que uma mensagem pertence ao tenant certo.

### 10.3 Classificação da mensagem

Toda mensagem recebida do número autorizado de um tenant precisa começar com um
marcador explícito de tipo na **primeira linha** — não existe mais heurística de
conteúdo (a versão anterior desta seção classificava por presença de `R$`, o que
classificava errado a maioria das respostas reais de fornecedor, que raramente
escrevem "R$" explícito; corrigido em 08/2026):

| Tipo | Como reconhecer | O que o sistema faz |
|---|---|---|
| **Lista de produtos** | 1ª linha = `LISTA_PRODUTOS`; linhas seguintes no formato `qtd + unidade + produto` | Adiciona os produtos à cotação em andamento do tenant. Se não houver cotação em andamento (ver regra de expiração abaixo), **cria uma nova cotação** com esses produtos. |
| **Resposta de fornecedor** | 1ª linha = `RESPOSTA_FORNECEDOR`; 2ª linha = nome do fornecedor; linhas seguintes com o preço | Se houver cotação em andamento → anexa a resposta desse fornecedor a ela. Se não houver → **cria uma nova cotação**, usando os produtos dessa própria lista como base (o fornecedor "abre" a cotação). |

O reconhecimento da 1ª linha tolera variação de caixa/acento/separador (espaço,
underscore ou hífen entre as palavras) **e** erro de digitação — via similaridade de
Levenshtein contra os dois marcadores, limiar 0,75 (`ClassificadorMensagemWhatsapp`).
Se a 1ª linha não for parecida o suficiente com nenhum dos dois (ou empatar entre os
dois acima do limiar), a mensagem é **"formato não reconhecido"**: nenhuma cotação ou
fornecedor é criado, o cliente recebe a mensagem de erro da seção 10.7, e a mensagem
NUNCA cai numa heurística de conteúdo como fallback — universo fechado de 2 tipos.

Unifiquei o comportamento dos dois tipos reconhecidos para o caso de "não há cotação
em andamento": em ambos, o sistema cria uma nova automaticamente. Isso evita uma
resposta de erro tipo "nenhuma cotação ativa" no meio da correria — o cliente nunca
precisa pensar sobre isso.

### 10.4 Regra de expiração da "cotação em andamento"

Uma cotação só é considerada "atual" para fins de roteamento de WhatsApp se `status = EM_ANDAMENTO` **e** `ultima_atividade_em` estiver dentro das últimas **48 horas**. Sem essa regra, uma cotação esquecida há semanas absorveria silenciosamente a próxima lista de fornecedor não relacionada que chegasse — um bug caro e difícil de perceber. Passadas 48h de inatividade, a próxima mensagem recebida inicia uma cotação nova, e a antiga fica visível no painel para o operador decidir se finaliza ou descarta manualmente.

### 10.5 Reconhecimento do fornecedor

O nome do fornecedor (primeira linha da mensagem de resposta) é casado por similaridade contra os fornecedores já cadastrados do tenant — reaproveitando o mesmo motor de matching (`calcSimilaridade`, seção 6.3) já usado para produtos, não exigindo igualdade exata de texto.

- **Encontrado** → a resposta é atribuída a esse fornecedor.
- **Não encontrado** → cria um fornecedor novo com `status = PENDENTE_DADOS` e `origem_cadastro = WHATSAPP_AUTO` (seção 3.2). Ele já participa da comparação de preço, mas fica de fora do cenário "Compra Equilibrada" do Mapa de Compra até o operador completar prazo de entrega, condição de pagamento e pedido mínimo no painel.

### 10.6 Divergências continuam exigindo revisão humana

A troca de marca do exemplo original (cliente pede "bombom nestle", fornecedor cota "bombom lacta") já é coberta pela lógica de matching e detecção de divergência das seções 6.3 e 6.4 — quando a confiança do match cai abaixo do limiar, o item é sinalizado no painel para o operador decidir (ajustar o pedido ou pedir nova cotação ao fornecedor). O WhatsApp nunca resolve isso sozinho — apenas captura o dado e sinaliza.

> **Nota (04/08, correção de gap real):** "sinaliza" originalmente só queria dizer
> "grava o item com `StatusItem.PENDENTE_CONFIRMACAO`" — mas até 04/08
> `CotacaoFornecedor.status` era gravado como `CONFIRMADO` incondicionalmente pelo
> webhook, mesmo com item pendente, o que escondia o sinal dos dois lugares do painel
> que dependem desse status (gate de "+ Adicionar Fornecedor" e a ordenação
> "pendentes primeiro" da Entrada de Dados). Corrigido: `CONFIRMADO` só quando não
> sobra nenhum item pendente deste fornecedor, senão `PROCESSADO` — mesmo estado
> intermediário do fluxo web.

> **Nota (08/08, Prompt 15 — unificação Web/WhatsApp, substitui a nota de 04/08
> acima):** a correção de 04/08 tratou o sintoma (status incondicional), mas a causa
> raiz continuava lá — `WhatsappRespostaFornecedorService.persistirAutomaticamente`
> gravava a resposta direto em `cotacao_produto_fornecedor` sem passar pela
> Conferência, o único gate de qualidade do sistema (seção 6.7), enquanto o canal Web
> sempre exigiu preview + confirmação explícita do operador antes de persistir
> qualquer coisa. Isso quebrava a garantia central do produto: dois comportamentos de
> persistência diferentes pro mesmo tipo de evento de negócio, dependendo só do canal
> de origem.
>
> Corrigido de raiz: `persistirAutomaticamente` foi eliminado. A partir de agora, os
> dois canais compartilham o MESMO núcleo de processamento
> (`RespostaFornecedorCoreService.processar`) — a única diferença legítima entre eles
> é a resolução do fornecedor (Web: `fornecedorId` já escolhido no dropdown,
> `ResolvedorFornecedorWebStrategy`; WhatsApp: matching fuzzy de nome + criação
> `PENDENTE_DADOS`/`WHATSAPP_AUTO` quando não encontra, `ResolvedorFornecedorWhatsappStrategy`
> — a mesma lógica desta seção 10.5, só que agora isolada numa classe própria). A
> partir do momento em que o fornecedor está resolvido, tudo mais — parsing, matching,
> classificação, geração de preview, gate sequencial em `cotacao_fornecedor` — é
> idêntico nos dois canais, e a persistência de fato SÓ acontece via
> `POST .../confirmar` (`ConfirmacaoRespostaService.confirmar`), mesmo quando a
> resposta do WhatsApp não tem nenhuma divergência: o operador confirma pela tela de
> Conferência (botão "Conferir resposta do fornecedor") antes de qualquer linha ser
> gravada — não existe mais nenhum atalho de auto-confirmação, nem para o caso
> trivial. `StatusItem.PENDENTE_CONFIRMACAO` deixou de ser gravado por qualquer
> caminho de produção (mantido no enum e no schema por compatibilidade — testes que já
> usavam esse valor como fixture continuam válidos). Detalhe completo em
> `docs/fluxograma-05-resposta-fornecedor-avisos.md`.

### 10.7 Confirmação de recebimento — via Meta Message Template (Prompt 18, 12/08)

> **Superado a partir de 12/08/2026 (Prompt 18).** O texto abaixo (versões 2.0-2.2)
> descrevia uma mensagem de confirmação em **texto livre**. Diagnóstico do Prompt 18
> confirmou, pelo código (não pela doc), que esse envio em texto livre estava
> realmente acontecendo em produção — não era um caso de doc otimista sobre feature
> não implementada. A partir do Prompt 18, o mecanismo de envio trocou de texto livre
> para **Message Template aprovado pela Meta**, mantendo a mesma garantia
> (recibo único por entrada processada, nunca bloqueia o processamento da mensagem se
> o envio falhar) mas mudando radicalmente como o texto é decidido: não é mais uma
> constante Java, é um template cadastrado pelo `ADMIN_PRX` por tenant.
>
> **Desenho final: exatamente 2 templates fixos por tenant — `SUCESSO` e `ERRO`.** O
> "tipo" da mensagem recebida (`Lista de produtos` / `Resposta de fornecedor` /
> `Desconhecido`, quando a 1ª linha não é reconhecida — seção 10.3) **não seleciona
> qual template usar** — vira um parâmetro dinâmico dentro do template escolhido
> (`{{1}} = tipoMensagem`, `{{2}} = detalhe`, ordem fixada em código, nunca editável
> pelo admin). Isso significa que o caso "formato não reconhecido" — que não tem
> nenhum `TipoMensagemWhatsapp` conhecido ainda, já que a classificação falhou antes
> de saber se era tentativa de lista ou de resposta — se resolve de forma limpa como
> só mais um `ERRO`, com `tipoMensagem=Desconhecido`, sem precisar de um caminho de
> texto livre à parte. Se o tenant ainda não cadastrou o template `ERRO`/`SUCESSO`
> correspondente, a decisão de produto é **não enviar nada** (loga e segue) — mesmo
> comportamento adotado uniformemente pra "não cadastrado" e "cadastrado mas
> desativado" (`ativo=false`), e nunca bloqueia o processamento/persistência da
> mensagem recebida em si.
>
> **Desacoplamento (requisito de desenho, não incidental):** a decisão de negócio
> "preciso confirmar sucesso/erro" não conhece o mecanismo concreto de envio — uma
> porta `com.prx.cotacao.notificacao.MensageriaService` (`enviarMensagemSucesso`/
> `enviarMensagemErro(ContextoNotificacao)`) isola isso; `ContextoNotificacao`
> carrega só `tenantId`/`destinatario`/parâmetros de negócio, nada específico de
> WhatsApp Template. A única implementação hoje (`WhatsappTemplateMensageriaService`)
> resolve o template certo por `(tenant_id, resultado)` e fala com a Graph API por
> trás dessa porta — trocar/somar canal no futuro (e-mail, SMS) não tocaria
> `WhatsappWebhookService` nem nenhuma lógica de negócio existente.
>
> Cadastro dos 2 templates por tenant é feito pelo `ADMIN_PRX` numa aba nova dentro do
> detalhe do tenant no Admin PRX (`/admin/tenants/{id}`, ao lado de "Usuários" — ver
> seção 10.8), não mais em constante Java.

**Texto original (v2.0-2.2, mantido para histórico — não reflete mais o comportamento real):**
Sem conduzir conversa, o sistema ainda responde **uma única mensagem de confirmação** por entrada processada — por exemplo, `"✅ Lista recebida e adicionada à sua cotação."`, `"✅ Resposta do fornecedor recebida, aguardando conferência do operador."` (texto ajustado no Prompt 15 — "registrada" deixou de ser verdade a partir da unificação Web/WhatsApp, ver seção 10.6), ou, quando a 1ª linha não é reconhecida (seção 10.3), `"⚠️ Não consegui identificar o formato da sua mensagem. Comece com LISTA_PRODUTOS ou RESPOSTA_FORNECEDOR na primeira linha."`. Isso não é fluxo conversacional (não há passos nem botões), é só um recibo — sem ele, o cliente manda a lista e não tem nenhum sinal de que funcionou.

### 10.8 Componentes técnicos

- **Webhook receiver** (`POST /whatsapp/webhook`) — valida assinatura Meta (`X-Hub-Signature-256`), idempotência por `message_id`.
- **Classificador de mensagem** — marcador explícito na 1ª linha (`LISTA_PRODUTOS`/`RESPOSTA_FORNECEDOR`), normalização de caixa/acento/separador e tolerância a erro de digitação via similaridade de Levenshtein (limiar 0,75); sem marcador reconhecido, formato não reconhecido — sem heurística de conteúdo como fallback (seção 10.3).
- **Reaproveitamento dos services** de parsing/validação e matching já construídos para o canal web (mesma lógica, dois canais de entrada) — incluindo a extensão do matching para nome de fornecedor.
- **Motor de roteamento** — decide anexar à cotação atual ou criar nova, consultando `ultima_atividade_em`.
- **Envio de confirmação via Meta Template** (Prompt 18, 12/08 — ver 10.7) —
  `com.prx.cotacao.notificacao.MensageriaService` (porta, canal-agnóstica) +
  `WhatsappTemplateMensageriaService` (`whatsapp.envio`, único adaptador hoje) resolve
  o template ativo por `(tenant_id, resultado)` na tabela `template_mensagem`
  (`tenant_id NOT NULL` + RLS, `UNIQUE(tenant_id, resultado)`) e chama
  `WhatsappMessageSender.enviarTemplate(...)` → `MetaWhatsappGraphClient` (POST
  `type=template` na Graph API, `components[].parameters[]` posicionais). O método
  antigo de texto livre (`enviar`) foi removido da interface — não sobrou nenhum
  caminho de fallback em texto livre no fluxo de confirmação. `NotificacaoParametrosFactory`
  (`whatsapp.webhook.service`) monta os parâmetros dinâmicos (`tipoMensagem`/`detalhe`)
  pros 5 cenários reais (lista sucesso/erro, resposta sucesso/erro, formato
  desconhecido) sem se repetir num switch gigante no `WhatsappWebhookService`. Falha
  no envio (Graph API fora do ar, template não aprovado, etc.) é capturada e logada no
  adaptador — nunca propaga pro processamento da mensagem recebida, que já foi
  persistido/preview antes do envio da confirmação ser sequer tentado.
- Nenhuma máquina de estados persistida, nenhum botão interativo, nenhum link assinado — removidos do desenho anterior por não serem necessários neste fluxo.

> **Nota (09/08, Prompt 16 — reorganização de pacotes, unificação estrutural de
> respostafornecedor + whatsapp):** puramente organizacional — nenhuma classe mudou de
> comportamento, assinatura pública ou contrato de API, só de pacote. As classes de
> `com.prx.cotacao.cotacao.application`/`.domain`/`.domain.parser` relacionadas a
> processar resposta de fornecedor (núcleo unificado do Prompt 15, entidades
> `CotacaoFornecedor`/`CotacaoProdutoFornecedor`/`ItemConferencia`, e o parser de
> resposta) foram consolidadas em `com.prx.cotacao.cotacao.respostafornecedor`
> (com `dto/` e `parser/` aninhados) — o resto de `cotacao` (comparativo, mapa de
> compra, núcleo de cotação/lista) permanece onde estava, fora do escopo deste prompt.
> `com.prx.cotacao.whatsapp` (antes um pacote único misturando três preocupações) virou
> três subpacotes por motivo de mudança: `whatsapp.webhook` (recepção, assinatura,
> idempotência, simulação para QA), `whatsapp.canal` (classificação de mensagem,
> resolução de fornecedor/identificação por telefone, roteamento resposta/lista) e
> `whatsapp.envio` (cliente Graph API da Meta, envio de mensagem). A dependência entre
> os dois flui numa única direção: `whatsapp.canal.ResolvedorFornecedorWhatsappStrategy`
> depende de `cotacao.respostafornecedor`, nunca o inverso. Duas observações que
> ficaram registradas mas não corrigidas neste prompt (fora de escopo, é reorganização
> de local, não de design): (1) o prompt original desta reorganização assumia a
> existência de uma interface `ResolvedorFornecedorRespostaStrategy` comum às duas
> Strategies (Web e WhatsApp) — ela nunca existiu no código, nem aninhada nem solta, e
> os dois métodos `resolver(...)` têm assinaturas diferentes (`UUID` vs `String`), então
> não foi criada; as duas Strategies continuam como classes concretas independentes,
> só realocadas. (2) `MatchingProdutoService` moveu para `respostafornecedor.parser`
> (instrução explícita do prompt), mas `ParserListaProdutosService` — que fica em
> `cotacao.domain.parser` porque é usado pelo núcleo/comparativo, fora de escopo —
> depende dela; isso introduz uma dependência de "core" para "respostafornecedor" que
> não existia como tal antes da reorganização (antes, ambas viviam no mesmo pacote
> `cotacao.parser`).

> **Nota (09/08, Prompt 17 — extração de core/comparativo/mapacompra/mensagem, com
> subpastas domain/repository/service/dto/web):** continuação do Prompt 16 —
> `com.prx.cotacao.cotacao.application`/`.domain` (os dois pacotes técnicos que
> sobraram concentrando ciclo-de-vida de cotação, comparativo e mapa de compra sob o
> mesmo teto) deixaram de existir. Viraram quatro pacotes-irmãos, cada um com
> subestrutura por papel técnico (só as pastas que fazem sentido para o tamanho do
> pacote — nenhuma pasta vazia por simetria):
> - `cotacao.core` (domain/repository/service/dto/web) — ciclo de vida da cotação:
>   `Cotacao`, `CotacaoProduto`, `CotacaoStatus`, `CanalOrigem`; `CotacaoService`,
>   `CotacaoListaService`, `CotacaoProdutoItemService`, `ProdutoResolverService`,
>   `ParserListaProdutosService` (o parser da lista base, não de resposta —
>   distinto do que já foi para `respostafornecedor.parser` no Prompt 16);
>   `CotacaoController` (só endpoints de criar/listar/buscar/lista/produtos/finalizar).
> - `cotacao.comparativo` (service/dto/web) — `ComparativoService` +
>   `ComparativoController` novo, extraído de `CotacaoController`
>   (`GET /cotacoes/{id}/comparativo`, mesmo path de antes).
> - `cotacao.mapacompra` (domain/repository/service/dto/web) — `CotacaoAjusteManual`,
>   `CenarioSelecionado`; `MapaCompraService`, `CotacaoAjusteManualService`;
>   `MapaCompraController` novo, extraído de `CotacaoController` (`GET/PUT/POST/DELETE
>   /cotacoes/{id}/mapa*`, mesmos paths de antes).
> - `cotacao.mensagem` (só service/ — sem domain/repository/dto próprios, não tem
>   entidade nem DTO específico) — `MensagemService`, `PrecoReferenciaService`.
>
> `ProdutoResolverService` foi para `core` (confirmado por grep: só é consumido por
> `CotacaoListaService` e `CotacaoProdutoItemService`, ambos core).
>
> **Desvio do escopo original, registrado conscientemente:** o prompt descrevia
> `CotacaoController` como tendo só três categorias de endpoint (ciclo-de-vida,
> comparativo, mapa), mas o controller real tinha cinco — 9 endpoints de
> fornecedor/resposta/aviso (`/fornecedores`, `/fornecedores/{id}/resposta*`,
> `/avisos/{id}/resolver`) e 1 de mensagem (`/fornecedores/{id}/mensagem`) chamando
> services de `respostafornecedor`/`mensagem`. Como este prompt proíbe explicitamente
> tocar `cotacao.respostafornecedor` e não pede um `MensagemController` novo, esses 10
> endpoints **continuam em `CotacaoController`** (agora em `cotacao.core.web`) — o
> controller não ficou "só ciclo-de-vida" como o checklist original esperava. Extrair
> um controller de fornecedor/resposta para dentro de `respostafornecedor` (aplicando
> a subestrutura `web/` lá, hoje só tem classes soltas na raiz) fica para um Prompt 18.
>
> `cotacao.core` depende de `cotacao.mapacompra` (`CotacaoService`/`Cotacao` usam
> `CenarioSelecionado`/`MapaCompraService` para finalizar a cotação) e de
> `cotacao.mensagem` (`ComparativoService` usa `PrecoReferenciaService`) — dependências
> que já existiam informalmente (mesmo pacote `application`/`domain`), agora só ficaram
> explícitas como imports entre pacotes-irmãos; nenhuma delas é nova nem foi alterada.

### 10.9 Custo de infraestrutura da Meta

> **Correção 12/08 (Prompt 18):** o parágrafo abaixo (v2.0-2.2) afirmava que o fluxo
> "não usa mensagens interativas com template pré-aprovado". Isso deixou de ser
> verdade a partir do Prompt 18 — a confirmação de recebimento agora É um Message
> Template aprovado pela Meta (ver 10.7/10.8), embora continue sendo uma mensagem de
> **Service** (resposta dentro de 24h a uma mensagem iniciada pelo cliente), não uma
> mensagem iniciada pela empresa — então a conclusão sobre custo (gratuita) continua
> válida, só a premissa de "sem template pré-aprovado" ficou incorreta. A dependência
> de aprovação de template deixou de ser "praticamente eliminada" e passou a ser um
> risco ativo — ver 10.10.

Mesma conclusão da pesquisa original quanto a custo (seção mantida para histórico): desde jul/2025 a Meta cobra por mensagem entregue, e mensagens de **Service** (resposta a mensagem iniciada pelo cliente, dentro de 24h) são gratuitas. Como este fluxo é 100% reativo e ~~não usa mensagens interativas com template pré-aprovado~~ (a simplificação removeu os botões), a dependência de aprovação de template da Meta ~~é praticamente eliminada~~ — resta apenas a verificação inicial do Business Manager e do número, que é um passo único, não recorrente.

### 10.10 Riscos e dependências fora do seu controle

- Verificação do Business Manager e do número (passo único, fora do seu controle de calendário, mas sem repetição a cada mensagem nova como aconteceria com templates).
- Cliente mandando mensagem sem o marcador `LISTA_PRODUTOS`/`RESPOSTA_FORNECEDOR` reconhecido na 1ª linha — tratado pela mensagem de confirmação/erro da seção 10.7, mas vale monitorar a taxa de rejeição nas primeiras semanas para calibrar o limiar de similaridade se necessário.
- Limite inicial de mensagens iniciadas pela empresa por dia — irrelevante aqui, já que o fluxo é 100% respostas a mensagens do cliente, não mensagens iniciadas pela empresa.
- **Novo (12/08, Prompt 18):** os 2 templates (`SUCESSO`/`ERRO`) de cada tenant precisam ser criados e aprovados previamente no Business Manager — passo manual, fora do controle do código, repetido por tenant (não é mais um passo único de plataforma como a verificação inicial do número). Enquanto um tenant não tiver os 2 templates aprovados e cadastrados no Admin PRX, a decisão de produto é não enviar nenhuma confirmação (ver 10.7) — não há fallback em texto livre.

### 10.11 Estimativa de horas

| Item | Horas |
|---|---|
| Webhook Meta + validação de assinatura + idempotência | 4 |
| Cadastro de números de telefone autorizados por cliente | 3 |
| Classificador de mensagem + regra de expiração (48h) | 5 |
| Matching de nome de fornecedor + criação com status PENDENTE_DADOS | 5 |
| Roteamento (anexar à cotação atual / criar nova) | 4 |
| Ajuste no Mapa de Compra para respeitar fornecedor pendente | 3 |
| Confirmação mínima de recebimento via WhatsApp | 2 |
| Testes (classificação, expiração, matching, segurança de número) | 7 |
| Deploy e monitoramento do webhook | 2 |
| **Total módulo WhatsApp** | **35 horas** |


Este módulo está integrado ao escopo do v1 (ver proposta comercial) — não é mais uma fase futura separada. A simplificação do desenho (sem máquina de estados, sem botões) reduziu o custo em relação à estimativa original do bot conversacional, mesmo somando a nova lógica de identificação de tenant e classificação de mensagem.

---

## 11. Módulo futuro: Histórico de Preços e Economia

### 11.1 Objetivo

O protótipo tem duas telas — **Histórico de Preços** e **Economia** — que dependem de
dado acumulado **entre cotações ao longo do tempo** (preço de um produto por
fornecedor em cotações passadas; economia obtida cotação a cotação). No protótipo esse
estado vive inteiramente em `localStorage` do navegador. **O backend atual não persiste
nada disso** — não há tabela nem endpoint equivalente — então essas duas telas ficam
fora do escopo da migração de UI/UX do v1 até este módulo ser construído.

Este módulo registra aqui o desenho para quando for priorizado, para que a lógica do
protótipo (`registrarHistoricoCotacao`, `registrarEconomiaCotacao`, `getHistoricoPrecos`,
`gerarChaveProduto`, `calcularVariacaoHistorica`, `classificarPrecoHistorico`) seja
portada para persistência real em vez de reimplementada às pressas como efeito
colateral de uma tarefa de UI.

### 11.2 Modelo de dados novo

Duas tabelas novas, ambas com `tenant_id` + policy de RLS (regra 1 do CLAUDE.md, sem
exceção):

- **`historico_preco_produto`** — uma linha por (produto, fornecedor, cotação)
  sempre que uma cotação é **finalizada**: `id, tenant_id, produto_id, fornecedor_id,
  cotacao_id, preco_unitario_calculado, capturado_em`. Alimenta a série temporal de
  preço por produto (equivalente a `getHistoricoPrecos()`/`gerarChaveProduto()` do
  protótipo, mas com FK real em vez de chave de string derivada do nome).
- **`historico_economia_cotacao`** — uma linha por cotação finalizada:
  `id, tenant_id, cotacao_id, valor_total_recomendado, economia_potencial,
  percentual_economia, produto_maior_economia_id, produto_maior_economia_valor,
  quantidade_fornecedores, finalizada_em`. Equivalente a
  `registrarEconomiaCotacao()` do protótipo.

Ambas são escritas em background pelo próprio `POST /cotacoes/{id}/finalizar` (mesmo
ponto em que o protótipo chama `registrarHistoricoCotacao()`/`registrarEconomiaCotacao()`
dentro de `confirmarFinalizacao()`) — nunca escritas a partir de uma cotação ainda em
andamento, para não poluir o histórico com preços que podem ser revisados antes de
finalizar.

### 11.3 API nova

- `GET /produtos/{id}/historico-precos` — série temporal de preços do produto por
  fornecedor, para a tela Histórico de Preços (busca + detalhe).
- `GET /economia?page=&size=` — lista paginada de `historico_economia_cotacao` do
  tenant, para a tela Economia (KPIs agregados + histórico expansível por cotação).

Nenhum endpoint existente muda; ambos são endpoints novos e aditivos.

### 11.4 Frontend

Só depois de 11.2/11.3 existirem: portar as telas **Histórico de Preços** (busca de
produto + linha do tempo de preços por fornecedor) e **Economia** (KPI row + lista de
economia por cotação com detalhe expansível, incluindo o recurso de exportar
"Conferência de Nota" em PDF do protótipo, `exportarConfNotaPDF`), seguindo o mesmo
mapeamento de tokens Tailwind e os componentes compartilhados (cards, tabelas) já
construídos para Dashboard/Comparativo/Mapa de Compra.

> **Nota (Prompt 11, 01/08):** a exportação "Conferência de Nota" em PDF (`exportarConfNotaPDF`
> do protótipo) foi adiantada e implementada isoladamente na tabela "Economia de Cotações" já
> existente no Dashboard, a partir dos dados de uma única cotação finalizada já carregados via
> `GET /cotacoes/{id}/comparativo` (`frontend/src/lib/comparativo.ts#detalhamentoPorFornecedor`,
> `frontend/src/lib/conferenciaNotaPdf.ts`) — sem depender de `historico_preco_produto`/
> `historico_economia_cotacao` nem dos endpoints da seção 11.3, que continuam não implementados.
> A técnica de exportação (HTML + `window.print()`, sem biblioteca de PDF) foi portada fiel ao
> protótipo. O restante deste módulo — tela **Economia** com KPIs entre cotações — permanece
> futuro/não iniciado (ver nota abaixo sobre **Histórico de Preços**, já implementado).

> **Nota (Prompt 13, implementação real, 01/08):** ao contrário do desenho original desta
> seção 11.2 (tabela nova `historico_preco_produto` escrita em background na finalização), a
> tela **Histórico de Preços** foi implementada como consulta derivada ao vivo sobre
> `cotacao_produto_fornecedor`/`cotacao_produto`/`cotacao`/`produto` — **sem tabela nova** —
> por decisão de escala do projeto (pequeno varejo, baixo volume de cotações por tenant). Ver
> `HistoricoPrecoService.historico()` (pacote novo `com.prx.cotacao.historico`) e o índice
> `idx_cotacao_finalizada` (migration V19, parcial sobre `cotacao(tenant_id, status,
> finalizada_em DESC) WHERE status = 'FINALIZADA'`), endpoint `GET /historico-precos`.
> O ponto de referência de preço por (produto, cotação finalizada) é a **menor oferta válida**
> daquela cotação para o produto (mesmo critério de `MapaCompraService.ofertaValida()`/
> `menorPreco()`) — não necessariamente o que foi de fato comprado, que pode divergir sob os
> cenários EQUILIBRADA/MELHOR_PRAZO ou um ajuste manual de distribuição; simplificação
> deliberada para manter a consulta derivável por join simples, sem reprocessar o snapshot do
> Mapa de Compra por cotação histórica. A metade **Economia** desta seção (tabela
> `historico_economia_cotacao`, endpoint `GET /economia`) permanece futura/não iniciada, sem
> relação com esta implementação.

### 11.5 Roadmap

Fica registrado como item "Futuro" na seção 13 (Roadmap) — sem prazo do v1, priorizado
sob demanda.

---

## 12. Prompts para construção assistida por IA

Sequência de prompts pensada para ser usada com Claude Code (ou ferramenta equivalente) módulo a módulo, na ordem abaixo. Cada prompt assume que o anterior já foi aplicado.

**Prompt 1 — Scaffold do backend** — ✅ concluído (09/07)
```
Crie um projeto Spring Boot 3 (Java 21) com módulos: web, data-jpa, security,
validation, postgresql driver, flyway. Configure multi-ambiente (application-dev.yml,
application-prod.yml) lendo credenciais de variáveis de ambiente. Estruture pacotes
por feature: auth, tenant, fornecedor, produto, cotacao. Adicione Testcontainers
para testes de integração com Postgres real.
```

**Prompt 2 — Entidades e migrations** — ✅ concluído (09/07, V1-V13; V14/V15 adicionadas depois)
```
Crie as entidades JPA e migrations Flyway para: Tenant, Usuario, Fornecedor,
Produto, Cotacao, CotacaoProduto, CotacaoProdutoFornecedor, FornecedorProduto
(schema abaixo). [colar o modelo de dados da seção 3 deste documento]
Inclua RLS: crie a policy de isolamento por tenant_id em cada tabela sensível
via migration SQL, e um filtro Spring que aplica SET app.current_tenant_id
na conexão a partir do JWT autenticado.
```

**Prompt 3 — Auth multi-tenant** — ✅ concluído (09/07)
```
Implemente autenticação JWT (access 15min + refresh 7 dias) com dois papéis:
ADMIN_PRX (tenant_id nulo, enxerga todos os tenants, bypassa RLS) e
OPERADOR_CLIENTE (tenant_id fixo, restrito por RLS). Endpoints: POST /auth/login,
POST /auth/refresh. Middleware que injeta tenant_id na sessão de banco a cada
requisição autenticada.
```

**Prompt 4 — Parsers e matching (porte do protótipo)** — ✅ concluído (09/07); motor reescrito em 16/07 fiel ao protótipo 14/07 (seção 6.6)
```
Porte para Java as seguintes funções JavaScript deste protótipo [colar o código
de parseListaProdutos, parseLinhaProduto, parseCotacaoFornecedor, normTxt,
extrairPesoVolume, calcSimilaridade, conciliarProdutosCotados, detectarEmbalagem
do arquivo HTML original]. Preserve exatamente o comportamento de regex e scoring.
Escreva como services testáveis: ParserListaProdutosService,
ParserRespostaFornecedorService, MatchingProdutoService. Adicione testes
unitários cobrindo os casos de exemplo do guia de formatação do protótipo.
```

**Prompt 5 — API REST do fluxo de cotação** — ✅ concluído (09/07); fluxo de resposta/confirmação reescrito em 16/07 (seção 6.7)
```
Implemente os endpoints REST descritos na seção 5 deste documento para o
fluxo: criar cotação, enviar lista de produtos, cadastrar/reaproveitar
fornecedor, enviar resposta de fornecedor (retorna itens conciliados +
avisos de divergência), resolver aviso (confirmar embalagem — grave o
snapshot em cotacao_produto_fornecedor.embalagem_qtd_confirmada, NUNCA
sobrescreva produto.embalagem_qtd_sugerida a partir de um único fornecedor,
apenas quando 2+ fornecedores concordarem), gerar mensagem de pedido para
WhatsApp por fornecedor, finalizar cotação.
```

**Prompt 6 — Cenários do Mapa de Compra** — ✅ concluído (09/07)
```
Implemente os 3 algoritmos de distribuição fornecedor→produto descritos na
seção 6.5: Menor Preço, Compra Equilibrada (respeitando pedido mínimo por
fornecedor) e Melhor Prazo. Endpoint GET /cotacoes/{id}/mapa?cenario=X
retornando a distribuição, total por fornecedor, produtos abaixo do mínimo,
e economia comparada ao pior cenário.
```

**Prompt 7 — Frontend Next.js** — ✅ concluído (09/07, migração de UX/UI fiel ao protótipo em 11/07 e 16/07); painel admin (seção 5) não construído
```
Crie as páginas Next.js (App Router, TypeScript, Tailwind) para: Dashboard,
Entrada de Dados (colar lista + form de fornecedores + colar resposta,
replicando o layout e as validações visuais deste protótipo HTML anexado),
Comparativo (tabela + por produto + por fornecedor), Mapa de Compra
(3 cenários + distribuição + botão "copiar pedido WhatsApp" por fornecedor).
Consumir a API via cliente HTTP tipado (openapi-generator ou similar a
partir do contrato do backend).
```

> Toda tela ou componente criado a partir deste prompt passa pela revisão do subagent
> `frontend-ux-designer` antes do merge, que confere fidelidade ao protótipo — hoje
> `"COTA&TESTA - 14.07 - V5.html"` (trocou em relação ao `COTA_TESTE_29_06_-_V5.html`
> original; usar sempre o mais recente) — fonte de verdade de UX do projeto — e
> consistência do mapeamento de tokens Tailwind (cor, espaçamento, tipografia). Ver
> seção 12.1.

**Prompt 8 — Testes de integração e RLS** — ✅ concluído (Testcontainers configurado e em uso; ver seção 7)
```
Escreva testes de integração com Testcontainers cobrindo: fluxo completo de
uma cotação ponta a ponta via API, e um teste específico que tenta ler/gravar
dados de um tenant a partir de um JWT de outro tenant e espera falha (RLS
funcionando). Adicione também testes para os 3 cenários do Mapa de Compra
com dados fixos conhecidos.
```

**Prompt 9 — CI/CD e deploy** — ❌ não iniciado (ver seção 9)
```
Configure GitHub Actions: workflow que roda testes (unitários + integração
com Testcontainers) em todo PR, e workflow de deploy automático para
Railway/Render (backend) e Vercel (frontend) na branch main. Adicione
staging na branch develop. Documente as variáveis de ambiente necessárias.
```

**Prompt 10 — Módulo WhatsApp (desenho simplificado)** — ✅ concluído (09/08, ver seção 10)
```
Implemente o webhook do WhatsApp Cloud API (POST /whatsapp/webhook) com
validação de assinatura e idempotência por message_id. Implemente o
classificador de mensagem descrito na seção 10.3 (marcador LISTA_PRODUTOS
vs RESPOSTA_FORNECEDOR na 1ª linha, com tolerância a erro de digitação),
a regra de expiração de cotação em
andamento por ultima_atividade_em (seção 10.4), e o matching de nome de
fornecedor por similaridade com criação automática em status
PENDENTE_DADOS quando não encontrado (seção 10.5). Reaproveite os services
de parsing e matching já criados no Prompt 4 — não recrie a lógica.
Implemente a mensagem de confirmação mínima (seção 10.7). Escreva testes
simulando payloads de webhook da Meta para: lista sem preço com/sem
cotação em andamento, resposta de fornecedor conhecido, resposta de
fornecedor novo (verifica status PENDENTE_DADOS), mensagem de número não
cadastrado (deve ser descartada), e cotação expirada (deve iniciar uma
nova em vez de anexar).
```

**Prompt 11 — Módulo futuro: Histórico de Preços e Economia** — ❌ não iniciado (ver seção 11)
```
Implemente o módulo descrito na seção 11 deste documento: migrations Flyway +
entidades JPA para historico_preco_produto e historico_economia_cotacao
(ambas com tenant_id + policy de RLS), escritas a partir de
POST /cotacoes/{id}/finalizar (nunca a partir de uma cotação em andamento).
Endpoints GET /produtos/{id}/historico-precos e GET /economia?page=&size=.
Depois, porte as telas Histórico de Preços e Economia do protótipo
(COTA_TESTE_29_06_-_V5.html) consumindo esses endpoints novos, reaproveitando
os componentes de card/tabela e o mapeamento de tokens Tailwind já
construídos nas demais telas.
```

**Prompt 12 — Grid Unificado de Entrada de Dados (Web + WhatsApp)** — ✅ concluído (04/08)
```
Generalize o grid editável hoje exclusivo da tela "Ajuste de Lista" (WhatsApp) para
virar a interface primária de entrada de produtos nos dois canais (Web e WhatsApp),
substituindo o textarea permanente de paste da tela Entrada de Dados. O paste
continua existindo como ação secundária: um modal "Colar do WhatsApp" que importa
o texto colado como itens NOVOS no grid (sempre-append via
POST /cotacoes/{id}/produtos/importar-texto, nunca substitui/mescla um item já
existente — reaproveita o parser da seção 6.1, mas não a lógica de upsert de
POST /cotacoes/{id}/lista, que continua intocada por ser usada também pela ingestão
de mensagens WhatsApp). Adicione POST /cotacoes/{id}/produtos (item manual, aceita
produtoId existente ou nomeProdutoLivre — resolve-ou-cria pelo mesmo pipeline do
parser). Implemente a trava de edição pós-conferência (PATCH lança 409 se o item já
tem cotacao_produto_fornecedor vinculado — ver seção 3.5) e troque a exclusão de
hard-delete/bloqueio por soft-delete incondicional (nova coluna removido_em,
ComparativoService/MapaCompraService/etc. passam a filtrar só itens vivos — ver
seção 3.2). Remova a rota /ajuste-lista como tela própria, dobrando seu gate de
negócio (WhatsApp com lista ainda não revisada) para dentro de /entrada via
renderização condicional. Destaque 3 tipos de erro de linha no grid (sem produto
identificado, formato de texto não reconhecido, unidade fora do padrão).
```

> **Nota (12/08):** entre o Prompt 12 e o Prompt 18 abaixo, houve trabalho real não
> registrado como prompt numerado nesta lista — Admin PRX (CRUD de
> tenants/administradores/usuários, seção 0/5), bootstrap do primeiro `ADMIN_PRX` via
> env vars, refinamento do classificador WhatsApp (matching fuzzy por header), e a
> reorganização de pacotes documentada como "Prompt 16"/"Prompt 17" nas notas da seção
> 10.8 (numeração interna dessas notas, não desta lista). Não reconstruído
> retroativamente aqui — fora do escopo do Prompt 18, registrado só para não sugerir
> que nada aconteceu entre 04/08 e 12/08.

**Prompt 18 — Templates de Mensagem WhatsApp (confirmação via Meta Template API)** — ✅ concluído (12/08)
```
Troque a confirmação de recebimento do webhook WhatsApp (seção 10.7) de texto livre
para Message Templates aprovados pela Meta, com parâmetros dinâmicos, cadastrados por
tenant. Diagnostique primeiro, pelo código (não pela doc), se a confirmação em texto
livre estava realmente sendo enviada hoje. Desenhe uma porta MensageriaService
(enviarMensagemSucesso/enviarMensagemErro) que isola a decisão de negócio do
mecanismo concreto de envio — a única implementação (WhatsApp Template) fica atrás
dela, sem vazar nome de template/idioma/phone_number_id pra quem chama. Exatamente 2
templates fixos por tenant (SUCESSO/ERRO, tenant_id NOT NULL + RLS); o tipo da
mensagem (lista/resposta/desconhecido) vira parâmetro dinâmico dentro do template
escolhido, não uma chave de seleção entre N templates. Tela de cadastro nova aparece
dentro do Admin PRX já existente, no detalhe do tenant, ao lado de "Usuários" — sem
inventar layout novo. Falha no envio nunca bloqueia o processamento da mensagem
recebida em si.
```

### 12.1 Subagents do Claude Code utilizados neste projeto

Construção e revisão deste projeto usam um setup multi-agent no Claude Code — cada
subagent tem escopo fixo e é acionado no estágio de revisão/teste do ciclo de trabalho,
nunca na implementação em si:

| Subagent | Papel |
|---|---|
| `db-schema-guardian` | Revisa entidade JPA/migration/policy de RLS nova ou alterada: isolamento multi-tenant e regra de snapshot de `embalagem_qtd_confirmada`. |
| `parser-porter` | Porta lógica de parsing de lista/resposta de fornecedor e matching por similaridade do protótipo para services Java, preservando comportamento exato. |
| `security-reviewer` | Revisa commits que tocam autenticação, JWT, isolamento multi-tenant, ou o webhook do WhatsApp. |
| `test-writer` | Escreve testes unitários/integração para feature já implementada e funcionando. |
| `deploy-ops` | Cuida de Docker Compose, droplet DigitalOcean, proxy reverso, CI/CD e stack de monitoramento. |
| `frontend-ux-designer` | Revisa telas/componentes de frontend novos ou alterados contra o protótipo validado (fidelidade de layout e interação) e contra o mapeamento de tokens Tailwind, antes do merge. |

---

## 13. Roadmap

| Fase | Escopo | Quando | Status em 12/08 (corrigido — ver notas) |
|---|---|---|---|
| v1 | Sistema web (fluxo de cotação completo) | até 15/08/2026 | ✅ Construído — ver seção 0 |
| v1 | Módulo WhatsApp (entrada de dados simplificada) | até 15/08/2026 | ✅ Construído (09/08); confirmação por Meta Template desde 12/08 (Prompt 18) — ver seção 0/10 |
| v1 | Deploy em produção (droplet DigitalOcean + Caddy + CI/CD + monitoramento) | até 15/08/2026 | ⚠️ Parcial — **correção 12/08**: Caddy/HTTPS e containerização do frontend já prontos; falta CI/CD e monitoramento — ver seção 9 |
| v1 (movido de "Futuro") | Painel Admin PRX (`/admin/tenants`) | — | ✅ **Correção 12/08: esta linha estava desatualizada.** Não é mais "sob demanda/futuro" — já está construído e em produção, incluindo o CRUD de templates de mensagem do Prompt 18. Ver seção 0/5 |
| Futuro | Catálogo de fornecedores parceiros (`fornecedor_produto` já preparado no schema) | sob demanda | — |
| Futuro | Evolução do módulo WhatsApp para fluxo conversacional (botões, menu, link direto pro mapa) — se a operação mostrar essa necessidade | sob demanda | — |
| Futuro | Histórico de Preços e Economia (seção 11) — persistência entre cotações + telas correspondentes do protótipo | sob demanda | ❌ Não iniciado |
