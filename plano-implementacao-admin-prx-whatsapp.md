# Plano de Implementação — Admin PRX + Módulo WhatsApp (Fases 1–6)

**Gerado em:** 02/08/2026
**Autor:** PRX (Nicolas), com apoio de Claude
**Status:** pronto para execução — cada Fase abaixo é um prompt independente para Claude Code
**Fonte:** `documentacao-tecnica-sistema-cotacao-prx.md` (seção 10) + `PLANO-WHATSAPP.md` + decisões novas desta sessão

Este documento **substitui** `PLANO-WHATSAPP.md` como plano de execução ativo — ele
incorpora mudanças de arquitetura que o `PLANO-WHATSAPP.md` ainda não conhecia (telefone
por usuário, Admin PRX como bloqueante, conferência multi-fornecedor navegável). Depois
que a Fase 6 fechar, apague este arquivo — a seção 10 da doc técnica deve ser atualizada
de forma **aditiva** para refletir o estado final (não reescrever, adicionar uma seção
10.12 com o que mudou em relação ao desenho original).

**Nota sobre a ordem:** a versão anterior deste plano colocava a UI de "Ajuste de Lista"
antes do webhook. Isso não funciona na prática — não dá pra construir/testar uma tela que
edita cotações recém-criadas pelo WhatsApp sem existir nenhum dado real nesse estado no
banco. O webhook é quem cria esse dado. Por isso o webhook (Fase 3) agora vem antes das
telas que consomem o que ele produz (Fases 4 e 5).

**Nota sobre a Fase 2 (revisão importante):** o desenho original deste plano criava um
novo valor de enum, `EM_ANDAMENTO_WHATSAPP`, em `cotacao.status`. Esse desenho foi
**abandonado** numa sessão de implementação — parte do código chegou a ser escrita nessa
linha (migration + entidade) mas ficou não commitada e precisa ser revertida. O desenho
atual reaproveita o campo `canal_origem` (`WEB`/`WHATSAPP`), que já existe em `cotacao`
desde a V5, e introduz só um campo novo, `lista_revisada` (boolean, default `TRUE`) —
`FALSE` enquanto uma cotação WhatsApp ainda não passou pela tela "Ajuste de Lista"
(Fase 4). `cotacao.status` permanece com os 4 valores originais (RASCUNHO,
EM_ANDAMENTO, FINALIZADA, CANCELADA) em todas as fases deste plano. Toda menção a
`EM_ANDAMENTO_WHATSAPP` abaixo foi substituída pela combinação `canal_origem=WHATSAPP` +
`lista_revisada`. A Fase 2 também ganhou a tela "Meus Telefones" no escopo — estava
marcada como fora de escopo na primeira versão deste plano, e foi puxada pra dentro a
pedido do usuário, já que o endpoint fica pronto mas inacessível sem ela.

---

## 0. Decisões fechadas nesta sessão

| Decisão | Resolução |
|---|---|
| Telefone autorizado: por tenant ou por usuário? | **Por usuário.** `tenant_telefone_autorizado` (migration V9, já existente) é **removida**. Nova tabela `usuario_telefone_autorizado`. Como nenhum controller/service usa a tabela antiga ainda (confirmado no `PLANO-WHATSAPP.md`), a troca é limpa — sem dado real para migrar. |
| Admin PRX entra em que fase? | **Fase 1, bloqueante.** Nenhuma sessão de WhatsApp real começa antes disso — precisa existir CRUD de tenant/usuário para o cadastro de telefone por usuário fazer sentido além de um seed manual no Zanon. |
| Trabalho não commitado (módulo histórico) | Resolvido fora deste plano — não é mais um bloqueio. |

### Decisões de design que eu (Claude) estou assumindo por padrão

Você não teve tempo de deliberar sobre isso agora, então sigo com a opção mais simples e
sinalizo explicitamente — revise antes de rodar a Fase 2 se quiser mudar:

