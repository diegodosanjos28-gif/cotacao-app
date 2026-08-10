# Plano de QA — Sistema de Cotação PRX

Cenários de teste manual/exploratório cobrindo todos os fluxos implementados até
2026-07-17 (inclui o refactor de fluxo sequencial de fornecedor + Conferência +
design system, ver `docs/PLAN REFACTOR.md`). Complementa (não substitui) a suíte
automatizada já existente em `backend/src/test` (parsers, matching, mapa de compra,
RLS, auth, CORS) — os cenários aqui focam em comportamento ponta a ponta, UI e casos
de borda que teste unitário não cobre bem.

> **Nota (10/08/2026):** o módulo WhatsApp (seção 0.2 abaixo dizia "zero código" até
> esta revisão) foi implementado e está funcional desde 09/08 — ver
> `docs/documentacao-tecnica-sistema-cotacao-prx.md` seção 10 e
> `docs/fluxograma-05-resposta-fornecedor-avisos.md` para o comportamento completo.
> Cobertura automatizada existe em `backend/src/test/java/com/prx/cotacao/whatsapp/`
> (assinatura, webhook end-to-end, classificador, resolução de fornecedor, roteamento
> de resposta) e `POST /dev/whatsapp/simular` permite exercitar o webhook real fora do
> perfil `prod` (ver `docs/qa/scripts/`). Este documento ainda não tem cenários manuais
> dedicados ao canal WhatsApp em si (só aos parsers reaproveitados, que já eram
> cobertos) — os itens abaixo que citavam WhatsApp como "não alcançável"/"fora de
> escopo" foram corrigidos, mas não foi adicionada uma seção nova de cenários
> ponta-a-ponta pelo canal; tratar como lacuna a fechar, não como ausência de feature.

## 0. Escopo

### 0.1 O que existe para testar hoje

Web app completo (login → criar cotação → lista de produtos → adicionar fornecedor
um de cada vez, com gate sequencial → colar resposta → Conferência (preview
OK/Atenção/Revisar) → confirmar (persiste) → repete para o próximo fornecedor →
comparativo → mapa de compra → finalizar). Multi-tenant com RLS. Módulo WhatsApp
pronto (webhook, classificador, roteamento — mesmo núcleo de preview/Conferência do
canal Web, ver seção 10 da doc técnica). Painel admin (`/admin/tenants`) também existe
hoje (fora do escopo desta revisão de doc — não verificado aqui). Sem Histórico de
Preços/Economia.

### 0.2 Fora de escopo — não testar como bug se não funcionar

Estes itens **não estão implementados**. Se um teste exploratório "descobrir" que não
funcionam, não é um bug novo — é escopo futuro já documentado. Ver seção 11 para a
lista completa de referência rápida antes de abrir qualquer achado.

- Histórico de Preços e Economia — nenhuma persistência entre cotações.
- Mover produto manualmente entre fornecedores no Mapa de Compra — somente leitura.
- Persistência de rascunho entre preview e confirmação — fechar o modal de
  Conferência sem confirmar descarta o preview (textarea incluída); reabrir exige
  colar a resposta de novo e reprocessar (decisão B do plano de refactor).
- `POST /produtos` — catálogo só cresce via matching automático, sem criação direta.

### 0.3 Ambiente de teste

Backend `mvn spring-boot:run -Dspring-boot.run.profiles=dev` contra Postgres local
(porta conforme `application-dev.yml`), frontend `npm run dev`. Usuário de teste e
detalhes de setup: ver memória de projeto / sessões anteriores. Cada seção assume que
existe pelo menos 1 tenant com 1 usuário `OPERADOR_CLIENTE` ativo.

### 0.4 Convenção

Cada caso: **ID**, pré-condição, passos, resultado esperado. IDs por módulo:
`AUTH`, `DASH`, `ENT` (Entrada de Dados), `CMP` (Comparativo), `MAPA`, `ALR` (Alertas),
`SEC` (multi-tenant/segurança), `ERR` (erros genéricos), `A11Y`, `PARSE` (parsers/
matching), `BIZ` (regras de negócio de cotação).

---

## 1. Autenticação e Sessão (AUTH)

**AUTH-01 — Login com credenciais válidas**
Passos: acessar `/login`, preencher email/senha de um usuário ativo, submeter.
Esperado: redireciona para `/`; tokens gravados em `localStorage`
(`cotacao.accessToken`, `cotacao.refreshToken`).

**AUTH-02 — Login com senha errada**
Passos: email válido + senha incorreta.
Esperado: 401, mensagem "Credenciais inválidas" exibida abaixo do campo senha; nenhum
token salvo.

**AUTH-03 — Login com e-mail inexistente**
Esperado: mesma mensagem "Credenciais inválidas" do AUTH-02 (**verificar que a
mensagem é idêntica** — o backend não deve diferenciar e-mail inexistente de senha
errada, para não permitir enumeração de usuários).

**AUTH-04 — Login com usuário inativo (`ativo=false`)**
Pré-condição: usuário existente com `ativo=false` no banco.
Esperado: mesma mensagem "Credenciais inválidas" (indistinguível de AUTH-02/03).

**AUTH-05 — Campos vazios no login**
Passos: submeter formulário com email e/ou senha vazios.
Esperado: bloqueado pela validação HTML5 nativa (`required`) — nenhuma chamada de
rede disparada.

**AUTH-06 — Login sem rate limiting**
Passos: tentar login com senha errada 20+ vezes seguidas em poucos segundos.
Esperado (comportamento atual, não é bug a corrigir nesta rodada): nenhum bloqueio,
captcha ou atraso — todas as tentativas retornam 401 normalmente. Documentar como
achado de segurança conhecido se for reportar, não como regressão nova.

**AUTH-07 — Expiração do access token em uso (15 min)**
Passos: logar, aguardar >15min (ou forçar expiração), fazer qualquer ação que dispare
fetch autenticado.
Esperado: primeira resposta 401 dispara `/auth/refresh` automaticamente; se o refresh
token ainda for válido, a requisição original é reenviada com o novo access token sem
o usuário perceber (sem tela de erro, sem redirecionamento).

**AUTH-08 — Múltiplas requisições simultâneas com token expirado**
Passos: forçar expiração do access token; disparar 2+ ações que fazem fetch ao mesmo
tempo (ex: trocar de aba rápido em Comparativo enquanto Mapa ainda carrega).
Esperado: apenas **uma** chamada a `/auth/refresh` é feita (deduplicada via
`refreshInFlight`), todas as requisições pendentes reusam o resultado.

**AUTH-09 — Refresh token expirado ou inválido**
Passos: com refresh token expirado/corrompido, forçar um 401 de access token.
Esperado: `logout()` roda — tokens limpos, redirect duro para `/login` via
`window.location.href` (não `router.push`); qualquer chamada pendente rejeita com
"Sessão expirada".

**AUTH-10 — Replay de refresh token já usado**
Passos: usar um refresh token, guardar o valor antigo (não o novo emitido), tentar
usá-lo de novo em `/auth/refresh`.
Esperado: 401 "Token já utilizado — todos os tokens foram revogados"; **todas** as
sessões desse usuário são revogadas (mesmo a legítima que gerou o novo token) — ao
tentar usar o token novo válido em seguida, também deve falhar, pois a replay
detection revoga em massa por `usuario_id`.

**AUTH-11 — Acessar rota protegida sem estar logado**
Passos: limpar `localStorage`, navegar direto para `/`, `/cotacoes/{id}/entrada` etc.
Esperado: `AuthGuard` redireciona para `/login` (tela em branco momentânea antes do
redirect — sem spinner).

**AUTH-12 — Logout**
Passos: clicar "Sair" na NavBar.
Esperado: sem diálogo de confirmação; tokens limpos; redirect duro para `/login`.

**AUTH-13 — JWT_SECRET curto/ausente no boot (config, não runtime)**
Não testável via UI — cenário de deploy/config: confirmar em code review/CI que o
boot falha (`IllegalStateException`) se `app.jwt.secret` tiver menos de 32 bytes.

