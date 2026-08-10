# Inputs — Lista de produtos (Entrada de Dados, campo "Lista de produtos")

Cenários de input para o textarea "Lista de produtos" na Entrada de Dados
(`POST /cotacoes/{id}/lista`, processado por
`backend/src/main/java/com/prx/cotacao/cotacao/parser/ParserListaProdutosService.java`).

> Os blocos abaixo são pensados pra colagem direta no campo web, sem header. Se algum
> deles for usado via `/dev/whatsapp/simular` (canal WhatsApp real, que passa pelo
> `ClassificadorMensagemWhatsapp`), prefixe manualmente `LISTA_PRODUTOS\n` — sem isso
> a mensagem é rejeitada como "formato não reconhecido" (ver seção 10.3 da doc
> técnica e `docs/qa/scripts/README.md`).

Regras do parser (para referência ao ler os cenários abaixo):

- Padrão principal: `^(\d+(?:[.,]\d+)?)\s*(ALIAS)\s+(.+)$` (case-insensitive), onde
  `ALIAS` vem do dicionário `UNIT_ALIASES` (ampliado no pacote de ajustes pós-call,
  2026-07-29 — ver LP-15 a LP-19 abaixo). Forma-padrão salva em `unidade` → aliases
  aceitos:

  | forma-padrão | aliases aceitos |
  |---|---|
  | `un` | un, und, unid, unidade, unidades |
  | `fd` | fd, f, fardo, fardos |
  | `cx` | cx, cxa, caixa, caixas |
  | `pct` | pct, pacote, pacotes |
  | `kg` | kg, kilo, quilo, kilos, quilos |
  | `g` | g, gr, grama, gramas |
  | `ml` | ml, mililitro |
  | `l` | l, lt, litro, litros |
  | `dz` | dz, dzs, dúzia, dúzias, duzia, duzias |
  | `galao` | galao, galão, gl |
  | `fr` | fr, frasco, frascos |
  | `gf` | gf, garrafa, garrafas |
  | `lata` | lata, latas |
  | `pt` | pote, potes, pt |
  | `sc` | sc, saco, sacos |
  | `rl` | rl, rolo, rolos |
  | `ta` | tambor, tambores, ta |
  | `bandej` | bandej, bandeja, bandejas, bdj |
  | `disp` | display, disp |
  | `kit` | kit, kits |
  | `cento` | cento, centos |

- Se a unidade não bate com nenhum alias acima, ou não há espaço entre quantidade e
  unidade, o parser **descarta a quantidade digitada** e usa `quantidade=1`,
  `unidade=un`, e a **linha inteira** como nome do produto para matching — o
  fallback nunca rejeita a linha, mesmo com o dicionário maior.
- Linha que é só um número é **ignorada silenciosamente** (não vira item).
- Linha sem número no início também vira `quantidade=1`, `unidade=un`.
- Vírgula ou ponto são aceitos como separador decimal.
- `texto_original` (guardado em `cotacao_produto` e reaproveitado depois, ver
  documento 03) é sempre a **linha inteira como colada**, nunca só o nome do produto.

Todos os blocos abaixo podem ser colados direto no campo "Lista de produtos" e
"Adicionar itens".

---

**LP-01 — Todas as unidades reconhecidas, uma por linha**
**Descrição:** confirma que as 9 unidades da regex são todas aceitas e minúsculizadas
corretamente, cada uma com nome de produto plausível.
**Cole isto:**
```
5un arroz agulhinha tipo 1
3und feijão carioca 1kg
2fd papel toalha bobina
4cx leite integral 1l
6pct macarrão espaguete 500g
10kg açúcar refinado
3l água mineral sem gás
3lt óleo de soja
12dz ovos brancos grandes
```
**Resultado esperado:** 9 itens processados, um por linha, cada um com a unidade
exatamente como escrita (minúscula) e quantidade correspondente. Nenhum vira
"não identificado no catálogo" por causa da unidade (a unidade sempre é reconhecida
aqui — "não identificado" viria só de falha de matching contra o catálogo).

---

**LP-02 — Unidade em maiúscula/mista (case-insensitive)**
**Descrição:** confirma que a regex de unidade é case-insensitive e o valor salvo
sai sempre em minúsculo.
**Cole isto:**
```
5UN arroz tipo 1
3Kg Açúcar Refinado
2Cx Leite Integral 1L
```
**Resultado esperado:** mesmos 3 itens de antes; campo `unidade` salvo como `un`,
`kg`, `cx` (minúsculo), independente de como foi digitado.

---

**LP-03 — Quantidade com vírgula decimal**
**Descrição:** confirma a conversão de vírgula para ponto na quantidade.
**Cole isto:**
```
2,5kg arroz tipo 1
1,750kg carne moída
0,5l vinagre de álcool
```
**Resultado esperado:** quantidades interpretadas como `2.5`, `1.750`, `0.5`.

---

**LP-04 — Quantidade com ponto decimal (formato já americano)**
**Descrição:** confirma que ponto também é aceito diretamente (não só vírgula).
**Cole isto:**
```
2.5kg arroz tipo 1
10.25kg farinha de trigo
```
**Resultado esperado:** quantidades `2.5` e `10.25`, sem erro de parsing.