1. **`cotacao.status` não ganha valor novo.** ~~Suposição original: `EM_ANDAMENTO_WHATSAPP`
   como 5º valor do enum~~ — revisado. O sinal de "cotação WhatsApp ainda não revisada"
   vive em dois campos que já existem ou são simples de adicionar: `canal_origem`
   (`WEB`/`WHATSAPP`, já existente desde a V5) + `lista_revisada` (boolean novo,
   default `TRUE`; cotações WhatsApp nascem `FALSE`). Menos superfície de mudança —
   nenhuma tela que já faz `switch/case` em `cotacao.status` precisa ser tocada.
2. **Transição:** quando o usuário conclui a etapa "Ajuste de Lista" (Fase 4),
   `lista_revisada` muda para `TRUE` — o `status` da cotação nunca muda por causa
   disso, permanece `EM_ANDAMENTO` o tempo todo (igual ao fluxo web). A partir daí ela
   segue o fluxo normal (Conferência → Comparativo → Mapa de Compra) sem distinção de
   canal na lógica de negócio; `canal_origem = WHATSAPP` continua gravado só para
   auditoria/analytics.
3. **`usuario_telefone_autorizado` carrega `tenant_id` desnormalizado** (além de
   `usuario_id`) — mesmo sendo redundante com `usuario.tenant_id` — para manter a
   política RLS simples e consistente com as demais tabelas (`USING (tenant_id =
   current_setting('app.current_tenant_id')::uuid)`), em vez de RLS via subquery/join.
4. **Um usuário pode ter mais de um número** (dono + WhatsApp da loja, por exemplo) —
   mesma cardinalidade N que a tabela antiga tinha por tenant, só que agora por usuário.
5. **O número é único globalmente, não por tenant** — decisão explícita do usuário: "um
   número nunca pode ser repetido para outro usuário, independente do tenant". Índice
   único em `numero_whatsapp` sozinho (não composto com `tenant_id`), diferente do
   padrão usado para nome de fornecedor (que é único por tenant). Já implementado na
   V21 + validação de conflito (409) na camada de service.

Se algum desses pontos não for o que você quer, me avisa antes de rodar a Fase 2 —
é a fase que grava esquema, e reverter migration depois de dado real é sempre mais caro.

---

## 1. Ordem de execução e estimativa

| Fase | Escopo | Depende de | Estimativa |
|---|---|---|---|
| 1 | Admin PRX — CRUD de tenants e usuários | — | 10–12h |
| 2 | Modelo de dados: telefone por usuário (+ tela "Meus Telefones") + `lista_revisada` | Fase 1 | 7–9h |
| 3 | Webhook + classificador + roteamento | Fases 1, 2 | 25–28h |
| 4 | Tela "Ajuste de Lista" | Fase 3 (precisa de dado real com `canal_origem=WHATSAPP` pra existir) | 6–8h |
| 5 | Conferência multi-fornecedor navegável (web + prep WhatsApp) | — (pode ser feita a qualquer momento, em paralelo) | 8–10h |
| 6 | Testes de integração completos + deploy do webhook | Fases 3, 4, 5 | 7–9h |

**Por que o webhook (Fase 3) vem antes das telas (4 e 5) e não depois:** a Fase 4 edita
dados que só existem depois que uma mensagem de WhatsApp real (ou simulada via payload de
teste) passou pelo webhook e criou uma `cotacao` com `canal_origem = WHATSAPP` e
`lista_revisada = FALSE`. Sem isso, a Fase 4 não tem o que exibir nem como validar
visualmente que está funcionando.

**A Fase 5 é a única verdadeiramente independente** — ela melhora a Conferência para
cotações web comuns com múltiplos fornecedores colados manualmente, que já existem hoje.
Pode ser feita em qualquer ponto da sequência, inclusive antes da Fase 1, sem bloquear
nada. Ela só passa a ser *usada pelo* fluxo WhatsApp a partir do momento em que a Fase 3
existe e a Fase 4 direciona o usuário pra lá.

Cada Fase abaixo é **um prompt autocontido** para colar em uma sessão nova do Claude
Code, seguindo o mesmo padrão da seção 11 da doc técnica (diagnóstico obrigatório antes
de qualquer código, escopo explícito, fora de escopo explícito, checklist de verificação).