---

## 2. Dashboard (DASH)

**DASH-01 — Lista vazia (tenant novo)**
Pré-condição: tenant sem nenhuma cotação.
Esperado: linha "Nenhuma cotação ainda. Crie a primeira acima."; nenhum stat card
exibido (cards só aparecem com `cotacoes.length > 0`).

**DASH-02 — Criar cotação com título válido**
Passos: preencher "Título da nova cotação", clicar "Nova cotação".
Esperado: redireciona para `/cotacoes/{novaId}/entrada`; nova cotação nasce
`status=RASCUNHO`, `canalOrigem=WEB`.

**DASH-03 — Criar cotação com título vazio/só espaços**
Passos: deixar campo vazio ou só espaços, clicar "Nova cotação".
Esperado: no-op silencioso — nenhuma requisição, nenhum feedback visual de erro
(**achado de UX conhecido**, não crítico: usuário não sabe por que nada aconteceu).

**DASH-04 — Stats agregados com múltiplas cotações**
Pré-condição: 2+ cotações com itens/comparativo variados.
Esperado: "Cotações" = contagem total; "Em andamento" = só as `EM_ANDAMENTO`;
"Economia potencial total" = soma de `totalEconomia` de todas; "Produtos sem
cotação" = soma de itens sem oferta válida em qualquer cotação, tom âmbar se >0.

**DASH-05 — Expandir/recolher linha de cotação**
Passos: clicar no chevron de uma linha.
Esperado: `aria-expanded` alterna corretamente; painel mostra economia potencial,
produtos cotados (X/Y), fornecedor mais competitivo, e link para a entrada; se a
cotação não tem itens, mostra "Nenhum produto adicionado a esta cotação ainda."

**DASH-06 — Uma cotação falha ao carregar comparativo (rede)**
Cenário difícil de forçar manualmente, mas documentar comportamento esperado: como o
carregamento usa `Promise.allSettled`, uma falha isolada vira `[]` para aquela
cotação sem quebrar a tabela inteira.

---

## 3. Entrada de Dados (ENT)

### 3.1 Lista de produtos

**ENT-01 — Colar lista no formato padrão**
Passos: colar `15un sazon legumes 60g` + `2cx leite integral 1l`, "Adicionar itens".
Esperado: 2 itens processados; cada linha mostra o texto original verbatim + rótulo
verde "produto reconhecido" ou âmbar "não identificado no catálogo"; status da
cotação vira `EM_ANDAMENTO`.

**ENT-02 — Linha sem unidade reconhecida**
Passos: colar `3 caixotes de leite` (unidade "caixotes" não está no dicionário —
`caixa`/`caixas`/`cx`/`cxa` SÃO reconhecidos desde o pacote de ajustes pós-call,
A.3, mas "caixote" não).
Esperado (comportamento real do parser, não um bug): quantidade é **descartada** —
vira `quantidade=1`, `unidade=un`, e o nome do produto usado para matching é a
**linha inteira** ("3 caixotes de leite"), prejudicando a qualidade do match.
Confirmar que o item aparece na lista processada mesmo assim, com `matched`
provavelmente `false` — o fallback nunca rejeita a linha.

**ENT-02b — Dicionário de unidades ampliado (pacote de ajustes pós-call, A.3)**
Passos: colar uma linha para cada uma das novas unidades aceitas: `1fardo arroz`,
`2galao agua mineral`, `3garrafa vinho`, `4saco cimento`, `5display chiclete` (também
cobertos: `cento`, `g`/`gr`/`grama`, `mililitro`, `frasco`, `lata`, `pote`, `rolo`,
`tambor`, `bandeja`, `kit` — ver tabela completa no dicionário `UNIT_ALIASES` do
backend e `UNIDADES`/`validarLista.ts` no frontend, que precisam bater 1:1).
Esperado: nenhuma das 5 linhas gera aviso de formato incorreto (nem no hint
client-side, nem via matching real no backend); `"lt"`/`"l"` continuam mapeando para
litro (não "lata"), prioridade mantida de propósito.

**ENT-03 — Linha que é só um número**
Passos: colar uma linha contendo apenas `42`.
Esperado: linha descartada silenciosamente — não vira item nenhum, nem "não
identificado".

**ENT-04 — Linha sem número no início**
Passos: colar `leite integral` (sem quantidade).
Esperado: vira item com `quantidade=1`, `unidade=un`, nome = linha inteira.

**ENT-05 — Vírgula decimal na quantidade**
Passos: colar `2,5kg arroz`.
Esperado: quantidade interpretada como `2.5` (vírgula convertida para ponto).

**ENT-06 — Submissão com textarea vazio**
Passos: clicar "Adicionar itens" sem digitar nada.
Esperado: no-op silencioso, sem requisição nem erro visível.

**ENT-07 — Enviar lista depois da cotação finalizada**
Pré-condição: cotação já `FINALIZADA` (ver BIZ-04).
Passos: tentar colar nova lista.
Esperado: 409 "Cotação finalizada não aceita novos itens", erro exibido no banner da
página.

**ENT-08 — Reenviar lista na mesma cotação (não finalizada)**
Passos: enviar uma lista, depois enviar outra lista de produtos diferente na mesma
cotação.
Esperado: itens são **acrescentados** (não substituem), preservando ordem crescente —
sem guarda de idempotência.

**ENT-08a — Linha com erro destacada em amarelo dentro do textarea (pacote de ajustes
pós-call, A.1)**
Passos: colar uma lista com pelo menos 1 linha inválida (ex.: `leite integral` sem
quantidade, ou uma linha muito curta) no meio de linhas válidas.
Esperado: a linha inteira com erro fica com fundo amarelo translúcido DENTRO do
textarea (overlay atrás do texto, `bg-wa/20`), além da lista de avisos já existente
abaixo (não removida); o destaque acompanha o scroll vertical do textarea (overlay
sincronizado via a mesma função do gutter de numeração); rolagem horizontal em linha
muito longa não quebra o alinhamento (`wrap="off"` preservado). Colar texto puro via
Ctrl+V continua funcionando sem fricção.

**ENT-08b — Textarea da lista não cresce com o conteúdo (pacote de ajustes pós-call,
A.2)**
Passos: colar uma lista bem longa (150+ linhas) no textarea da lista de produtos.
Esperado: o textarea mantém altura fixa (`max-h-[60vh]`) com scroll interno próprio;
a página ao redor (título, "Guia de formatação", rodapé com "Confirmar Lista")
**não** cresce nem empurra o layout — sem isso, o container só tinha `min-h`/
`overflow-hidden` sem um teto real, e o textarea crescia sem limite (achado desta
leva, verificado via reprodução isolada da mesma cadeia de classes).

### 3.2 Cadastro de fornecedores

**ENT-09 — Criar fornecedor com todos os campos**
Passos: "+ Novo", preencher nome, prazo, condição de pagamento, pedido mínimo,
observações, salvar.
Esperado: fornecedor aparece na sidebar e no bloco de resposta com todos os dados;
`status=ATIVO`, `origemCadastro=MANUAL`.

**ENT-10 — Criar fornecedor só com nome (demais campos opcionais)**
Esperado: salva normalmente; campos ausentes mostram "—" no bloco de resposta.

**ENT-11 — Nome vazio/só espaços**
Passos: deixar nome em branco, tentar salvar.
Esperado: bloqueado (`required` HTML5 + guarda JS `!dados.nome.trim()`), sem
requisição.

**ENT-12 — Pedido mínimo negativo**
Passos: digitar `-50` no campo Pedido Mínimo (R$).
Esperado (achado a confirmar): o input `type=number min=0` não bloqueia
digitação/submissão de negativo de forma confiável em todos os navegadores, e não há
validação JS customizada nem `@Positive`/`@DecimalMin` no backend
(`FornecedorRequest`) — testar se um valor negativo é aceito e persistido sem erro.
Se aceito, registrar como achado (validação ausente ponta a ponta).

