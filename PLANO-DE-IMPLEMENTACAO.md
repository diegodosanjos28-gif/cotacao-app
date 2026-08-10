# Plano de Implementação e Fluxo de Trabalho Multiagente — Sistema de Cotação PRX

**Papel de quem lê este documento:** orquestrador. Você não escreve cada linha de código —
você decide a ordem, aciona os agentes certos, e decide quando algo está "verde" o
suficiente para virar commit. Este documento é o seu roteiro operacional do dia 1 até o
deploy.

---

## 1. Princípios (economia de tokens como pilar, não como detalhe)

Cada regra abaixo existe para eliminar uma forma específica de desperdício de contexto.
Vale ler antes de rodar o projeto, porque toda a estrutura de agentes foi desenhada em
cima delas.

| Princípio | Por quê |
|---|---|
| **Sessão principal implementa; nunca revisa a si mesma.** | Uma sessão que acabou de escrever 200 linhas tem viés de confirmação e contexto poluído para revisar criticamente o que ela mesma fez. Delegar a revisão a um subagent com contexto limpo é mais barato E mais confiável do que pedir "revise seu próprio código" na mesma janela. |
| **Subagent recebe só o que precisa, nunca "vá procurar".** | Um subagent sem instrução específica de arquivo/diff vai explorar o repositório inteiro com Read/Glob antes de conseguir opinar — isso é o maior ralo de tokens em uso de subagent mal configurado. Sempre passe o caminho do arquivo ou o `git diff` no próprio prompt de invocação. |
| **Revisão multiagente roda em paralelo, não em série.** | Quando dois agentes se aplicam (ex: uma migration que também mexe em dado sensível), chame os dois na mesma mensagem. Contexto de cada um continua isolado (não se soma), só o tempo de espera cai. |
| **Tiering de modelo por tipo de julgamento.** | Checagem de padrão mecânico (tem `tenant_id`? tem policy RLS?) roda em Haiku — mais rápido e mais barato, sem perda de qualidade real. Julgamento que exige entender intenção de negócio (portar lógica, revisar segurança, desenhar teste) fica em Sonnet. Ver a coluna "Modelo" na matriz da seção 3. |
| **1 tarefa = 1 commit pequeno.** | Commits grandes escondem regressão e forçam o revisor (humano ou agente) a reconstruir contexto maior do que precisa. Tarefa pequena, ciclo completo, commit, próxima. |
| **Sessão nova por módulo do roadmap, não uma mega-sessão de 8h.** | Contexto acumulado de um módulo já commitado não ajuda o próximo módulo — só ocupa espaço e, depois de horas, degrada a qualidade das respostas (é um limite conhecido de qualquer sessão longa). Feche a sessão (ou rode `/clear`) ao terminar um módulo do plano da seção 5. |
| **`CLAUDE.md` carrega as regras uma vez por sessão — não repita regra de negócio no prompt.** | Ele já é lido automaticamente no início de cada sessão. Repetir "lembra que RLS é obrigatório" em todo prompt é gasto redundante. |

---

## 2. O ciclo de vida de uma tarefa (6 estágios)

Todo Prompt do plano da seção 5 — do maior módulo ao menor ajuste — passa pelos mesmos
6 estágios. É a unidade de trabalho repetível deste projeto.

```
1. PLANEJAR        →  sessão principal, sem subagent (use o modo Plan do Claude Code
                       se a tarefa for grande — é gratuito em termos de edição, só
                       de leitura/raciocínio)
2. IMPLEMENTAR      →  sessão principal escreve o código (ou aciona parser-porter,
                       se for porte de lógica do protótipo)
3. REVISAR          →  subagents especializados, EM PARALELO quando mais de um
   (multiagente)        se aplica (ver matriz da seção 3)
4. TESTAR           →  test-writer (sempre, para qualquer feature de negócio nova)
5. VERIFICAR        →  sessão principal roda os testes (bash), confere veredito
   (gate)               dos subagents — só passa se tudo estiver verde
6. COMMITAR         →  sessão principal, mensagem de commit clara referenciando
                       o Prompt/módulo do plano da seção 5
```

**Regra de bloqueio:** se o estágio 3 (Revisar) ou 4 (Testar) voltar com problema, você
NÃO avança para Commitar. Corrige, roda de novo o estágio que falhou, só então segue.
Isso vale mesmo sob pressão de prazo — é exatamente o tipo de atalho que gera o bug caro
de produção.

---

## 3. Matriz de acionamento (quem entra em qual estágio)