---

## Fase 1 — Admin PRX (bloqueante)

```
DIAGNÓSTICO OBRIGATÓRIO — antes de escrever qualquer código, faça um inventário e me
apresente por escrito: (a) o que já existe hoje relacionado a ADMIN_PRX no código —
entidades, roles, guards de rota, endpoints — mesmo que incompleto; (b) confirme se os
endpoints POST /admin/tenants e GET/POST /admin/tenants/{id}/usuarios (seção 5 da doc
técnica) já existem no backend; (c) liste o que falta. Não escreva código até eu validar
esse diagnóstico.

ESCOPO:
Implemente o painel Admin PRX completo:

Backend:
- CRUD de tenant: GET/POST/PUT /admin/tenants (campos da seção 3.2: nome_fantasia,
  razao_social, cnpj, status, plano). Apenas ADMIN_PRX acessa (tenant_id nulo no JWT).
- CRUD de usuário dentro de um tenant: GET/POST/PUT /admin/tenants/{id}/usuarios
  (papel OPERADOR_CLIENTE, email, senha inicial gerada ou definida pelo admin).
- Endpoint de reset de senha de usuário do tenant, para suporte.
- Validação: ADMIN_PRX bypassa RLS (role de banco BYPASSRLS, seção 4). Todo outro
  papel não acessa nenhuma rota /admin/*.

Frontend (Next.js):
- Rota protegida /admin (só ADMIN_PRX vê o link/acessa a rota).
- Tela de listagem de tenants com busca simples e status (ATIVO/SUSPENSO/TRIAL).
- Tela de criação/edição de tenant.
- Dentro do detalhe do tenant: listagem e criação de usuários daquele tenant.
- Nenhuma funcionalidade de billing/plano além do campo enum já existente no schema —
  não construa nada de cobrança agora.

FORA DE ESCOPO:
- Qualquer relatório ou dashboard agregado entre tenants (métricas de uso, etc.).
- Onboarding automatizado / self-service signup — cadastro de tenant é sempre feito
  manualmente pelo ADMIN_PRX.
- Qualquer coisa relacionada a telefone/WhatsApp — isso é a Fase 2 deste plano, ainda
  não rode.

GATES:
- security-reviewer obrigatório (rota administrativa que enxerga todos os tenants,
  bypass de RLS).
- db-schema-guardian se qualquer migration for tocada.
- test-writer ao final: teste que confirma que OPERADOR_CLIENTE recebe 403 em toda
  rota /admin/*, e teste de fluxo completo criar tenant → criar usuário → login desse
  usuário funciona e enxerga só o próprio tenant.

CHECKLIST DE VERIFICAÇÃO (antes de considerar a fase concluída):
[ ] ADMIN_PRX consegue criar um tenant novo e um usuário OPERADOR_CLIENTE nele
[ ] Esse usuário faz login e só enxerga dados do próprio tenant (RLS ok)
[ ] OPERADOR_CLIENTE não acessa nenhuma rota /admin/* (403)
[ ] Testes automatizados cobrindo os dois pontos acima passam
```

---

## Fase 2 (revisada) — telefone por usuário + `lista_revisada`, sem novo status