**ENT-13 — Editar fornecedor existente**
Passos: "Editar dados" em um bloco de resposta, alterar prazo/condição, salvar.
Esperado: modal reabre com dados atuais pré-preenchidos (via `key` que força remount);
alteração refletida imediatamente na sidebar e no bloco (upsert local por id).

**ENT-14 — Excluir (inativar) fornecedor**
Pré-condição: usar `DELETE /fornecedores/{id}` (não há botão de exclusão na UI
documentada — validar se a ação existe visualmente; se não existir botão, marcar
como gap de UI e testar via API diretamente).
Esperado: soft-delete, `status=INATIVO`; fornecedor não aparece mais em
`GET /fornecedores` (exclui `status != INATIVO`).

**ENT-15 — Auto-seleção de fornecedor recém-criado**
Passos: no slot vazio de "Adicionar fornecedor", digitar um nome que não bate com
nenhum fornecedor existente e escolher "+ Cadastrar novo fornecedor" (abre
`FornecedorFormModal` com o nome pré-preenchido); salvar.
Esperado: o fornecedor é criado, imediatamente adicionado à cotação
(`POST .../fornecedores` encadeado) e o bloco de resposta dele já aparece pronto para
uso, sem recarregar a página.

**ENT-16 — Fornecedor `PENDENTE_DADOS` (hoje alcançável via WhatsApp)**
Nota: desde o módulo WhatsApp (09/08), `ResolvedorFornecedorWhatsappStrategy` cria
este status automaticamente quando o nome extraído da mensagem não casa (por
similaridade) com nenhum fornecedor já cadastrado do tenant — via
`POST /dev/whatsapp/simular` fora do perfil `prod`, ou seed direto no banco. Confirmar
que o ícone `⚠` aparece na `FornecedoresSidebar` (cadastro geral, ainda existe fora do
fluxo sequencial) com `role="img"`/`aria-label` corretos (ver A11Y-01), que o mesmo
ícone dentro do bloco de resposta da cotação só tem `title` (ver A11Y-02), e que esse
fornecedor é **excluído** do cenário Compra Equilibrada no Mapa (ver MAPA-11).

**ENT-17 — Gate sequencial: "+ Adicionar Fornecedor" bloqueado até confirmar o atual**
Pré-condição: cotação com 1 fornecedor já adicionado, ainda `PENDENTE` ou
`PROCESSADO` (resposta processada mas não confirmada na Conferência).
Passos: observar o botão "+ Adicionar Fornecedor"; tentar contornar chamando
`POST /cotacoes/{id}/fornecedores` direto na API com outro `fornecedorId`.
Esperado: botão desabilitado client-side com a dica "Processe a cotação atual para
liberar o próximo fornecedor."; a chamada direta à API recebe 409 "Processe e
confirme a cotação do fornecedor atual antes de adicionar outro." (backstop
server-side em `CotacaoFornecedorService.adicionar` — não é só disable de UI).

### 3.3 Resposta de fornecedor — preview e Conferência

**ENT-18 — Processar resposta gera preview, não persiste ainda**
Passos: colar `Sazon Legumes 60g - R$ 4,89` + `Leite Integral 1L - sem estoque` no
bloco do fornecedor atual, clicar "Processar Cotação".
Esperado: `POST .../resposta` roda parser+matching+classificação mas **não** grava
nada em `cotacao_produto_fornecedor`; único efeito colateral é
`cotacao_fornecedor.status -> PROCESSADO`; `ConferenciaModal` abre automaticamente
com os contadores Total/OK/Atenção/Revisar corretos.

**ENT-19 — Fechar a Conferência sem confirmar descarta o preview**
Passos: com o modal de Conferência aberto, clicar "Fechar" (ou clicar fora, ou Esc).
Esperado: modal fecha sem chamar `/confirmar`; nada foi persistido; textarea e
preview são perdidos — reabrir o bloco do fornecedor exige colar a resposta de novo
e clicar "Processar Cotação" outra vez (decisão B do plano — sem estado de
rascunho).

