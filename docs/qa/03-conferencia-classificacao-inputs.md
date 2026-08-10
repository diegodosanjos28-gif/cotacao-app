# Inputs — Conferência do Fornecedor (cenários pareados: lista base + resposta)

Cada cenário abaixo tem **dois** blocos: a **lista base** (colar no campo "Lista de
produtos" de uma cotação nova, antes de adicionar qualquer fornecedor) e a
**resposta** (colar no bloco do fornecedor, depois de adicioná-lo, e clicar
"Processar Cotação"). Os dois blocos juntos foram desenhados — token por token,
seguindo exatamente o algoritmo de `MatchingProdutoService.calcSimilaridade`,
`ConciliacaoRespostaService` e `ClassificacaoConferenciaService` — para reproduzir
**um motivo específico** de Atenção/Revisar (ou o caminho "OK sem motivos") de forma
isolada, sem ruído de outros motivos aparecendo junto.

**Convenção usada nos itens da lista base:** sempre em minúsculo, nunca usando uma
marca da lista fixa de `MARCAS_CONHECIDAS` — isso evita que a heurística de detecção
de marca dispare `BRAND_CHANGED` sem querer nos cenários que não são sobre marca (a
extração de marca só considera uma palavra capitalizada como candidata; texto todo
minúsculo nunca aciona a heurística).

Cada cenário informa o **score de similaridade calculado** e o **status/motivo(s)
final(is)** esperados, derivados manualmente do algoritmo — não são um "deve dar
mais ou menos isso", são o resultado exato da simulação.

> Os blocos abaixo são pensados pra colagem direta nos campos web, sem header. Se
> algum deles for usado via `/dev/whatsapp/simular` (canal WhatsApp real, que passa
> pelo `ClassificadorMensagemWhatsapp`), prefixe manualmente `LISTA_PRODUTOS\n` (lista
> base) ou `RESPOSTA_FORNECEDOR\n` + nome do fornecedor (resposta) — sem isso a
> mensagem é rejeitada como "formato não reconhecido" (ver seção 10.3 da doc técnica e
> `docs/qa/scripts/README.md`).

---

**CF-01 — OK, sem nenhum motivo (linha de base/controle)**
**Descrição:** confirma o caminho "feliz" — match direto, mesma medida, sem
divergência de marca/peso/embalagem/preço/confiança. Use este cenário para comparar
contra qualquer um dos outros (ele é o "silêncio" contra o qual os motivos abaixo se
destacam).
**Lista base:**
```
10un sazon legumes 60g
```
**Resposta:**
```
Sazon Legumes 60g - R$ 4,89
```
**Resultado esperado:** score ≈ 0,90 (0,75 de sobreposição de tokens + 0,15 de bônus
de mesma medida); status **OK**; nenhum motivo listado; candidato único.

---

**CF-02 — BRAND_CHANGED (marca diferente da lista base)**
**Descrição:** ambas as marcas (base e ofertada) precisam ser reconhecidas e
diferentes entre si.
**Lista base:**
```
5un maggi caldo de galinha temperinho
```
**Resposta:**
```
Knorr Caldo de Galinha Temperinho - R$ 3,20
```
**Resultado esperado:** score = 0,60 (3 de 5 tokens significativos batem: caldo,
galinha, temperinho — "knorr" não bate com "maggi"); status inicial CONFIRMADO
rebaixado para **ATENÇÃO** por causa da marca (score < 0,75, limiar de rebaixa);
motivo único: **BRAND_CHANGED** ("Marca diferente da lista base").

---

**CF-03 — WEIGHT_CHANGED (gramagem divergente, mesmo produto)**
**Descrição:** mesma dimensão (peso), valor diferente — a divergência de peso força
o score direto a zero, precisa da 2ª passada (ignorando medida) pra ainda achar o
match, com penalidade de 40%.
**Lista base:**
```
10un sazon legumes 60g
```
**Resposta:**
```
Sazon Legumes 120g - R$ 4,89
```
**Resultado esperado:** score final ≈ 0,40 (0,667 da 2ª passada × 0,6 de
penalidade); status **REVISAR**; motivos: **WEIGHT_CHANGED** ("Gramagem diferente da
solicitada") **e** normalmente também **LOW_CONFIDENCE_MATCH** junto (o score de
0,40 fica abaixo do limiar de confiança de 0,45) — é esperado ver os dois motivos
juntos aqui, não é um comportamento errático.

---

**CF-04 — WEIGHT_ADDED (peso informado pelo fornecedor, ausente na lista base)**
**Descrição:** lista base sem nenhuma medida de peso/volume; resposta menciona peso
em **kg/g** (dimensão "peso").
**Lista base:**
```
3un detergente concentrado
```
**Resposta:**
```
Detergente Concentrado 900g - R$ 2,10
```
**Resultado esperado:** score ≈ 0,667 (2 de 3 tokens batem: detergente,
concentrado); status **REVISAR**; motivo único: **WEIGHT_ADDED** ("Peso informado,
mas a lista base não tinha peso").

---

**CF-05 — VOLUME_ADDED (mesma lógica do CF-04, mas em ml/l)**
**Descrição:** idêntico ao CF-04, trocando a unidade pra **ml/l** (dimensão
"volume") — confirma que o motivo certo é escolhido conforme a dimensão detectada.
**Lista base:**
```
3un detergente concentrado
```
**Resposta:**
```
Detergente Concentrado 500ml - R$ 2,10
```
**Resultado esperado:** score ≈ 0,667; status **REVISAR**; motivo único:
**VOLUME_ADDED** ("Volume informado, mas a lista base não tinha volume") — note que
é um motivo **diferente** de WEIGHT_ADDED apesar do texto/estrutura da resposta ser
quase idêntico ao CF-04.

---

**CF-06 — PACKAGE_QTY_ADDED (embalagem informada, ausente na lista base)**
**Descrição:** lista base sem nenhuma menção a caixa/fardo/pacote; resposta detecta
"Caixa com N" por texto.
**Lista base:**
```
2un refrigerante lata
```
**Resposta:**
```
Refrigerante Lata - Caixa com 12 - R$ 45,00
```
**Resultado esperado:** score = 0,50 (2 de 4 tokens batem: refrigerante, lata — "12"
é numérico e só casaria por igualdade exata, "caixa" não existe na lista base);
status **REVISAR** (score no limiar de PROVÁVEL, mas o motivo de embalagem já
escala sozinho); motivo único: **PACKAGE_QTY_ADDED** ("Quantidade por embalagem
informada, mas a lista base não tinha").

---

**CF-07 — PACKAGE_QTY_CHANGED (embalagem diferente da já informada na base)**
**Descrição:** variante do CF-06 em que a lista base **já** tem uma quantidade de
embalagem (diferente da oferecida).
**Lista base:**
```
2un refrigerante lata caixa com 6
```
**Resposta:**
```
Refrigerante Lata - Caixa com 12 - R$ 45,00
```
**Resultado esperado:** score = 0,60 (3 de 5 tokens batem, incluindo "caixa" desta
vez); status **REVISAR**; motivo único: **PACKAGE_QTY_CHANGED** ("Quantidade por
embalagem diferente: base tinha 6, oferecido tem 12").

---

**CF-08 — PACKAGE_PRICE_SUSPECTED (preço ainda não informado / "consultar")**
**Descrição:** este é o gatilho "consultar/a combinar" (ver RF-05 do documento 02).
Desde o pacote de ajustes pós-call (2026-07-29) existe um **segundo** gatilho
independente para o mesmo motivo — preço reconhecido, mas ≥1,5x a referência OU
≥R$10,00 acima dela (os dois em OR), de outro fornecedor já confirmado — ver
**CF-15** abaixo.
**Lista base:**
```
2un vinho tinto reserva especial
```
**Resposta:**
```
Vinho Tinto Reserva Especial - consultar
```
**Resultado esperado:** score = 0,80 (match forte de texto); status **REVISAR**;
motivo único: **PACKAGE_PRICE_SUSPECTED**; candidato aparece na Conferência com
preço vazio/desabilitado — só é possível confirmar esse item via "Editar
manualmente" com um preço real (ver ENT-34 do plano de QA geral), nunca "aceitar
sugestão" como está.

---

**CF-09 — MULTIPLE_OPTIONS (duas linhas de resposta batendo no mesmo item base)**
**Descrição:** um único item na lista base recebe **dois** candidatos igualmente
válidos (mesma medida, nenhum divergente de volume) — o agrupamento não consegue
escolher um só.
**Lista base:**
```
5un sazon legumes 60g
```
**Resposta (colar as duas linhas juntas):**
```
Sazon Legumes 60g Original - R$ 4,89
Sazon Legumes 60g Light - R$ 5,49
```
**Resultado esperado:** ambas as linhas batem no mesmo item base (score ≈ 0,90 cada,
mesma lógica do CF-01, com um token extra "original"/"light" sobrando de cada
lado); status **REVISAR**; motivo único: **MULTIPLE_OPTIONS**; a Conferência mostra
**2 candidatos** (um radio button por opção, com preços 4,89 e 5,49) — resolver via
`SELECIONAR_CANDIDATO`.

---

**CF-10 — EXTRA_ITEM (produto oferecido sem nenhum item base correspondente)**
**Descrição:** a resposta cota um produto completamente diferente de tudo que está
na lista base, mas com preço reconhecível — precisa aparecer mesmo assim, como item
"extra" elegível para "Adicionar item à lista".
**Lista base:**
```
5un sazon legumes 60g
```
**Resposta:**
```
Chocolate Amargo 70% Cacau 100g - R$ 9,90
```
**Resultado esperado:** nenhum item base recebe esse candidato (score 0 contra o
único item da lista, mesmo na 2ª passada); status **REVISAR**; motivo único:
**EXTRA_ITEM**; `itemBaseId` nulo — sem controles de resolução interativos além de
"Adicionar item à lista"; ao confirmar, essa linha é **sempre ignorada**, nunca vira
`cotacao_produto_fornecedor` (ver ENT-28 do plano de QA geral).

---

**CF-11 — LOW_CONFIDENCE_MATCH (match fraco, sem nenhum outro motivo junto)**
**Descrição:** score na faixa PROVÁVEL (0,35–0,55) mas abaixo de 0,45, por pura
sobreposição parcial de texto — sem nenhuma divergência de peso/marca/embalagem
envolvida, pra isolar exatamente esse motivo (diferente do CF-03, onde ele aparece
"de carona" com WEIGHT_CHANGED).
**Lista base:**
```
3un biscoito recheado chocolate
```
**Resposta:**
```
Biscoito Wafer Chocolate Sabor Morango - R$ 3,50
```
**Resultado esperado:** score = 0,40 (2 de 5 tokens batem: biscoito, chocolate —
"wafer", "sabor", "morango" não têm correspondência); status **ATENÇÃO**; motivo
único: **LOW_CONFIDENCE_MATCH** ("Confiança baixa no reconhecimento do produto").

---

**CF-12 — 🔍 Sem estoque contra item real da lista base (achado a confirmar)**
**Descrição:** ao contrário de todos os cenários acima, uma resposta "sem estoque"
que dá match forte com um item da lista base **não aparece em lugar nenhum** da
Conferência — não é OK, não é Atenção, não é Revisar, não vira "extra". A leitura de
`ClassificacaoConferenciaService.classificar` sugere que um item com
`status=STATUS_SEM_ESTOQUE` nunca entra no agrupamento por produto (que só aceita
`CONFIRMADO`/`PROVAVEL`) e também não entra no laço de "não identificados com
preço" (preço é sempre nulo em linhas de sem-estoque) — ele simplesmente não gera
nenhum `ItemConferencia`. Isso não foi confirmado rodando o sistema, só derivado do
código — testar e reportar o resultado real antes de tratar como bug confirmado.
**Lista base:**
```
5un sazon legumes 60g
```
**Resposta:**
```
Sazon Legumes 60g - sem estoque
```
**Resultado esperado (hipótese a validar):** a Conferência abre com contadores que
**não incluem** esse item em nenhuma categoria (Total pode ficar menor do que o
esperado, já que 1 item da lista base tinha resposta e ela "sumiu"); confirmar sem
resolver mais nada e checar se, no Comparativo, esse fornecedor aparece como "—"
(sem oferta) para "Sazon Legumes 60g" em vez de aparecer explicitamente como "sem
estoque" — se for esse o caso, é um achado real de perda silenciosa de informação
(o fornecedor respondeu que não tem, mas o sistema trata como se ele nunca tivesse
respondido).

---

## Pacote de ajustes pós-call (2026-07-29) — novos comportamentos

**CF-13 — Item identificado sem preço reconhecível não aparece mais na Conferência
(B.2, reverte 87228f3)**
**Descrição:** a linha casa fortemente com o item base mas não traz nenhum número
reconhecível — nem "consultar" (isso é CF-08, continua REVISAR). Antes desta data,
isso gerava o motivo `PRICE_MISSING`/REVISAR; agora é tratado como "fornecedor não
informou", igual a não ter mencionado o item.
**Lista base:**
```
5un sal grosso moído
```
**Resposta:**
```
Sal Grosso Moído
```
**Resultado esperado:** score ≈ 0,90 (match forte de texto); status **OK**; **sem
nenhum motivo**; item não aparece destacado, não bloqueia "Confirmar e Processar".
Ao confirmar, nenhuma linha é gravada em `cotacao_produto_fornecedor` para este
item+fornecedor; no Comparativo, aparece como "—" para esse fornecedor. Se já havia
uma linha confirmada numa rodada anterior para esse item+fornecedor (reenviar este
mesmo cenário uma 2ª vez depois de já ter confirmado com preço), ela é **removida**
na reconfirmação — não fica "grudada" com o preço antigo.

---

**CF-14 — MULTIPLE_OPTIONS: Excluir / Adicionar como novo item na opção não
selecionada (B.1)**
**Descrição:** parte do mesmo cenário de CF-09 (2 candidatos pro mesmo item base) —
adiciona o passo de resolução das opções não escolhidas, que agora tem ação
explícita em vez de descarte silencioso.
**Lista base:**
```
5un sazon legumes 60g
```
**Resposta (colar as duas linhas juntas):**
```
Sazon Legumes 60g Original - R$ 4,89
Sazon Legumes 60g Light - R$ 5,49
```
**Passos:** na Conferência, selecionar "Original" (`R$ 4,89`) como vencedor
(`SELECIONAR_CANDIDATO`). No candidato "Light" (não selecionado), clicar "Adicionar
como novo item na lista base", editar o texto/preço se quiser (ambos vêm
pré-preenchidos), "Salvar", depois "Confirmar e Processar".
**Resultado esperado:** confirmação cria **2** linhas: o item original
("Sazon Legumes 60g") com o preço do candidato "Original" (R$ 4,89, vindo do
servidor, não editável nessa via), **e** um `CotacaoProduto` novo na lista base
("Sazon Legumes 60g Light" ou o texto editado) com o preço informado no formulário
de spin-off. Se em vez de "Adicionar como novo item" o operador clicar "Excluir" no
candidato "Light", nada muda no resultado final (já era descartado por padrão) — só
o feedback visual da tela reflete a decisão explícita. Se o operador tentar
confirmar o spin-off **sem** selecionar um vencedor pro item original, a confirmação
inteira é bloqueada (400) mesmo com o spin-off preenchido.

---

**CF-15 — PACKAGE_PRICE_SUSPECTED via referência de outro fornecedor confirmado
(B.3, gatilho de 1,5x OU R$10,00 absolutos)**
**Descrição:** preço reconhecido normalmente (não "consultar", diferente de CF-08),
mas ≥1,5x a mediana dos preços de outros fornecedores já confirmados para o mesmo
item nesta cotação, OU com diferença absoluta ≥R$10,00 acima dela (os dois
critérios valem em OR — um ajuste seguinte ao pacote original reintroduziu o R$10,00
absoluto como critério adicional, não substituto). Precisa de **dois** fornecedores
em sequência — confirmar o primeiro antes de processar o segundo (fluxo sequencial
padrão).
**Lista base:**
```
3un feijão carioca tipo 1
```
**Resposta Fornecedor 1 (processar e CONFIRMAR antes de seguir):**
```
Feijão Carioca Tipo 1 - R$ 8,00
```
**Resposta Fornecedor 2 (processar depois do 1º confirmado):**
```
Feijão Carioca Tipo 1 - R$ 13,00
```
**Resultado esperado:** Fornecedor 1 confirma normalmente, status **OK**, sem
motivo (score alto, preço único, sem outro fornecedor pra comparar ainda).
Fornecedor 2: mesmo score/match do Fornecedor 1, mas `R$13,00 ÷ R$8,00 = 1,625x` ≥
1,5x a referência → status **REVISAR**, motivo **PACKAGE_PRICE_SUSPECTED** (mesmo
label "Possível preço de caixa/fardo" do CF-08), campo "Unid./embalagem" disponível
para resolver. Repetir com `R$11,00` (1,375x, abaixo do limiar) no lugar de
`R$13,00` para confirmar que **não** dispara.

---

**CF-16 — Snapshot de embalagem não é sobrescrito em reconfirmação com valor
diferente (B.0)**
**Descrição:** continuação de CF-15 — depois de resolver "Unid./embalagem" pro
Fornecedor 2, uma reconfirmação posterior com um valor diferente não deve mudar o
que já foi gravado.
**Passos:** a partir do estado final de CF-15 (Fornecedor 2 com `PACKAGE_PRICE_SUSPECTED`
resolvido, "Unid./embalagem" = `12`, confirmado). Reprocessar o Fornecedor 2 colando
a mesma resposta (`Feijão Carioca Tipo 1 - R$ 13,00`) de novo, desta vez preenchendo
"Unid./embalagem" com `6` (valor diferente do já gravado), "Confirmar e Processar".
**Resultado esperado:** a confirmação **não falha** e não bloqueia; o snapshot
gravado (`embalagem_qtd_confirmada`) continua `12`, não vira `6` — o valor novo é
descartado silenciosamente (log de auditoria no backend, nível WARN); o preço
unitário calculado (`preco_unitario_calculado`) continua usando `12` na divisão
(`13,00 ÷ 12`), não `6`.