```
CONTEXTO — este prompt substitui o desenho original da Fase 2. Parte do trabalho já foi
implementada numa sessão anterior seguindo o desenho original (enum EM_ANDAMENTO_WHATSAPP)
e precisa ser revertida; outra parte (UsuarioTelefoneAutorizado completo) já está pronta e
correta, só falta passar pelos gates. Leia o diagnóstico abaixo com atenção antes de tocar
em qualquer arquivo — o objetivo aqui é reverter uma parte específica, não recomeçar tudo.

DIAGNÓSTICO OBRIGATÓRIO — antes de qualquer código, confirme por escrito: (a) o estado
atual de CotacaoStatus.java (deve ter 5 valores se a sessão anterior já rodou; volta pra
4); (b) a migration mais recente relacionada a cotacao.status (provavelmente
V22__cotacao_status_em_andamento_whatsapp.sql); (c) confirme que V20/V21 (drop de
tenant_telefone_autorizado, create de usuario_telefone_autorizado) e toda a stack de
UsuarioTelefoneAutorizado (entidade, repository, controller, service, DTOs) já existem e
já implementam GET/POST/DELETE /usuarios/me/telefones — se não existirem, pare e me avise,
esse prompt assume que existem; (d) confirme se algo dessas migrations já rodou contra
algum ambiente persistente (não descartável) — se sim, pare e me avise antes de editar a
migration in-place, o plano abaixo assume que não rodou.

ESCOPO:

1. Reverter CotacaoStatus.java para 4 valores: RASCUNHO, EM_ANDAMENTO, FINALIZADA,
   CANCELADA. cotacao.status NUNCA ganha um 5º valor neste projeto — o sinal de
   "cotação WhatsApp ainda não revisada" vive em campos separados (itens 2 e 3).
2. Apagar a migration que criou EM_ANDAMENTO_WHATSAPP e criar, no lugar, uma migration
   nova (mesmo número de versão, editada in-place — nada rodou contra ambiente
   persistente, confirmado no diagnóstico) que adiciona uma coluna:
   `cotacao.lista_revisada BOOLEAN NOT NULL DEFAULT TRUE`. Comentário na migration:
   sinaliza se o usuário já revisou a lista recebida via WhatsApp antes de seguir para
   a Conferência (tela "Ajuste de Lista", Fase 4). Cotações web nascem TRUE e nunca
   mudam; cotações WhatsApp nascem FALSE (Fase 3 é quem grava isso).
3. Adicionar o campo espelho em Cotacao.java (`listaRevisada`, boolean, default true,
   getter/setter). NÃO expor em CotacaoResponse ainda — quem consome esse campo no
   frontend é a Fase 4 (tela "Ajuste de Lista"), sessão separada; expor a DTO antes da
   tela existir é escopo que não foi pedido aqui.
4. Confirmar (não recriar) que a stack de usuario_telefone_autorizado já implementa a
   regra de unicidade GLOBAL do número — um número nunca pode se repetir para outro
   usuário, independente do tenant: índice único simples em numero_whatsapp (não
   composto com tenant_id, ao contrário do padrão usado pra nome de fornecedor) +
   validação de conflito (409, mensagem clara) na camada de service antes do insert.
   Se isso não estiver correto, corrija — mas é esperado que já esteja.
5. Confirmar (não recriar) que o resto da stack UsuarioTelefoneAutorizado — entidade
   estendendo o mesmo padrão de auditoria por tenant usado em Fornecedor/Cotacao,
   repository, controller, service, DTOs de request/response — já implementa
   GET/POST/DELETE /usuarios/me/telefones com isolamento por dono via usuário
   autenticado (não por tenant inteiro — cada usuário só vê os próprios números).
6. NOVO NESTA REVISÃO — tela "Meus Telefones" no frontend (estava fora de escopo na
   primeira versão deste plano, entrou a pedido do usuário: o endpoint fica pronto mas
   inacessível sem UI). Reaproveitar o mesmo padrão já usado na tela /admin (lista +
   modal de formulário, componentes existentes de layout/tabela/modal do projeto — não
   criar um padrão visual novo):
   - Tipos no arquivo de tipos do frontend: UsuarioTelefone (id, numeroWhatsapp,
     nomeContato nullable, ativo, criadoEm) e UsuarioTelefoneRequest (numeroWhatsapp,
     nomeContato opcional).
   - Client de API: listarMeusTelefones (GET), criarMeuTelefone (POST),
     removerMeuTelefone (DELETE), mesmo padrão dos métodos já usados pra usuários do
     tenant no painel admin.
   - Página nova "Meus Telefones": acessível a qualquer usuário autenticado, mas só faz
     sentido pra OPERADOR_CLIENTE (ADMIN_PRX não tem tenant — backend já rejeita, não
     precisa esconder a rota, só não faz sentido oferecer link de navegação a esse
     papel). Tabela com número/contato/criado em/remover + botão "Adicionar número"
     abrindo modal (campo número com validação de formato E.164 no cliente, campo
     contato opcional). Erro 409 do backend (número duplicado) exibido inline no
     formulário, sem reimplementar a validação de unicidade no cliente.
   - Link "Meus Telefones" na navegação, visível só para OPERADOR_CLIENTE (mesmo
     padrão condicional já usado pro link "Admin", visível só a ADMIN_PRX).
7. IMPORTANTE — dado de teste: como a Fase 3 (webhook) e a Fase 4 (Ajuste de Lista)
   dependem de dados reais para serem construídas/testadas, cadastre via a tela nova
   (ou seed de teste) pelo menos um número de WhatsApp de teste vinculado a um usuário
   real do tenant Zanon antes de encerrar esta fase. Sem isso a Fase 3 não tem como
   simular um payload de webhook autenticado de ponta a ponta.
8. Regra de negócio (só grave a regra, a Fase 3 é quem consome): mensagem recebida de
   número não cadastrado em nenhum usuario_telefone_autorizado é descartada e logada —
   nunca cria cotação ou fornecedor.

FORA DE ESCOPO:
- Webhook, classificador, roteamento — isso é a Fase 3.
- Qualquer alteração na lógica de matching/parsing.

GATES:
- db-schema-guardian obrigatório — migration reescrita (não empilhada) + coluna nova em
  tabela existente. Confirma que lista_revisada não precisa de política RLS própria
  (não é campo de isolamento de tenant) e que V21 já segue o padrão de RLS do projeto.
- security-reviewer obrigatório — cobre especificamente a rota
  /usuarios/me/telefones: confirmar que qualquer usuário autenticado de qualquer
  tenant só vê/mexe nos próprios telefones (por id do usuário autenticado, nunca por
  tenant inteiro), mesmo sem um matcher explícito dedicado na configuração de
  segurança.
- frontend-ux-designer obrigatório — tela nova /meus-telefones: revisão de
  consistência de tokens/spacing/componentes com o resto do app antes do commit (não
  há protótipo original pra essa tela, mesma situação da tela /admin).
- test-writer obrigatório — não existe nenhum teste pra UsuarioTelefoneAutorizado
  ainda. Cobrir: isolamento cross-tenant (mesmo padrão dos demais testes de RLS do
  projeto); conflito 409 em número duplicado; rejeição quando o usuário autenticado
  não tem tenant (ADMIN_PRX); 404 ao tentar remover telefone de outro usuário;
  cotacao.lista_revisada nasce TRUE por padrão e persiste corretamente ao setar FALSE.
- Atualize a seção 3.2 e 3.3 da documentacao-tecnica-sistema-cotacao-prx.md de forma
  ADITIVA: nota "[DEPRECATED — substituída por usuario_telefone_autorizado em
  DD/MM/2026]" no bloco de tenant_telefone_autorizado (mantendo o texto original),
  novo bloco de usuario_telefone_autorizado, nova linha lista_revisada na tabela de
  cotacao, e usuario 1───N usuario_telefone_autorizado no diagrama ER. Não tocar na
  seção 10 — isso é fechamento da Fase 6, não desta fase.

CHECKLIST DE VERIFICAÇÃO:
[ ] Migration aplica limpo em banco de dev com dados existentes do Zanon (sem quebrar
    fornecedor/cotacao/etc já cadastrados)
[ ] cotacao.status permanece com os 4 valores originais — nenhuma tela/query quebrada
[ ] lista_revisada aceita TRUE/FALSE, default TRUE não quebra cotações web existentes
[ ] usuario_telefone_autorizado isolado por RLS (teste cross-tenant negativo)
[ ] Unicidade global do número confirmada (dois usuários de tenants diferentes não
    conseguem cadastrar o mesmo número)
[ ] Tela /meus-telefones funciona ponta a ponta: adicionar, listar, duplicar bloqueado
    com mensagem clara, remover — sem erro de console
[ ] Existe pelo menos um número de teste cadastrado, pronto pra Fase 3 simular payload
[ ] Commit único e pequeno ao final cobrindo a reversão do enum, lista_revisada, a
    tela Meus Telefones e os testes novos
```