| Situação da tarefa | Estágio 3 (Revisar) | Estágio 4 (Testar) | Modelo |
|---|---|---|---|
| Entidade JPA ou migration nova/alterada | `db-schema-guardian` (obrigatório) | `test-writer` (isolamento RLS) | Haiku (revisor) / Sonnet (testes) |
| Porte de parsing/matching do protótipo | — (a implementação já é o `parser-porter`) | `test-writer` (fixtures do guia de formatação) | Sonnet |
| Auth, JWT, papéis admin/cliente | `security-reviewer` (obrigatório) | `test-writer` | Sonnet |
| Webhook do WhatsApp (assinatura, idempotência) | `security-reviewer` (obrigatório) + `db-schema-guardian` (se criar fornecedor/telefone) | `test-writer` | Sonnet + Haiku em paralelo |
| Tela frontend (Next.js) | — (sem subagent dedicado; revisão manual ou visual) | opcional | — |
| Cenários do Mapa de Compra | — | `test-writer` (dados fixos, saída conhecida) | Sonnet |
| Docker, CI/CD, monitoramento | `deploy-ops` já é quem implementa | manual (smoke test) | Sonnet |

Leitura da tabela: a coluna "Revisar" só lista agente quando há um bounded check real a
fazer. Onde não há (frontend, infra já implementada pelo próprio `deploy-ops`), forçar um
subagent de revisão seria gasto sem retorno — não crie esse passo artificialmente.

---

## 4. Portões de qualidade entre módulos (revisão multiagente entre fluxos)

Além do ciclo por tarefa, existem 6 pontos no roadmap onde vale rodar uma revisão
consolidada, com mais de um agente, antes de seguir para o próximo bloco de módulos:

| Portão | Depois de | Agentes acionados juntos | Critério para seguir |
|---|---|---|---|
| **A — Fundação** | Setup + Entidades + Auth (Prompts 1-3) | `db-schema-guardian` + `security-reviewer` | RLS presente em toda tabela sensível, papéis admin/cliente isolados |
| **B — Lógica de negócio** | Parsers + API + Cenários (Prompts 4-6) | `parser-porter` (checagem final de paridade) + `test-writer` | Testes cobrindo os exemplos do guia de formatação do protótipo, os 3 cenários com dado fixo |
| **C — Frontend** | Telas Next.js (Prompt 7) | — (checagem manual contra o protótipo original) | Fluxo visual bate com o protótipo validado pelo cliente |
| **D — Testes formais** | Suite de integração e RLS (Prompt 8) | `security-reviewer` (revisão final) | Teste de isolamento cross-tenant passando de propósito (tentativa de leitura entre tenants falha) |
| **E — Deploy** | CI/CD e infra (Prompt 9) | `deploy-ops` | Firewall só com 80/443 públicos, Netdata/Dozzle só via Tailscale |
| **F — WhatsApp** | Módulo simplificado (Prompt 10) | `security-reviewer` + `db-schema-guardian` + `test-writer` | Número não cadastrado é descartado; fornecedor novo nasce `PENDENTE_DADOS`; cotação expira em 48h |

---

## 5. Plano sequencial de implementação

Ordem recomendada para seguir como orquestrador, do dia 0 até o deploy. Cada linha é um
ciclo completo (seção 2). Os números de Prompt referenciam a seção 12 da documentação
técnica (`docs/documentacao-tecnica-sistema-cotacao-prx.md`).

### Dia 0 — Setup do projeto e dos agentes (antes do Prompt 1)

1. Crie o repositório e copie `.claude/agents/` + `CLAUDE.md` (deste pacote) para a raiz.
2. Abra o Claude Code na raiz do projeto. Rode `/agents` e confirme que os 5 subagents
   aparecem na Library.
3. Peça um teste vazio: "Liste os subagents disponíveis e confirme que leram o
   `CLAUDE.md`." — isso não gasta quase nada e confirma que o setup está correto antes
   de começar a trabalhar de verdade.
4. Só então comece o Prompt 1.

### Prompts 1-3 — Fundação

| # | Prompt (doc técnica) | Revisar | Testar | Commit quando |
|---|---|---|---|---|
| 1 | Scaffold do backend | — | — | Projeto builda, Testcontainers configurado |
| 2 | Entidades e migrations | `db-schema-guardian` | `test-writer` (isolamento) | RLS em toda tabela sensível |
| 3 | Auth multi-tenant | `security-reviewer` | `test-writer` | Login + isolamento por papel funcionando |

→ **Portão A** antes de seguir.

### Prompts 4-6 — Lógica de negócio