---

**LP-05 — 🔍 Unidade não reconhecida (gotcha: quantidade é descartada)**
**Descrição:** "caixas", "pacotes", "litros", "unidades" por extenso **não** estão na
lista de unidades reconhecidas — só as abreviações. Isso descarta a quantidade
digitada inteira, não só a unidade.
**Cole isto:**
```
3 caixas de leite integral
5 pacotes de bolacha maria
2 litros de óleo
10 unidades de sabonete
```
**Resultado esperado (comportamento real do parser, não um bug a corrigir sem
discussão):** todos os 4 itens viram `quantidade=1`, `unidade=un`, e o nome usado
para matching é a **linha inteira** (ex.: "3 caixas de leite integral" completo, não
"leite integral"), o que tende a piorar a qualidade do match contra o catálogo.
Confirmar visualmente que a quantidade "3", "5", "2", "10" **não** aparece refletida
em nenhum campo estruturado do item processado.

---

**LP-06 — 🔍 Unidade grudada sem espaço e não reconhecida**
**Descrição:** variante do LP-05 sem nenhum espaço entre número e unidade — cai num
caminho de regex diferente (nem o padrão principal nem o fallback "número + espaço +
texto" batem), mas produz o **mesmo resultado final**.
**Cole isto:**
```
2lts água mineral
5mls álcool em gel
```
**Resultado esperado:** mesmo resultado do LP-05 — `quantidade=1`, `unidade=un`, nome
= linha inteira ("2lts água mineral", "5mls álcool em gel"). Nenhuma das duas colunas
soa como abreviação típica esperada ("lts"/"mls" não estão na lista — só "l"/"lt"/"ml"
não existe nem nessa lista, curiosamente **"ml" não é uma unidade reconhecida** pelo
parser de lista, só "l"/"lt").

---

**LP-07 — Linha sem número no início**
**Descrição:** produto digitado sem nenhuma quantidade.
**Cole isto:**
```
leite integral
detergente neutro
```
**Resultado esperado:** ambos viram `quantidade=1`, `unidade=un`, nome = a própria
linha.

---

**LP-08 — Linhas que são só um número (descartadas)**
**Descrição:** confirma que uma linha só numérica nunca vira item, silenciosamente.
**Cole isto:**
```
15un sazon legumes 60g
42
3,5
2cx leite integral 1l
```
**Resultado esperado:** apenas **2 itens** processados (a linha "15un sazon legumes
60g" e "2cx leite integral 1l") — "42" e "3,5" desaparecem sem gerar item nem erro.

---

**LP-09 — Linhas em branco misturadas na lista**
**Descrição:** confirma que linhas vazias entre itens são ignoradas sem quebrar a
ordem dos demais.
**Cole isto:**
```
15un sazon legumes 60g

2cx leite integral 1l


5kg açúcar refinado
```
**Resultado esperado:** 3 itens processados, na ordem em que aparecem (sazon,
leite, açúcar), sem item vazio no meio nem erro por causa das linhas em branco.

---

**LP-10 — Espaços extras ao redor de quantidade/unidade/nome**
**Descrição:** confirma que espaços múltiplos entre os campos e no início/fim da
linha não quebram o parsing nem ficam no nome salvo.
**Cole isto:**
```
   15un   sazon legumes 60g   
3cx    leite   integral   1l
```
**Resultado esperado:** ambos processados normalmente (quantidade 15/3, unidade
un/cx); o nome do produto não deve ter espaços sobrando no início/fim (mas espaços
duplos **entre** palavras do nome, se houver, podem persistir — o parser só faz
`.strip()`, não colapsa espaços internos).

---

**LP-11 — Acentos e caracteres especiais preservados no nome**
**Descrição:** confirma que o texto do produto não é normalizado/alterado na
gravação — normalização (remoção de acento, minúsculo) só acontece na hora do
matching, não no texto salvo.
**Cole isto:**
```
3un ração canina raça grande 15kg
2cx pão de queijo congelado 1kg
5un café torrado moído extra forte 500g
```
**Resultado esperado:** o texto exibido em "Produtos já adicionados" mantém os
acentos exatamente como digitado ("ração", "pão", "café") — nenhuma normalização
visível na UI.

---

**LP-12 — Lote grande, todos os casos de borda juntos**
**Descrição:** cenário de carga/regressão combinando várias regras acima numa única
submissão — útil pra checar performance percebida e se a ordem final bate com a
ordem de digitação (campo `ordem`).
**Cole isto:**
```
15un sazon legumes 60g
3caixas de bolacha recheada
leite integral sem marca
2,5kg arroz tipo 1 tradicional
42
3cx refrigerante lata 350ml
5pct macarrão instantâneo galinha caipira
3,5
1dz ovos caipira
0,750kg queijo mussarela fatiado
10un guardanapo de papel folha dupla
```
**Resultado esperado:** 9 itens processados (as duas linhas só-número, "42" e "3,5",
somem); ordem final segue exatamente a ordem das linhas não descartadas acima;
"3caixas de bolacha recheada" e "leite integral sem marca" caem na regra do LP-05/LP-
07 (quantidade 1, nome = linha inteira para a primeira).

---

**LP-13 — Reenvio da mesma lista (upsert, não duplica)**
**Descrição:** complementa ENT-08 do plano de QA geral — útil ter o texto exato
pronto pra colar duas vezes seguidas.
**Cole isto (colar, "Adicionar itens", colar de novo idêntico, "Adicionar itens" outra
vez):**
```
15un sazon legumes 60g
2cx leite integral 1l
```
**Resultado esperado:** depois do 2º envio idêntico, a lista "Produtos já
adicionados" continua mostrando só **2** itens (não 4) — o backend faz upsert por
texto original (produto ainda não conciliado) ou por `produtoId` (se já conciliado
com o catálogo em uma submissão anterior), preservando id/ordem.

---

**LP-14 — Reenvio com quantidade alterada no mesmo texto**
**Descrição:** variante do LP-13 testando que a atualização de quantidade é
refletida quando o produto já está conciliado com o catálogo (dedup por
`produtoId`).
**Pré-condição:** já existe um produto no catálogo cujo nome dá match forte com
"sazon legumes 60g" (rodar LP-13 antes ajuda a criar esse histórico).
**Cole isto (colar a 1ª linha, "Adicionar itens", depois colar só a 2ª e "Adicionar
itens" de novo):**
```
10un sazon legumes 60g
```
```
25un sazon legumes 60g
```
**Resultado esperado:** depois do 2º envio, continua havendo **1** item para esse
produto na cotação, agora com quantidade `25` (não 10, não 35) — a linha foi
atualizada, não duplicada.

---

## Pacote de ajustes pós-call (2026-07-29) — dicionário de unidades ampliado

**LP-15 — Unidades de embalagem novas (fardo por extenso, galão, garrafa, saco,
display)**
**Descrição:** as 5 unidades citadas no checklist do cliente — confirma que a forma
por extenso/aliases entram como alias reconhecido, não só a abreviação de 1-2
letras que já existia antes.
**Cole isto:**
```
3fardo arroz agulhinha tipo 1
2galao agua mineral crystal
5garrafa vinho tinto seco
10saco cimento votoran
1display bala fini morango
```
**Resultado esperado:** 5 itens processados, nenhum com aviso de formato incorreto;
`unidade` salva como `fd`, `galao`, `gf`, `sc`, `disp` respectivamente (forma-padrão
curta, não o alias digitado).

---

**LP-16 — Unidades de peso/volume novas (grama, mililitro, dúzia com "s")**
**Cole isto:**
```
500g queijo mussarela fatiado
250gr presunto cozido fatiado
2mililitro essencia baunilha
6dzs ovos caipira
```
**Resultado esperado:** 4 itens processados; `unidade` salva como `g`, `g`, `ml`,
`dz` respectivamente; `gr` e `g` mapeiam pro mesmo padrão (`g`).

---

**LP-17 — Unidades novas menos comuns (frasco, lata, pote, rolo, tambor, cento,
kit)**
**Cole isto:**
```
12frasco shampoo anticaspa
24lata sardinha em oleo
6pote iogurte natural
4rolo papel toalha industrial
1tambor detergente industrial 20l
2cento pao frances
3kit ferramentas basico
```
**Resultado esperado:** 7 itens processados, nenhum aviso de formato; `unidade`
salva como `fr`, `lata`, `pt`, `rl`, `ta`, `cento`, `kit` respectivamente.

---

**LP-18 — Ambiguidade "lt"/"l": sempre litro, nunca lata**
**Descrição:** o cliente citou que "lt" raramente também poderia significar "lata"
— confirma que a prioridade documentada (litro sempre vence) está implementada.
**Cole isto:**
```
2lt oleo de soja
3l agua mineral sem gas
```
**Resultado esperado:** ambos com `unidade=l` (litro) — nenhum vira `lata`, mesmo
"lt" isoladamente sendo uma abreviação ambígua no mundo real.

---

**LP-19 — Variações de acento e plural nas unidades já existentes**
**Descrição:** confirma que a normalização (remove acento, minúsculo) do
dicionário ampliado continua funcionando igual à dos aliases antigos.
**Cole isto:**
```
2duzia banana prata
1dúzia laranja pera
3unid detergente lava louça
4galão água de coco
```
**Resultado esperado:** 4 itens processados; `unidade` salva como `dz`, `dz`, `un`,
`galao` respectivamente — acento presente ou ausente dá o mesmo resultado.

---

**LP-20 — Unidade genuinamente não reconhecida continua com fallback preservado**
**Descrição:** confirma que o dicionário maior não quebrou o fallback documentado
no topo deste arquivo — nenhuma linha é rejeitada, mesmo com uma unidade
inventada.
**Cole isto:**
```
3caixote de leite
2braça de corda
```
**Resultado esperado:** 2 itens processados (não descartados); `quantidade=1`,
`unidade=un`, nome do produto = linha inteira ("3caixote de leite" e "2braça de
corda") — mesmo comportamento de LP-05, "caixote" e "braça" não estão em nenhuma
lista de alias.