---

## Fase 3 — Webhook + classificador + roteamento

```
DIAGNÓSTICO OBRIGATÓRIO — confirme que as Fases 1 e 2 estão de fato aplicadas e
testadas antes de começar, incluindo o número de teste cadastrado ao final da Fase 2.
Liste os services de parsing/matching do Prompt 4 (ParserListaProdutosService,
ParserRespostaFornecedorService, MatchingProdutoService) e confirme a assinatura de
cada um — este prompt REAPROVEITA essa lógica, não recria.

ESCOPO (substitui o Prompt 10 original da doc técnica — a diferença é a identificação
por usuário em vez de tenant):

1. POST /whatsapp/webhook — valida assinatura Meta (X-Hub-Signature-256), idempotência
   por message_id.
2. Identificação: busca o número recebido em usuario_telefone_autorizado (Fase 2).
   Não encontrado → descarta e loga, sem side-effect algum. Encontrado → resolve
   usuario_id e tenant_id (via usuario), esse usuario_id é o criado_por de qualquer
   cotação criada/afetada por essa mensagem.
3. Classificador de mensagem (seção 10.3 da doc técnica — reescrito em 08/2026,
   substituindo a heurística original por "R$" citada aqui: marcador explícito
   LISTA_PRODUTOS/RESPOSTA_FORNECEDOR na 1ª linha, com tolerância a erro de digitação
   via similaridade de Levenshtein).
4. Regra de "cotação em andamento" para fins de roteamento: cotação do MESMO usuario_id
   (não apenas do tenant) com canal_origem=WHATSAPP, status=EM_ANDAMENTO (nunca muda —
   ver Fase 2 revisada) e ultima_atividade_em dentro de 48h. Isso é uma mudança em
   relação à seção 10.4 original (que era por tenant) — agora é por usuário,
   consistente com a decisão da Fase 2. Se dois usuários do mesmo tenant mandarem
   listas por WhatsApp ao mesmo tempo, cada um tem sua própria cotação em andamento,
   sem conflito. O campo lista_revisada NÃO entra nessa checagem — ele só controla se
   a cotação já passou pela tela Ajuste de Lista (Fase 4), é ortogonal a estar "em
   andamento" para fins de anexar mais mensagens.
5. Lista de produtos: sem cotação em andamento do usuário → cria nova com
   status=EM_ANDAMENTO, canal_origem=WHATSAPP, lista_revisada=FALSE,
   criado_por=usuario_id. Com cotação em andamento → adiciona itens a ela.
6. Resposta de fornecedor: mesma lógica de criar/anexar; nome do fornecedor casado por
   similaridade (reaproveitando calcSimilaridade) contra fornecedores do TENANT (não do
   usuário — fornecedor é um cadastro do tenant, compartilhado entre usuários). Não
   encontrado → cria fornecedor novo status=PENDENTE_DADOS, origem_cadastro=WHATSAPP_AUTO.
7. Confirmação mínima de recebimento (seção 10.7, inalterado): uma mensagem de recibo
   por entrada processada.
8. Ferramenta de simulação: crie um endpoint ou script de dev (não exposto em produção)
   que simula um payload de webhook da Meta autenticado, usando o número de teste da
   Fase 2 — isso é o que vai gerar o dado real que a Fase 4 precisa pra existir.

FORA DE ESCOPO:
- Qualquer fluxo conversacional, botão, menu — desenho permanece stateless (seção
  10.1, princípio não muda).
- Edição da lógica de parsing/matching em si — só a camada de identificação e
  roteamento muda em relação ao Prompt 10 original.
- A tela "Ajuste de Lista" e a navegação multi-fornecedor — Fases 4 e 5.

GATES:
- security-reviewer obrigatório (webhook público + fronteira de identificação por
  número).
- db-schema-guardian se qualquer campo novo for necessário (ex: índice de idempotência
  por message_id).
- parser-porter não deveria ser necessário — é extensão de matching existente, não
  porte novo. Se descobrir que precisa portar lógica nova do zero, pare e me avise
  antes de continuar.

CHECKLIST DE VERIFICAÇÃO — todos os casos abaixo precisam de teste automatizado
passando antes de considerar a fase concluída (mesma lista da seção 10.11 da doc
técnica, adaptada para usuário):
[ ] Lista sem preço, com cotação em andamento do usuário → anexa
[ ] Lista sem preço, sem cotação em andamento do usuário → cria nova
[ ] Dois usuários do mesmo tenant mandando listas simultaneamente não se cruzam
[ ] Resposta de fornecedor conhecido (match por nome, nível tenant) → atribui
[ ] Resposta de fornecedor novo → cria com PENDENTE_DADOS/WHATSAPP_AUTO
[ ] Mensagem de número não cadastrado → descartada, logada, sem side-effect
[ ] Cotação expirada (>48h) → inicia nova em vez de anexar
[ ] Webhook: assinatura inválida rejeitada; message_id duplicado é idempotente
[ ] Simulação de dev gera pelo menos uma cotação real no banco com canal_origem=WHATSAPP
    e lista_revisada=FALSE, pronta para a Fase 4 usar
```