| # | Prompt (doc técnica) | Revisar | Testar | Commit quando |
|---|---|---|---|---|
| 4 | Parsers e matching (porte do protótipo) — usar `parser-porter` na implementação | `parser-porter` (checagem final) | `test-writer` | Comportamento idêntico aos exemplos do guia de formatação |
| 5 | API REST do fluxo de cotação | `db-schema-guardian` (se tocar migration) | `test-writer` | Endpoints principais respondendo com dado real |
| 6 | Cenários do Mapa de Compra | — | `test-writer` | 3 cenários batendo com dado fixo conhecido |

→ **Portão B** antes de seguir.

### Prompt 7 — Frontend

| # | Prompt (doc técnica) | Revisar | Testar | Commit quando |
|---|---|---|---|---|
| 7 | Frontend Next.js | — | opcional | Fluxo visual bate com o protótipo |

*Ponto de decisão:* se o contrato de API do backend já estiver estável, este prompt pode
rodar numa **sessão paralela** (worktree separado) enquanto você segue endurecendo o
backend na sessão principal — não como subagent, como workstream independente (ver
distinção na seção "Quando abrir sessão paralela" do `README.md`).

→ **Portão C** antes de seguir.

### Prompt 8-9 — Testes formais e deploy

| # | Prompt (doc técnica) | Revisar | Testar | Commit quando |
|---|---|---|---|---|
| 8 | Testes de integração e RLS | `security-reviewer` | (é o próprio prompt) | Teste de isolamento cross-tenant falha do jeito certo |
| 9 | CI/CD e deploy | `deploy-ops` implementa | smoke test manual | Pipeline verde, app no ar na droplet |

→ **Portão D**, depois **Portão E**.

### Prompt 10 — Módulo WhatsApp (simplificado)

| # | Prompt (doc técnica) | Revisar | Testar | Commit quando |
|---|---|---|---|---|
| 10 | Webhook + classificador + roteamento | `security-reviewer` + `db-schema-guardian` (paralelo) | `test-writer` | Todos os casos de teste da seção 10.11 da doc técnica passando |

→ **Portão F**. Sistema pronto para o prazo de 15/08.

*Prompt 11 (Histórico de Preços e Economia, seção 11 da doc técnica) é item de
roadmap "Futuro" — fora da sequência de gates do v1, só entra sob demanda.*

---

## 6. Exemplo real de uma sessão (Prompt 4, ponta a ponta)

Para tirar isso do abstrato — como um ciclo completo realmente soa no terminal:

```
Você:  Vamos implementar o Prompt 4 da documentação técnica: portar o parsing e
       matching do protótipo. O arquivo original está em
       /prototipo/cota-teste.html, funções parseLinhaProduto, calcSimilaridade,
       conciliarProdutosCotados, detectarEmbalagem.

       [ESTÁGIO 1 — PLANEJAR: peça um plano curto antes de codar, se a tarefa
       parecer grande o suficiente para valer a pena]

Você:  Use o subagent parser-porter para portar essas funções para
       ParserListaProdutosService, ParserRespostaFornecedorService e
       MatchingProdutoService, seguindo as regras do arquivo dele.

       [ESTÁGIO 2 — IMPLEMENTAR: parser-porter entra, contexto isolado, só faz isso]

Você:  test-writer, escreve os testes desses 3 services usando os exemplos do
       guia de formatação do protótipo como fixture.

       [ESTÁGIO 3+4 — REVISAR/TESTAR: aqui não há revisão de segurança/schema
       (não mexeu em entidade nem auth), então pula direto pro test-writer]

Você:  [roda os testes via bash] ./mvnw test -Dtest=Parser*

       [ESTÁGIO 5 — VERIFICAR: gate manual, você confere o resultado]

Você:  git add . && git commit -m "feat: porta parsing e matching do protótipo
       (Prompt 4)"

       [ESTÁGIO 6 — COMMITAR]
```

Note o que NÃO aconteceu: `db-schema-guardian` e `security-reviewer` nunca foram
chamados nesse ciclo — a tarefa não tocou schema nem auth. Chamá-los aqui seria gasto de
tokens sem propósito. É a matriz da seção 3 fazendo esse filtro por você.

---

## 7. Checklist de acompanhamento

Use isto como lista de progresso real — marque conforme os portões forem sendo
cruzados:

- [ ] Dia 0 — setup dos agentes confirmado
- [ ] Portão A — Fundação (Prompts 1-3)
- [ ] Portão B — Lógica de negócio (Prompts 4-6)
- [ ] Portão C — Frontend (Prompt 7)
- [ ] Portão D — Testes formais (Prompt 8)
- [ ] Portão E — Deploy (Prompt 9)
- [ ] Portão F — WhatsApp (Prompt 10)
- [ ] Sistema no ar, prazo 15/08/2026