**ENT-20 — Item OK/Atenção não mostra controles de resolução**
Pré-condição: preview com pelo menos 1 item `OK` e 1 `ATENCAO` (ex.: marca diferente
da lista base).
Esperado: linha mostra o motivo em texto (`BRAND_CHANGED` → "Marca diferente da
lista base") mas sem radios/campo de edição — só linhas `REVISAR` têm
`ResolucaoInline`.

**ENT-21 — Item Revisar por `MULTIPLE_OPTIONS`**
Passos: colar 2 linhas de resposta que casam melhor com o mesmo item base da
cotação (ex.: duas variações de embalagem do mesmo produto, ambas com score alto).
Esperado: status `REVISAR`, motivo "Fornecedor enviou múltiplas opções"; um radio por
candidato, cada um com preço; selecionar um usa `tipo=SELECIONAR_CANDIDATO`.

**ENT-21b — Opção não selecionada de `MULTIPLE_OPTIONS`: Excluir / Adicionar como
novo item (pacote de ajustes pós-call, B.1)**
Pré-condição: mesmo cenário de ENT-21 (2+ candidatos no mesmo item), um deles já
selecionado como vencedor.
Passos: no candidato NÃO selecionado, clicar "Excluir" — depois desfazer e clicar
"Adicionar como novo item" em outro candidato não selecionado, preencher texto/preço
(pré-preenchidos com os dados originais, editáveis), "Salvar", confirmar.
Esperado: "Excluir" é só feedback visual (o candidato já não seria persistido de
qualquer forma); "Adicionar como novo item" gera `tipo=ADICIONAR_CANDIDATO_A_LISTA`
com `itemBaseId` (item de origem) + `textoOriginalExtra` (candidato original) +
`textoOriginalSelecionado`/`precoInformado` (valores finais, podem estar editados);
ao confirmar, cria um `CotacaoProduto` novo na lista base sem tocar no item original;
se o item original (`MULTIPLE_OPTIONS`) não tiver `SELECIONAR_CANDIDATO` próprio
nesta mesma submissão, a confirmação inteira é bloqueada (400) mesmo com o spin-off
preenchido — resolver o item original continua obrigatório.

**ENT-22 — Item Revisar com candidato único (ex.: `WEIGHT_CHANGED`/`PACKAGE_QTY_ADDED`)**
Passos: colar resposta para um item cuja gramagem diverge da lista base (mesma
dimensão, valor diferente).
Esperado: status `REVISAR`, motivo "Gramagem diferente da solicitada"; 1 radio só
(o próprio candidato conciliado); aceitar usa `tipo=ACEITAR_SUGESTAO`.

**ENT-23 — Editar manualmente um item Revisar**
Passos: em qualquer item `REVISAR`, clicar "Editar manualmente", preencher
descrição + preço, "Salvar".
Esperado: exige os dois campos não vazios e preço > 0 (validação client-side, sem
chamada de API se inválido); ao confirmar, `tipo=EDITAR_MANUAL` é o único caminho
que aceita preço/texto arbitrário do cliente — os demais tipos sempre usam o
candidato recomputado pelo servidor.

**ENT-24 — Marcar "Sem oferta deste fornecedor"**
Passos: em um item `REVISAR`, clicar "Sem oferta deste fornecedor".
Esperado: `tipo=SEM_OFERTA`; contador ao vivo do modal conta esse item como
resolvido (some da contagem "Revisar pendente"); ao confirmar, esse item
simplesmente não é gravado em `cotacao_produto_fornecedor` para este fornecedor.

**ENT-25 — "Confirmar e Processar" desabilitado com Revisar pendente**
Esperado: botão desabilitado enquanto houver item `REVISAR` sem resolução; texto
"{N} item(ns) em Revisar precisam de resolução." atualiza em tempo real conforme o
operador resolve cada um.

**ENT-26 — Confirmar com sucesso**
Passos: resolver todos os itens `REVISAR`, clicar "Confirmar e Processar".
Esperado: `POST .../confirmar` reenvia o **mesmo texto** do preview + as resoluções;
servidor recomputa o pipeline inteiro do zero (preço de itens OK/Atenção nunca vem
de dado ecoado pelo cliente); persiste com `status=OK` sempre (motivos viram CSV em
`tipo_embalagem_detectado`); `cotacao_fornecedor -> CONFIRMADO`; redireciona para
`/cotacoes/{id}/comparativo`.

**ENT-27 — Item base sem nenhum candidato de resposta**
Passos: cotação com um item que o fornecedor simplesmente não respondeu.
Esperado: **não aparece** na tabela de Conferência (decisão F do plano — segue o
protótipo à risca); não bloqueia "Confirmar e Processar"; no Comparativo, esse item
aparece sem oferta desse fornecedor ("—").

**ENT-28 — Item extra (resposta sem item base correspondente)**
Passos: colar uma linha de produto com preço que não bate com nenhum item da lista
base (score baixo em todos).
Esperado: aparece como linha "extra" (`itemBaseId=null`) com motivo `EXTRA_ITEM`,
status `REVISAR`, mas sem controles de resolução interativos (não tem como
persistir sem item base); ao confirmar, essa linha é sempre ignorada silenciosamente
— nunca vira registro em `cotacao_produto_fornecedor`.

**ENT-29 — Reprocessar um fornecedor já `CONFIRMADO`**
Passos: com um fornecedor já `CONFIRMADO`, voltar ao bloco dele, colar uma resposta
diferente, "Processar Cotação" de novo.
Esperado: `cotacao_fornecedor` volta para `PROCESSADO` (invalida a confirmação
anterior); se havia um próximo fornecedor liberado por essa confirmação, "+
Adicionar Fornecedor" volta a ficar bloqueado até reconfirmar este.

**ENT-30 — Reconfirmar um fornecedor preserva embalagem já resolvida**
Pré-condição: fornecedor confirmado uma vez com um item `PACKAGE_PRICE_SUSPECTED`
resolvido informando "Unid./embalagem".
Passos: reprocessar e reconfirmar o mesmo fornecedor, desta vez sem preencher
"Unid./embalagem" de novo para esse item (resolução recalculada não traz um valor
novo).
Esperado: upsert real por `cotacao_produto_id` (não delete+insert) das linhas
anteriores, e o snapshot de `embalagemQtdConfirmada` já resolvido na 1ª confirmação
é **preservado**, não apagado silenciosamente pela reconfirmação.

**ENT-30b — Reconfirmar com valor DIFERENTE de embalagem não sobrescreve o snapshot
(pacote de ajustes pós-call, B.0)**
Pré-condição: mesmo cenário de ENT-30, mas na reconfirmação o operador preenche
"Unid./embalagem" com um valor DIFERENTE do já gravado (não vazio).
Esperado: o snapshot já confirmado sempre vence — o valor novo é descartado
silenciosamente (log em nível WARN no backend para auditoria), sem bloquear a
confirmação e sem alterar `embalagem_qtd_confirmada`/`preco_unitario_calculado` já
persistidos. Achado desta leva: antes dessa correção, o caminho de
`ConfirmacaoRespostaService.confirmar` sobrescrevia o snapshot silenciosamente
(diferente do que o comentário da entidade JPA já prometia) — só
`AvisoService.resolver` (hoje dormente para o fluxo web, ver 3.4) tinha essa trava
de fato.

**ENT-31 — Item Revisar sem resolução correspondente (via API direta)**
Cenário de segurança, não alcançável pela UI normal (o botão já bloqueia): chamar
`POST .../confirmar` direto omitindo a resolução de um item que a recomputação
classifica como `REVISAR`.
Esperado: 400 — o servidor recomputa e barra a confirmação mesmo que o client-side
devesse ter bloqueado antes.

**ENT-32 — Resolução anexada a item OK/Atenção (via API direta)**
Cenário de segurança: enviar uma `resolucao` para um `itemBaseId` que a recomputação
classificou como `OK` ou `ATENCAO`.
Esperado: 400 — não é permitido fabricar preço/texto para um item que não pediu
revisão; só itens genuinamente `REVISAR` aceitam dado do cliente.

**ENT-33 — `SELECIONAR_CANDIDATO` com texto que não bate com nenhum candidato**
Esperado: 400 "Candidato não encontrado para o item {id}: {texto}".

**ENT-34 — Item com preço não extraível ("consultar"/"a combinar") sem edição manual**
Passos: colar uma linha "Produto X - a combinar" e tentar confirmar aceitando a
sugestão como está (sem editar manualmente).
Esperado: 400 pedindo edição manual com o preço correto — `preco_informado` é
`NOT NULL` no banco, nunca grava nulo silenciosamente. **Não confundir com ENT-34b**:
aqui o fornecedor disse explicitamente "consulte-me" (`PACKAGE_PRICE_SUSPECTED`,
continua REVISAR); ENT-34b é o item que simplesmente não trouxe preço nenhum.

**ENT-34b — Item identificado sem preço reconhecível não é erro (pacote de ajustes
pós-call, B.2 — reverte 87228f3)**
Passos: colar uma linha que casa com um item da lista base mas não traz nenhum valor
reconhecível (ex.: "1 fardo sal realta") — diferente de "consultar"/"a combinar".
Esperado: item **não aparece** na Conferência (fica `OK`, sem motivo, sem bloquear
"Confirmar e Processar"); ao confirmar, nenhuma linha é gravada para esse item neste
fornecedor (mesmo efeito de "não mencionado"); se já havia uma linha confirmada de
uma rodada anterior para esse item+fornecedor, ela é removida (reverte para "sem
info"); no Comparativo, o item continua aparecendo com "—" para esse fornecedor.
**Não confundir com** linha genuinamente não interpretável (sem match no catálogo,
sem preço) — essa é uma lacuna pré-existente e continua fora deste escopo (nunca
aparece na Conferência, achado registrado, não corrigido nesta leva).

**ENT-35 — Enviar resposta ou confirmar após finalização**
Esperado: 409 "Cotação finalizada não aceita novas respostas" em ambos
`POST .../resposta` e `POST .../confirmar`.

**ENT-36 — Enviar resposta/confirmar para fornecedor não adicionado à cotação**
Esperado: 404 em ambos os endpoints — o fornecedor precisa existir em
`cotacao_fornecedor` antes (ver ENT-17).

### 3.4 Confirmação de embalagem e aviso dormente (fluxo web atual)

**ENT-37 — Campo "Unid./embalagem" só aparece com `PACKAGE_PRICE_SUSPECTED`**
Esperado: dentro de `ResolucaoInline`, o campo numérico "Unid./embalagem:" só
renderiza quando o item tem esse motivo — para os demais motivos de Revisar, a
resolução não pede embalagem.

**ENT-37b — `PACKAGE_PRICE_SUSPECTED` também dispara para item COM preço (pacote de
ajustes pós-call, B.3 — ativa via antes deliberadamente inerte; critério ampliado
num ajuste seguinte para OR com R$10,00 absolutos)**
Pré-condição: um primeiro fornecedor já confirmado nesta cotação com um preço para um
item (ex.: R$20,00).
Passos: processar um segundo fornecedor com um preço reconhecido normalmente (não
"consultar") para o mesmo item, mas ≥1,5x o preço do primeiro OU ≥R$10,00 acima dele
(ex.: R$40,00 dispara pelos dois critérios; R$31,00 dispara só pelo absoluto —
mediana de referência é o(s) fornecedor(es) já confirmado(s), excluindo
`semEstoque`).
Esperado: item do segundo fornecedor entra `REVISAR`/`PACKAGE_PRICE_SUSPECTED` dentro
da própria Conferência (não só como badge no Comparativo depois); campo "Unid./
embalagem" aparece normalmente (mesmo campo de ENT-37, mesmo destino de
`embalagem_qtd_confirmada`); preço abaixo de 1,5x E abaixo de R$10,00 de diferença
(ex.: R$21,00) não dispara; sem nenhum outro fornecedor confirmado ainda (sem
referência disponível), o gatilho não computa. No Comparativo, o badge "Possível
preço de caixa/fardo" usa o MESMO cálculo (`PrecoReferenciaService`,
`divergeDaReferencia`) — os dois critérios (relativo E absoluto) valem em ambas as
telas, não só na Conferência.