---

## Fase 4 — Tela "Ajuste de Lista"

```
DIAGNÓSTICO OBRIGATÓRIO — antes de código: (a) inventarie os componentes de edição de
cotacao_produto que já existem na tela de Entrada de Dados hoje (edição de quantidade,
unidade, nome, resolução de matching com produto_id) — o objetivo é REUSAR esses
componentes, não recriar; (b) exponha o campo lista_revisada em CotacaoResponse (a Fase
2 deixou isso de fora de propósito, é esta fase que consome); (c) use a ferramenta de
simulação da Fase 3 para gerar pelo menos uma cotação real com canal_origem=WHATSAPP e
lista_revisada=FALSE, com itens de lista, antes de começar a construir a tela — não
desenvolva contra dado mockado quando dado real já é possível.

ESCOPO:
Nova etapa no fluxo de cotação, exibida quando canal_origem = WHATSAPP e
lista_revisada = FALSE:

- Tela "Ajuste de Lista": mostra os itens de cotacao_produto criados a partir da(s)
  mensagem(ns) WhatsApp de lista de produtos, com texto_original visível ao lado dos
  campos editáveis (quantidade, unidade, produto_id resolvido pelo matching) — o
  usuário corrige erros de digitação/interpretação do WhatsApp aqui.
- Botão "Concluir ajuste e seguir para conferência" — ao clicar: lista_revisada muda
  para TRUE (o status da cotação NUNCA muda, permanece EM_ANDAMENTO o tempo todo — ver
  Fase 2 revisada), e o usuário é redirecionado para a Conferência de Fornecedores.
- Se a cotação ainda não tiver nenhuma resposta de fornecedor recebida via WhatsApp
  ainda, mostre isso claramente (não force o usuário a ver uma tela de conferência
  vazia sem explicação).

FORA DE ESCOPO:
- Qualquer alteração na lógica de matching/parsing em si (isso já existe, Prompt 4).
- Reabrir "Ajuste de Lista" depois que lista_revisada já virou TRUE — se precisar
  editar a lista depois, use a edição normal da tela de Entrada de Dados que já existe.

CHECKLIST DE VERIFICAÇÃO:
[ ] Cotação criada via WhatsApp aparece com indicador visual diferente no dashboard
    (canal_origem=WHATSAPP + lista_revisada=FALSE)
[ ] Usuário consegue editar cada linha antes de prosseguir, testado contra dado real
    gerado pela simulação da Fase 3
[ ] Ao concluir, lista_revisada muda corretamente para TRUE e navegação leva à
    Conferência
```

---

## Fase 5 — Conferência multi-fornecedor navegável (web + preparo WhatsApp)

```
DIAGNÓSTICO OBRIGATÓRIO — antes de código: descreva por escrito como a tela de
Conferência do Fornecedor funciona hoje (um fornecedor por vez? lista com todos
simultaneamente? como o usuário troca de fornecedor hoje?). Isso é essencial porque
você não me deu esse contexto e o resto do prompt depende de saber o ponto de partida
real — não assuma, leia o código da tela atual primeiro.

Esta fase não depende de nenhuma outra e pode ser feita em paralelo a qualquer momento
da sequência — ela usa dados de cotações web comuns com múltiplos fornecedores colados
manualmente, que já existem hoje.

ESCOPO:
Adicione ao painel de Conferência (usado tanto no fluxo web quanto, a partir da Fase 3,
no fluxo WhatsApp) a capacidade de navegar sequencialmente entre as respostas de
fornecedor pendentes de auditoria dentro de uma mesma cotação:

- Barra/indicador de progresso: "Fornecedor 2 de 5" (ou equivalente), com navegação
  anterior/próximo.
- Cada fornecedor mantém seu próprio estado de resolução de divergências — navegar
  entre eles não perde nem aplica mudanças não confirmadas.
- Ordem de navegação sugerida: fornecedores com divergência pendente primeiro, depois
  os já 100% conferidos (você decide a métrica exata de "pendente" com base no que
  encontrar no diagnóstico — documente a decisão).
- Isso deve funcionar igual para uma cotação criada pela web com múltiplas respostas
  coladas manualmente — não é exclusivo do canal WhatsApp, é uma melhoria geral que o
  WhatsApp só passa a depender dela a partir da Fase 3/4.

FORA DE ESCOPO:
- Qualquer mudança na lógica de detecção de divergência em si (seção 6.4) — só a
  navegação/UX entre fornecedores já resolvidos e pendentes.

CHECKLIST DE VERIFICAÇÃO:
[ ] Em uma cotação web com 3+ fornecedores colados, dá pra navegar entre eles sem
    perder progresso
[ ] Indicador de "quantos faltam conferir" está correto
[ ] Nenhuma regressão na tela de conferência de fornecedor único (caso mais comum
    hoje continua funcionando igual)
```