**ENT-37c — Corrigir a embalagem faz o alerta sumir do Comparativo (achado do
cliente)**
Pré-condição: cenário de ENT-37b disparado (segundo fornecedor com `PACKAGE_PRICE_SUSPECTED`).
Passos: resolver o item informando "Unid./embalagem" (ex.: `12`, se o preço mandado
foi de uma caixa/fardo inteiro) e confirmar. Depois, abrir o Comparativo.
Esperado: o badge "Possível preço de caixa/fardo" **não aparece mais** para esse
fornecedor nesse item no Comparativo. Achado corrigido nesta leva:
`precoUnitarioCalculado` (o valor JÁ dividido pela quantidade informada) é o que
entra na comparação, não `precoInformado` (o snapshot bruto do preço da
caixa/fardo, que nunca muda depois de gravado) — antes da correção, o badge
continuava aparecendo mesmo depois de corrigido, porque a comparação usava o
bruto.

**ENT-38 — "Unid./embalagem" vazio, zero ou negativo ao aceitar a sugestão**
Passos: em um item com `PACKAGE_PRICE_SUSPECTED`, aceitar o candidato sem preencher
"Unid./embalagem" (ou com `0`/negativo).
Esperado (achado a confirmar): não há bloqueio client-side — `aplicarEmbalagem`
só inclui `embalagemQtd` na resolução se `> 0`, senão a resolução segue sem esse
campo e o item confirma normalmente sem quantidade de embalagem gravada (sem erro,
sem aviso ao operador). Confirmar se isso é aceitável ou achado de UX.

**ENT-39 — Confirmação concorrente do mesmo fornecedor (duas abas/requisições)**
Passos: abrir a mesma cotação em duas abas, disparar "Confirmar e Processar" no
mesmo fornecedor quase simultaneamente em ambas.
Esperado (achado a confirmar): `ConfirmacaoRespostaService` faz upsert dentro de uma
transação, mas `CotacaoFornecedor` não tem `@Version` — diferente do lock otimista
por `@Version` em `CotacaoProdutoFornecedor` (que protege especificamente o snapshot
`embalagem_qtd_confirmada`, ver ENT-30b), aqui não há proteção visível contra a
segunda requisição sobrescrever silenciosamente o resultado da primeira. Testar e
documentar o comportamento real (nenhum 409 esperado hoje).

**ENT-40 — `AvisoService`/`PENDENTE_CONFIRMACAO` — dormente para os dois canais**
Nota: `POST /cotacoes/{id}/avisos/{cpfId}/resolver` continua existindo no backend
(decisão H do plano — preservado para um futuro canal que persista direto). O módulo
WhatsApp chegou (09/08) e foi cogitado como esse canal, mas desde a unificação
Web/WhatsApp (Prompt 15) ele também passa pelo mesmo `RespostaFornecedorCoreService`
(preview + Conferência), então **nenhum componente, de nenhum canal, chama este
endpoint hoje**: como `/confirmar` já resolve preço/marca/embalagem no mesmo passo e
sempre grava `status=OK`, o gatilho antigo (`status=PENDENTE_CONFIRMACAO`) nunca é
produzido em produção. Só testável via chamada direta à API — ver ENT-41 para o
cenário de segurança que ainda se aplica a esse endpoint.

**ENT-41 — `cpfId` de outra cotação no path da cotação atual**
Cenário de segurança, testável via chamada direta à API (endpoint dormente mas ainda
ativo, ver ENT-40): `POST /cotacoes/{cotacaoA}/avisos/{cpfIdDaCotacaoB}/resolver`.
Esperado: 404 (não 403, não 200) — ver SEC-05.

---

## 4. Comparativo (CMP)

**CMP-01 — Aba Tabela: destaque do menor preço**
Pré-condição: item com ofertas de 2+ fornecedores, status `OK`, sem "sem estoque".
Esperado: célula do menor preço válido aparece em verde/negrito; demais fornecedores
mostram preço normal; fornecedor sem oferta para o item mostra "—".

**CMP-01b — Badge "Possível preço de caixa/fardo" (divergência comparativa,
critério combinado — pacote de ajustes pós-call, B.3 + ajuste seguinte)**
Pré-condição: item com 2+ ofertas confirmadas, sem `semEstoque`.
Passos: um fornecedor com preço ≥1,5x a mediana dos demais fornecedores do mesmo
item, OU com diferença absoluta ≥R$10,00 acima dela (os dois critérios valem em OR
— um não substitui o outro).
Esperado: badge `DIVERGENCIA_COMPARATIVA` ("Possível preço de caixa/fardo") aparece
só no fornecedor mais caro (direcional — nunca no mais barato); item com só 1
fornecedor confirmado não computa (sem "outros" pra comparar). Exemplos: item de
preço baixo com diferença de poucos reais já dispara pelo critério relativo (R$4,00
vs R$7,00, diferença de só R$3,00 mas 1,75x — dispara); item de preço alto com
diferença grande em reais dispara pelo critério absoluto mesmo sem bater 1,5x
(R$100,00 vs R$111,00, só 1,11x mas R$11,00 de diferença — dispara pelo absoluto);
diferença pequena nos dois sentidos não dispara (R$100,00 vs R$109,99 — nem 1,5x
nem R$10,00). Mesmo cálculo (mediana + `divergeDaReferencia`) compartilhado com o
gatilho da Conferência (ver ENT-37b) via `PrecoReferenciaService` — os dois devem
concordar para o mesmo par de preços.

**CMP-02 — Aba Tabela: item com status diferente de OK (não alcançável hoje)**
Nota: `ConfirmacaoRespostaService` e `AvisoService.resolver` são os dois únicos
pontos que gravam `CotacaoProdutoFornecedor.status`, e **ambos sempre gravam `OK`**
— `NAO_IDENTIFICADO` permanece no enum mas nenhum fluxo atual o atribui (as
severidades ATENCAO/REVISAR do preview de Conferência não sobrevivem à
confirmação, ver ENT-26). `StatusBadge` ainda sabe renderizar os outros valores,
mas não há caminho de UI pra produzir um item comparativo com status diferente de
OK. Não reportar como bug se não conseguir forçar esse cenário — é o comportamento
esperado pós-refactor.

**CMP-03 — Filtro de busca por nome**
Passos: digitar parte do nome de um produto na busca.
Esperado: filtragem case-insensitive por substring, atualiza a tabela em tempo real.

**CMP-04 — Filtro por status de cobertura**
Passos: selecionar cada opção (Melhor Compra, Alta Variação, Sem Cotação, Cobertura
Parcial).
Esperado: cada filtro reflete exatamente a lógica de `statusCobertura`: "Sem Cotação"
= zero ofertas válidas; "Cobertura Parcial" = ofertas válidas < min(3, total de
fornecedores); "Alta Variação" = diferença percentual > 20%; "Melhor Compra" = nenhum
dos anteriores.

**CMP-05 — Filtro por fornecedor**
Passos: selecionar um fornecedor específico.
Esperado: mostra só itens em que aquele fornecedor tem alguma oferta (mesmo que não
seja a mais barata).

**CMP-06 — Combinação de filtros sem resultado**
Passos: combinar busca + status + fornecedor de forma que nada bata.
Esperado: "Nenhum produto encontrado com esses filtros." span completo da tabela.