---

## Fase 6 — Testes de integração completos + deploy do webhook

```
ESCOPO:
- Suíte de testes de integração (Testcontainers) cobrindo o fluxo ponta a ponta:
  mensagem WhatsApp de lista → cotação canal_origem=WHATSAPP/lista_revisada=FALSE →
  Ajuste de Lista (lista_revisada=TRUE) → mensagens de fornecedores → Conferência
  multi-fornecedor → conclusão → Comparativo/Mapa de Compra idênticos ao fluxo web a
  partir desse ponto.
- Deploy do endpoint de webhook em produção: HTTPS, registro no Meta Business Manager
  (passo manual, fora do controle de calendário — confirme separadamente se já foi
  iniciado, é o único bloqueio externo real, seção 10.10 da doc técnica).
- Monitoramento: garanta que falhas no webhook (assinatura inválida, erro de parsing)
  aparecem no Sentry com contexto suficiente para debugar sem acessar o servidor.

CHECKLIST DE VERIFICAÇÃO:
[ ] Todos os casos de teste da Fase 3 (webhook) + o fluxo ponta a ponta acima passam
[ ] Webhook responde no domínio de produção com HTTPS válido
[ ] Erro simulado no webhook aparece no Sentry em menos de 1 minuto
```

---

## Notas finais

- Trate cada Fase como sessão nova do Claude Code, seguindo o padrão já validado no
  seu `CLAUDE.md` (sessão por módulo).
- Sequência obrigatória: 1 → 2 → 3 → 4 → 6. A Fase 5 é a única solta — encaixe onde
  for mais conveniente, inclusive antes da Fase 1, sem prejuízo pra nada.
- Se ao longo da Fase 1 ou 2 você decidir mudar alguma das 4 suposições de design da
  seção 0, pare, ajuste este documento primeiro, e só depois rode o prompt da fase.