**CMP-07 — Botão "Exportar"**
Passos: clicar "Exportar".
Esperado: abre o diálogo de impressão do navegador (`window.print()`) — **não** é uma
exportação de arquivo real; confirmar que a expectativa do usuário ("baixar um
arquivo") não é atendida e documentar se isso deveria ser esclarecido na label do
botão.

**CMP-08 — Rodapé de economia potencial respeitando filtro ativo**
Esperado: valor de "Economia potencial total nesta visão" recalcula com base apenas
nos itens **filtrados** exibidos, não no total geral da cotação.

**CMP-09 — Aba Por Produto: produto sem nenhuma cotação**
Esperado: card mostra "Sem cotação ainda." sem lista de ofertas.

**CMP-10 — Aba Por Produto: barra proporcional ao preço**
Pré-condição: item com 2+ ofertas válidas de preços bem diferentes.
Esperado: barras ordenadas ascendente por preço, a mais barata em verde/negrito,
largura mínima de 8% mesmo para o preço mais alto (não fica invisível).

**CMP-11 — Aba Por Produto: recomendação com alta variação (>20%)**
Esperado: texto "Diferença de {X}% entre fornecedores — vale confirmar antes de
fechar." em vez da recomendação direta de compra.

**CMP-12 — Aba Fornecedores: cobertura por fornecedor**
Esperado: cada `StatCard` mostra `quantidade/totalItens` e percentual de cobertura
coerente com o número de itens em que aquele fornecedor tem oferta válida.

**CMP-13 — Aba Fornecedores: ranking e "melhores preços"**
Esperado: fornecedores ordenados por número de itens em que têm o menor preço válido
(desc); ranking vazio mostra "Nenhum fornecedor cotado ainda."

**CMP-14 — Aba Fornecedores: "Se comprar tudo dele" vs "Total recomendado (misto)"**
Esperado: "Se comprar tudo dele" conta como 0 os itens que aquele fornecedor não
cotou (não o preço cheio de outro fornecedor) — pode parecer artificialmente baixo
para um fornecedor com baixa cobertura; validar que isso não engana o operador (bom
candidato a nota de UX, não bug).

**CMP-15 — Comparativo vazio (cotação sem itens ainda)**
Esperado: `ResumoCotacao` (na Entrada) não renderiza nada; nas 3 abas do Comparativo,
mensagens de vazio específicas de cada uma (não uma tela genérica).

---

## 5. Mapa de Compra (MAPA)

**MAPA-01 — Carregamento dos 3 cenários em paralelo**
Esperado: os 3 mapas (`MENOR_PRECO`, `EQUILIBRADA`, `MELHOR_PRAZO`) carregam juntos
antes de qualquer card ficar interativo; trocar de cenário depois é instantâneo (sem
novo fetch).

**MAPA-02 — Cenário Menor Preço ignora pedido mínimo**
Pré-condição: fornecedor com pedido mínimo alto e poucos itens vencedores.
Esperado: o fornecedor pode aparecer com total abaixo do mínimo e nenhuma tentativa de
correção — apenas o aviso "abaixo do pedido mínimo" é exibido informativamente.

**MAPA-03 — Cenário Melhor Prazo com fornecedor sem prazo informado**
Pré-condição: um fornecedor sem `prazoEntregaPadrao` preenchido (ou texto não
parseável, ex: "entrega imediata").
Esperado: esse fornecedor nunca é excluído, mas é sempre preterido (ordenado por
último) frente a fornecedores com prazo numérico reconhecido — desempate final por
preço.

**MAPA-04 — Cenário Compra Equilibrada concentrando fornecedores**
Pré-condição: cenário com vários fornecedores próximos do pedido mínimo.
Esperado: distribuição tenta mover itens para atingir o mínimo de cada fornecedor
antes de excluí-lo; comparar visualmente com Menor Preço para confirmar que o total é
igual ou maior (nunca menor).

**MAPA-05 — Fornecedor que não consegue atingir o mínimo é removido da rodada**
Pré-condição: fornecedor cujo total, mesmo após receber todos os itens elegíveis que
poderia, ainda fica abaixo do mínimo.
Esperado: todos os itens dele são realocados para a próxima melhor oferta (ou ficam
sem fornecedor, se não houver alternativa) — ele desaparece da distribuição final da
Compra Equilibrada.

**MAPA-06 — Produto sem nenhuma oferta válida em um cenário**
Esperado: aparece na lista "produtosSemFornecedor" com banner âmbar listando os
nomes; não conta no `totalGeral` nem no cálculo de "pior cenário" (`totalPior`).

**MAPA-07 — Card de cenário: `aria-pressed` e navegação por teclado**
Passos: usar Tab para focar os 3 cards de cenário, alternar com Enter/Espaço.
Esperado: cada card é um `<button>` real, focável nativamente; `aria-pressed` reflete
o cenário ativo.

**MAPA-08 — Copiar pedido do WhatsApp**
Passos: clicar "Copiar pedido" em um fornecedor.
Esperado: texto copiado para a área de transferência (via `mensagemFornecedor`);
botão muda para "Copiado!" por 2s e volta ao texto original.

**MAPA-09 — Clique duplo rápido em "Copiar pedido"**
Passos: clicar "Copiar pedido" duas vezes no mesmo fornecedor dentro de 2 segundos.
Esperado: o texto "Copiado!" permanece visível até ~2s **depois do segundo clique**,
não é apagado prematuramente pelo timeout do primeiro clique (fix do commit
`56dbbe2`).

**MAPA-10 — Clipboard API indisponível**
Cenário: testar em contexto sem `navigator.clipboard` (não-HTTPS, navegador sem
permissão, ou simulação via devtools).
Esperado: mensagem "Este navegador não permite copiar automaticamente. Copie o texto
manualmente." — sem crash nem erro de console. Observação: não há nenhuma
textarea/fallback manual de fato oferecida apesar da mensagem sugerir isso — validar
se essa lacuna deve ser reportada como melhoria de UX.

**MAPA-11 — Fornecedor `PENDENTE_DADOS` excluído da Compra Equilibrada**
Pré-condição: fornecedor com `status=PENDENTE_DADOS` que tem ofertas na cotação.
Esperado: ofertas dele **não aparecem** no cenário Compra Equilibrada, mas aparecem
normalmente em Menor Preço e Melhor Prazo (a exclusão é só na Equilibrada, por
depender do pedido mínimo desconhecido).

**MAPA-12 — Fornecedor inativo (`INATIVO`) com ofertas antigas**
Pré-condição: fornecedor inativado após já ter respondido a uma cotação em aberto.
Esperado (achado a confirmar): diferente do Comparativo/`ComparativoService`, o
`MapaCompraService` usa `fornecedorRepository.findAll()` sem excluir `INATIVO` —
testar se as ofertas desse fornecedor ainda aparecem no Mapa mesmo depois de
inativado, o que seria uma inconsistência com o Comparativo.

**MAPA-13 — Finalizar cotação sem nenhum fornecedor abaixo do mínimo**
Passos: abrir modal "Concluir Ordem de Compra", confirmar.
Esperado: nenhum checkbox de ciência é exibido; botão de confirmação habilitado
diretamente; após confirmar, `POST /finalizar` roda e a página troca para a visão
somente-leitura `OrdemFinalizada` sem navegação de rota (é reativo ao novo status).

**MAPA-14 — Finalizar cotação com fornecedor(es) abaixo do mínimo**
Esperado: checkbox obrigatório "Estou ciente que {fornecedor(es)} ficará(ão) abaixo do
pedido mínimo" aparece; botão de confirmação fica desabilitado até marcar o checkbox.

**MAPA-15 — Finalizar cotação já finalizada (dupla finalização)**
Passos: com a cotação já `FINALIZADA`, tentar finalizar de novo via API diretamente
(a UI já esconde o botão nesse estado — usar chamada direta para confirmar o
comportamento do backend).
Esperado: 409 "Cotação já finalizada".

**MAPA-16 — Finalizar uma cotação em RASCUNHO (sem nunca ter passado por
EM_ANDAMENTO)**
Pré-condição: cotação recém-criada, nenhuma lista de produtos enviada ainda.
Passos: chamar `POST /cotacoes/{id}/finalizar` diretamente.
Esperado (comportamento real, não há guarda): a cotação é finalizada mesmo sem
nenhum item — não há checagem de estado mínimo exigido. Confirmar se isso é aceitável
ou deve virar um achado de regra de negócio ausente.

**MAPA-17 — Visão pós-finalização é 100% somente-leitura**
Passos: acessar `/mapa` de uma cotação `FINALIZADA`.
Esperado: sem seletor de cenário, sem botão "Copiar pedido", sem botão "Concluir" —
apenas o cenário gravado em `cenarioSelecionado` (ou `MENOR_PRECO` como fallback se
nulo) é mostrado, com os stats finais.

**MAPA-18 — `cenario` inválido na query string**
Passos: chamar `GET /cotacoes/{id}/mapa?cenario=INVALIDO` diretamente pela API.
Esperado (achado a confirmar): provavelmente erro 500 genérico ("Erro interno. Tente
novamente.") em vez de 400 — `MethodArgumentTypeMismatchException` não tem handler
dedicado, cai no catch-all. Documentar o status/mensagem real recebida.

---

## 6. Alertas (ALR)

**ALR-01 — Cotação saudável (sem críticos nem atenções)**
Esperado: bucket Crítico e Atenção mostram "Nenhum alerta crítico."/"Nenhum alerta de
atenção."; em "Próximos passos", mensagem "Cotação em bom estado — revise o Mapa de
Compra para fechar o pedido."

**ALR-02 — Produto sem nenhuma cotação → Crítico**
Esperado: aparece no bucket Crítico via `itensSemCotacao`.

**ALR-03 — Fornecedor abaixo do pedido mínimo (cenário Equilibrada) → Crítico**
Esperado: aparece no bucket Crítico com o valor de `faltaParaMinimo` formatado em
moeda. Confirmar que a checagem usa **só** o cenário `EQUILIBRADA` (não Menor Preço
nem Melhor Prazo) — testar que um fornecedor abaixo do mínimo apenas em Menor Preço
não aparece aqui.

**ALR-04 — Alta variação de preço (>20%) → Atenção**
Esperado: aparece no bucket Atenção via `itensAltaVariacao`.

**ALR-05 — Cobertura parcial (< min(3, total fornecedores)) → Atenção**
Pré-condição: cotação com apenas 2 fornecedores cadastrados no total, um item cotado
por só 1 deles.
Esperado: aparece como cobertura parcial (limiar dinâmico = min(3, 2) = 2, e 1 < 2).

**ALR-06 — Maior economia potencial → Oportunidades**
Esperado: exibe o item de maior `economiaPotencial`, só se > 0.

**ALR-07 — Concentração de fornecedor líder (>60%) → Oportunidades**
Pré-condição: um único fornecedor vence >60% dos itens (menor preço) na cotação.
Esperado: alerta de "diversificar fornecedores" aparece.

**ALR-08 — Resumo por categoria (barras)**
Esperado: 3 barras horizontais (Crítico/Atenção/Oportunidades) com largura
proporcional ao maior valor entre as três categorias.

---

## 7. Multi-tenant e Segurança (SEC)

**SEC-01 — Isolamento entre tenants (leitura cruzada)**
Pré-condição: 2 tenants distintos, cada um com cotações/fornecedores próprios.
Passos: autenticado como usuário do tenant A, tentar acessar (via API direta, trocando
o `{id}` na URL) uma cotação/fornecedor/produto pertencente ao tenant B.
Esperado: 404 em todos os casos (RLS + validação de posse) — nunca 200 com dado de
outro tenant, nunca 403 revelando que o recurso existe.

**SEC-02 — Isolamento em `cotacao_produto`/`cotacao_produto_fornecedor` (join-path)**
Contexto: essas duas tabelas não têm `tenant_id` direto nem filtro Hibernate — isolam
só via policy RLS de join. Testar especificamente ler/gravar um `cotacaoProdutoId` ou
`cpfId` de outro tenant via os endpoints que os expõem (`/avisos/.../resolver`,
`/comparativo`).
Esperado: falha (404), nunca sucesso.

**SEC-03 — Tentativa de acesso cross-tenant ao mesmo recurso lógico**
Esperado: mesmo com IDs válidos (UUID existente, só que de outro tenant), toda
tentativa de leitura ou escrita falha — nunca retorna 500 nem vaza dado parcial em
mensagem de erro.

**SEC-04 — Cross-cotação dentro do **mesmo** tenant**
Pré-condição: 2 cotações do mesmo tenant.
Passos: `POST /cotacoes/{cotacaoA}/avisos/{cpfIdDaCotacaoB}/resolver` (mesmo tenant,
cotação errada no path).
Esperado: 404 (ver ENT-41) — a validação de posse do `AvisoService` bloqueia mesmo
dentro do próprio tenant (endpoint dormente para o fluxo web, mas ainda ativo — ver
ENT-40).

**SEC-05 — Usuário ADMIN_PRX chamando endpoints comuns**
Pré-condição: usuário com `papel=ADMIN_PRX` (sem `tenant_id`).
Passos: autenticado como esse usuário, chamar `GET /cotacoes`, `/fornecedores`,
`/produtos` normalmente (não existe painel admin dedicado).
Esperado (achado de design a documentar, não necessariamente "bug" a corrigir agora):
como esses endpoints não fazem nenhuma restrição de papel, e o RLS libera tudo para
`is_admin_request()`, o ADMIN_PRX **verá dados de todos os tenants misturados** nessas
listagens comuns — confirmar esse comportamento e reportar como risco de design se
ainda não estiver ciente.

**SEC-06 — Token de um papel usado em endpoint do outro**
Esperado: como não há `@PreAuthorize` em nenhum controller de negócio, um
OPERADOR_CLIENTE e um ADMIN_PRX têm acesso idêntico a todos os endpoints reais
(exceto a rota `/admin/**` inexistente). Confirmar que isso é intencional/conhecido.

**SEC-07 — Header `Authorization` ausente ou malformado**
Passos: chamar qualquer endpoint protegido sem header, ou com `Bearer` mal formado.
Esperado: 401 no formato **padrão do Spring Security** (não o `ProblemDetail` custom
usado por `/auth/login|refresh`) — checar que o formato é realmente diferente entre
os dois casos, já que isso pode confundir um cliente de API que espera um único
formato de erro.

**SEC-08 — Token assinado com segredo diferente / adulterado**
Esperado: `JwtAuthFilter` não autentica (silenciosamente ignora), resultando em 401
padrão do Spring Security na rota protegida.

---

## 8. Tratamento de Erros Genérico (ERR)

**ERR-01 — Erro de validação de campo (400)**
Passos: enviar um payload inválido (ex: `POST /cotacoes` com `titulo` vazio) via API
direta.
Esperado: 400, corpo `ProblemDetail` com `detail` listando `"campo: mensagem"` — pode
concatenar múltiplos campos com `;` se houver mais de um erro.

**ERR-02 — Recurso inexistente (404)**
Esperado: `ProblemDetail` 404 com mensagem legível (ex: "Fornecedor não encontrado:
{uuid}").

**ERR-03 — Conflito de estado (409)**
Esperado: `ProblemDetail` 409 com a mensagem específica da regra violada (finalizar
2x, embalagem já confirmada, lock otimista).

**ERR-04 — Erro interno não tratado (500)**
Esperado: mensagem genérica fixa "Erro interno. Tente novamente." — **nunca** stack
trace, nome de exceção ou detalhe interno vazando para o cliente.

**ERR-05 — Falha de rede (sem resposta do servidor)**
Passos: desligar o backend, tentar qualquer ação no frontend.
Esperado: cada página mostra seu próprio texto de fallback (definido localmente, ex:
"Não foi possível carregar o comparativo.") — não trava a UI, não mostra tela branca.

**ERR-06 — Path param com tipo errado (UUID inválido, enum inválido)**
Ver MAPA-18 — comportamento esperado hoje é 500 genérico, não 400. Testar em pelo
menos 2 endpoints (`GET /cotacoes/{id}` com id não-UUID, `GET /mapa?cenario=X`
inválido) para confirmar consistência do gap.

---

## 9. Acessibilidade (A11Y)

**A11Y-01 — Ícone de aviso `PENDENTE_DADOS` na sidebar**
Esperado: `role="img"` + `aria-label` "Fornecedor criado automaticamente — complete os
dados", anunciado corretamente por leitor de tela.

**A11Y-02 — Mesmo ícone dentro do bloco de resposta do fornecedor**
Esperado (achado conhecido a confirmar): **não** tem `role`/`aria-label`, só `title`
— inconsistente com A11Y-01 mesmo sendo o mesmo ícone/significado. Reportar como
achado de a11y se ainda não corrigido.

**A11Y-03 — Navegação por teclado nos cards de cenário do Mapa**
Ver MAPA-07.

**A11Y-04 — Modal: fechar com Esc**
Passos: abrir um modal que usa o componente compartilhado `components/Modal.tsx`
(cadastro/edição de fornecedor, "Concluir Ordem de Compra"), pressionar Esc.
Esperado: fecha o modal.

**A11Y-04b — `ConferenciaModal`: Esc NÃO fecha (inconsistência introduzida no refactor)**
Passos: abrir a Conferência (ver ENT-18), pressionar Esc.
Esperado (achado a confirmar, regressão de consistência): `ConferenciaModal.tsx` é
implementado como `<div>` própria, não reaproveita `components/Modal.tsx` — não tem
listener de `Escape`. Diferente dos demais modais do app (A11Y-04), Esc não fecha a
Conferência. Reportar como achado de a11y se ainda não estiver ciente.

**A11Y-05 — Modal: clique fora fecha, clique dentro não**
Esperado: clicar no backdrop fecha (vale também para `ConferenciaModal`, que
implementa isso mesmo sem usar o componente compartilhado); clicar no conteúdo do
modal não propaga o clique de fechamento.

**A11Y-06 — Modal: ausência de focus trap**
Passos: abrir um modal, pressionar Tab repetidamente.
Esperado (gap conhecido, não corrigido): o foco **escapa** do modal para elementos da
página por trás — não há focus trap nem devolução de foco ao elemento que abriu o
modal ao fechar. Documentar como achado de acessibilidade pendente.

**A11Y-07 — Tabs do Comparativo (`aria-pressed`)**
Esperado: `TabPill` reflete corretamente o estado pressionado ao alternar entre
Tabela/Por Produto/Fornecedores.

**A11Y-08 — Linha expansível do Dashboard (`aria-expanded`)**
Esperado: atributo correto e rótulo alternando "Expandir detalhes"/"Recolher
detalhes".

---

## 10. Parsers e Matching — casos de borda adicionais (PARSE)

**PARSE-01 — Divergência de peso/volume mesmo com nome idêntico**
Passos: cotar "Sazon Legumes 60g" e receber resposta de fornecedor para "Sazon
Legumes 500g" (unidade igual, valor diferente).
Esperado: score de similaridade forçado a `0.0` — nunca dá match, mesmo com alta
sobreposição textual (regra de negação por peso/volume divergente).

**PARSE-02 — Bônus de peso/volume exato**
Passos: nomes com pequenas diferenças textuais mas mesmo peso/volume exato (unidade e
valor).
Esperado: score recebe bônus de `+0.2` (capado em 1.0).

**PARSE-03 — Item no limiar exato do score de match (0,60)**
Difícil de forçar precisamente via UI, mas útil documentar: `matched = score >= 0.6`
— um score de exatamente 0,60 **deve** ser considerado match; 0,59 não.

**PARSE-04 — Múltiplos pesos/volumes na mesma string**
Passos: nome de produto com dois valores de peso/volume (ex: "Combo 500ml + 1kg").
Esperado: só o **primeiro** encontrado na string é considerado (`500ml`) — o segundo
é ignorado para fins de bônus/penalidade.

**PARSE-05 — Detecção de embalagem por substring solta**
Passos: um texto de resposta contendo a substring "cx" dentro de outra palavra (não
como unidade), ex. um código de produto.
Esperado (achado a confirmar): a checagem usa `.contains()` puro, não regex de
palavra inteira — testar se gera falso-positivo de `PENDENTE_CONFIRMACAO`.

**PARSE-06 — Prazo de entrega com texto ambíguo**
Passos: cadastrar fornecedor com prazo "2 a 3 dias úteis".
Esperado: `PrazoEntregaParser` extrai apenas o **primeiro** número (2), ignorando o
intervalo — confirmar que o Mapa de Compra usa 2 dias para esse fornecedor no cenário
Melhor Prazo.

**PARSE-07 — Prazo de entrega sem nenhum número**
Passos: prazo = "entrega imediata".
Esperado: `null` — tratado como "sem prazo conhecido", ordenado por último no cenário
Melhor Prazo, nunca excluído.

---

## 11. Fora de escopo — referência rápida (OOS)

Não abrir como achado novo se encontrar qualquer um destes comportamentos — já são
gaps documentados e conhecidos:

1. ~~Nenhuma tela/endpoint de WhatsApp existe~~ — **desatualizado**: o módulo WhatsApp
   (webhook, classificador, roteamento) está implementado e funcional desde 09/08. Ver
   `docs/documentacao-tecnica-sistema-cotacao-prx.md` seção 10. `UsuarioTelefoneAutorizado`
   (substituiu `TenantTelefoneAutorizado` — telefone é por usuário, não por tenant) tem
   código de verdade por trás, não é só schema.
2. `CanalOrigem.WHATSAPP` e `OrigemCadastro.WHATSAPP_AUTO` **agora são atribuídos**
   normalmente pelo webhook (item 1) — uma cotação nasce `WHATSAPP` quando iniciada por
   lista/resposta recebida no canal, e um fornecedor nasce `WHATSAPP_AUTO`/
   `PENDENTE_DADOS` quando o matching por nome não encontra um existente (ver ENT-16).
3. Painel admin (`/admin/**`) — este documento ainda o lista como "reservado no
   `SecurityConfig`, nenhum controller implementado", mas essa revisão (10/08, focada
   só no módulo WhatsApp) encontrou `AdminUsuarioController`/`UsuarioAdminController`
   no backend, o que sugere que esse item também está desatualizado — não confirmado
   nem corrigido aqui; tratar como suspeito, não como fora de escopo confirmado.
4. Sem Histórico de Preços / Economia entre cotações — tudo é calculado só a partir
   da cotação atual.
5. Mapa de Compra é 100% somente-leitura — sem mover produto manualmente entre
   fornecedores.
6. `StatusItem.DIVERGENCIA_PRECO` e `DIVERGENCIA_VOLUME` **não existem mais** no
   enum/CHECK constraint desde o refactor de 2026-07-16/17 (antes eram só "nunca
   atribuídos" — agora foram removidos). `PENDENTE_CONFIRMACAO` permanece no enum
   mas também não é mais atribuído por nenhum fluxo web (ver ENT-40). Na prática,
   todo `CotacaoProdutoFornecedor` persistido hoje tem `status=OK` — ver CMP-02.
7. `CotacaoStatus.CANCELADA` nunca é atribuído — não existe endpoint de cancelamento.
8. `POST /produtos` não existe — catálogo só cresce via matching automático durante o
   processamento de lista/resposta.
9. `FornecedorProduto` (catálogo direto de parceiro) — schema existe, zero código de
   repository/service/controller.
10. Categorias finas de divergência (marca trocada, gramagem diferente, múltiplas
    opções, item extra) **existem** desde o refactor — mas só no preview de
    Conferência (`MotivoConferencia`, efêmero); não persistem em `StatusItem`, que
    continua tendo só os valores brutos do banco (item 6 acima).
11. `POST /avisos/{cpfId}/resolver` retorna a entidade JPA crua (com `versao` etc.),
    não um DTO dedicado — comportamento conhecido, não um bug de payload a corrigir
    nesta rodada de QA. Endpoint dormente para o fluxo web (ver ENT-40) — só
    testável via chamada direta à API.
12. Persistência de rascunho entre preview (`POST .../resposta`) e confirmação
    (`POST .../confirmar`) — não existe; fechar a Conferência sem confirmar descarta
    tudo (ver ENT-19, decisão B do plano de refactor).
