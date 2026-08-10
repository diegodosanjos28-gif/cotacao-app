# Inputs — Resposta do fornecedor (Entrada de Dados, bloco do fornecedor atual)

Cenários de input para o campo de colar a resposta do fornecedor, antes de clicar
"Processar Cotação" (`POST /cotacoes/{id}/fornecedores/{fornId}/resposta`, processado
por
`backend/src/main/java/com/prx/cotacao/cotacao/parser/ParserRespostaFornecedorService.java`).
Estes cenários testam só a camada de **parsing** da resposta (preço, sem estoque,
linhas a ignorar) — não dependem de ter uma lista base específica na cotação. Para
cenários que testam o **cruzamento** com a lista base (Conferência: marca diferente,
gramagem diferente, etc.), ver `03-conferencia-classificacao-inputs.md`.

Convenção usada abaixo: qualquer uma dessas respostas pode ser colada isoladamente
(ela vai gerar candidatos "extra" ou "não identificado" na Conferência se não houver
item base correspondente — o que já é suficiente para confirmar o comportamento do
parser em si).

> Os blocos abaixo são pensados pra colagem direta no campo web, sem header. Se algum
> deles for usado via `/dev/whatsapp/simular` (canal WhatsApp real, que passa pelo
> `ClassificadorMensagemWhatsapp`), prefixe manualmente `RESPOSTA_FORNECEDOR\n` +
> nome do fornecedor na linha seguinte — sem isso a mensagem é rejeitada como
> "formato não reconhecido" (ver seção 10.3 da doc técnica e
> `docs/qa/scripts/README.md`).

---

**RF-01 — Formatos de preço aceitos**
**Descrição:** confirma as variações de formatação de `R$` que o parser reconhece:
vírgula decimal, ponto decimal, 1 casa decimal só, e separador de milhar BR.
**Cole isto:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$6.99
Arroz Tipo 1 5kg - R$ 4,5
Combo Vinho Premium 12un - R$ 1.234,56
```
**Resultado esperado:** 4 preços extraídos corretamente: `4.89`, `6.99`, `4.50` (ou
equivalente com 1 casa), `1234.56` (o ponto do milhar é removido, a vírgula vira o
separador decimal — **não** confundir com `1.234` + `56`).

---

**RF-02 — Preço sem hífen antes do R$ (só espaço)**
**Descrição:** confirma que o hífen `-` não é obrigatório entre nome e preço.
**Cole isto:**
```
Sazon Legumes 60g R$ 4,89
```
**Resultado esperado:** preço `4.89` extraído normalmente, nome "Sazon Legumes 60g".

---

**RF-03 — Texto extra depois do preço ("cada", "/un")**
**Descrição:** confirma que texto após o valor não invalida a extração (o regex de
preço usa `find()`, não `matches()`).
**Cole isto:**
```
Sazon Legumes 60g - R$ 4,89 cada
Leite Integral 1L - R$ 6,99/un
Arroz Tipo 1 5kg - R$ 22,50 a unidade
```
**Resultado esperado:** os 3 preços extraídos normalmente (`4.89`, `6.99`, `22.50`);
o texto "cada"/"/un"/"a unidade" é descartado, não aparece em lugar nenhum
(não é acrescentado ao nome nem gera erro).

---

**RF-04 — Variantes de "sem estoque"**
**Descrição:** confirma as 4 expressões reconhecidas pelo padrão de sem-estoque.
**Cole isto:**
```
Leite Integral 1L - sem estoque
Arroz Tipo 1 5kg - não tem
Feijão Preto 1kg - nao tem
Macarrão Espaguete 500g - indisponível
Café Torrado 500g - falta
```
**Resultado esperado:** as 5 linhas marcadas como `semEstoque=true`, sem preço; o
nome do produto extraído remove a expressão de sem-estoque (ex.: "Leite Integral
1L", não "Leite Integral 1L - sem estoque").

---

**RF-05 — Linhas de "consultar"/"a combinar" (preço pendente)**
**Descrição:** confirma as 5 expressões que marcam preço ainda não informado
(`precoPendente=true`) — ver também RF-conferência PACKAGE_PRICE_SUSPECTED no
documento 03 e ENT-34 do plano de QA geral.
**Cole isto:**
```
Whisky Importado 1L - consultar
Vinho Reserva Especial 750ml - a combinar
Produto Especial da Casa - sob consulta
Item Sazonal Fora de Linha - ver preço
Encomenda Especial 5kg - sob demanda
```
**Resultado esperado:** as 5 linhas sem preço extraído, marcadas como "preço
pendente" — na Conferência, aparecem em Revisar com o candidato de preço
desabilitado (exige "Editar manualmente" pra confirmar, não é possível "aceitar
sugestão" sem preço).

---

**RF-06 — Linhas de saudação/cabeçalho/logística (ignoradas)**
**Descrição:** confirma o filtro de linhas que nunca viram produto, mesmo se
estiverem misturadas com linhas de produto de verdade.
**Cole isto:**
```
Bom dia!
Segue nossa cotação:
Sazon Legumes 60g - R$ 4,89
Prazo de entrega: 3 dias úteis
Forma de pagamento: 28 dias
Leite Integral 1L - R$ 6,99
Qualquer dúvida estou à disposição.
Atenciosamente,
João da Distribuidora ABC
```
**Resultado esperado:** apenas **2** linhas de produto processadas (Sazon Legumes,
Leite Integral) — todas as demais são descartadas silenciosamente por conterem
"bom dia", "prazo", "pagamento", "atenciosamente" (a 1ª linha "Bom dia!" também é
tratada à parte, como possível nome do fornecedor — ver RF-08).

---

**RF-07 — 🔍 Gotcha: substring "att" derruba a linha inteira**
**Descrição:** o filtro de linhas a ignorar usa `find()` (substring, sem `\b` de
borda de palavra) para a palavra "att" (de "atenciosamente"/abreviação "Att,"). Isso
derruba **qualquer linha que contenha a sequência de 3 letras "att" em qualquer
lugar**, mesmo dentro do nome de um produto. Cenário sintético pra expor o mecanismo
exato — "Attos" é um nome fictício escolhido de propósito por conter "att".
**Cole isto:**
```
Refrigerante Attos Cola 2L - R$ 6,50
```
**Resultado esperado (achado a confirmar rodando de verdade, não apenas leitura de
código):** a linha inteira é descartada como se fosse uma linha de despedida
("Att," / "Atenciosamente") — o produto "Refrigerante Attos Cola 2L" **não aparece**
na Conferência, preço R$ 6,50 perdido silenciosamente, sem nenhum aviso ao operador.
Se confirmado, vale reportar como achado de parsing (falso positivo por substring
sem borda de palavra).

---

**RF-08 — Primeira linha como nome do fornecedor (só se não parecer produto)**
**Descrição:** confirma a heurística que decide se a 1ª linha não-vazia é o nome do
fornecedor ou já é um produto.
**Cole isto (cenário A — 1ª linha vira nome do fornecedor):**
```
Distribuidora Boa Vista Ltda
Sazon Legumes 60g - R$ 4,89
```
**Cole isto (cenário B — 1ª linha JÁ é produto, não vira nome de fornecedor):**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Resultado esperado:** no cenário A, "Distribuidora Boa Vista Ltda" não vira
produto (não tem preço nem "sem estoque" — heurística `pareceLinhaDeProduto`
detecta que não é produto); só 1 item processado (Sazon). No cenário B, como a 1ª
linha já tem `R$`, ela é tratada como produto desde já — **2** itens processados,
nenhum "engolido" como nome de fornecedor.

---

**RF-09 — Múltiplos produtos numa linha, separados por `|`**
**Descrição:** confirma o desmembramento de linha só quando pelo menos 2 das partes
têm preço reconhecível.
**Cole isto:**
```
Sazon Legumes 60g R$ 4,89 | Leite Integral 1L R$ 6,99 | Arroz Tipo 1 5kg R$ 22,50
```
**Resultado esperado:** **3** linhas independentes processadas a partir dessa única
linha colada, cada uma com seu nome e preço corretos.

---

**RF-10 — Múltiplos produtos numa linha, separados por `;`**
**Descrição:** mesmo mecanismo do RF-09, separador `;`.
**Cole isto:**
```
Sazon Legumes 60g R$ 4,89; Leite Integral 1L R$ 6,99
```
**Resultado esperado:** 2 linhas independentes, mesmo comportamento do RF-09.

---

**RF-11 — `|` decorativo que NÃO desmembra (menos de 2 partes com preço)**
**Descrição:** confirma que o desmembramento só ocorre se ≥2 partes tiverem preço —
um `|` usado como separador visual de cabeçalho não deve fragmentar a linha.
**Cole isto:**
```
Distribuidora ABC | Filial Centro | Vendedor: Carlos
```
**Resultado esperado:** a linha **não** é desmembrada (nenhuma das 3 partes tem
preço reconhecível); como também não bate nenhum padrão de produto/sem-
estoque/consultar, a linha inteira vira um item "não identificado" **sem preço**, o
que significa que ela **desaparece silenciosamente** da Conferência (não vira
"extra" porque não tem preço — ver documento 03, cenário "linha sem preço
reconhecível").

---

**RF-12 — Quantidade/unidade informada pelo fornecedor no início da linha**
**Descrição:** confirma a extração (só informativa, não altera a interpretação do
preço) de quantidade+unidade quando o próprio fornecedor as inclui na resposta.
**Cole isto:**
```
3 cx Sazon Legumes 60g - R$ 4,89
12 un Leite Integral 1L - R$ 6,99
```
**Resultado esperado:** preço e nome extraídos normalmente, ignorando o prefixo de
quantidade/unidade para fins de preço (nome extraído ainda inclui "3 cx " no início,
já que a extração de nome só corta a partir do `R$`, não remove esse prefixo).

---

**RF-13 — Detecção de embalagem por texto ("caixa com N")**
**Descrição:** confirma a extração de tipo de embalagem + quantidade interna a
partir do texto da resposta (usada depois na Conferência para PACKAGE_QTY_ADDED/
CHANGED, ver documento 03).
**Cole isto:**
```
Refrigerante Lata 350ml - Caixa com 12 - R$ 45,00
Detergente Neutro 500ml - Fardo com 6 - R$ 18,00
Bolacha Recheada 130g - Pacote com 24 - R$ 60,00
```
**Resultado esperado:** cada linha detecta corretamente o tipo de embalagem
(caixa/fardo/pacote) e a quantidade interna (12/6/24) — informação que não altera o
preço lido (`R$ 45,00`/`18,00`/`60,00` continuam sendo o preço da linha inteira, não
dividido pela quantidade interna).

---

**RF-14 — Marca reconhecida da lista fixa vs. heurística de palavra capitalizada**
**Descrição:** confirma os dois caminhos de `extrairMarca` — lista fixa de marcas
conhecidas (~70 marcas) e, se não achar nenhuma, a heurística "primeira palavra
capitalizada que não é uma das palavras excluídas (Com/Sem/Para/Tipo/Sabor/Lata/
Pet/Garrafa/Vidro)".
**Cole isto:**
```
Perdigão Frango Congelado 1kg - R$ 12,50
Toddynho Achocolatado 200ml - R$ 2,50
Com Amor Temperos Especiais 100g - R$ 5,00
```
**Resultado esperado:** "Perdigão" reconhecida via lista fixa (marca = "perdigao",
sem acento); "Toddynho" reconhecida via heurística (não está na lista fixa, mas é a
1ª palavra capitalizada válida); na 3ª linha, "Com" é uma palavra excluída da
heurística — a marca extraída deve ser "Amor" (a próxima palavra capitalizada
válida), não "Com".

---

**RF-15 — Nenhum preço, nenhum sem-estoque, nenhum "consultar" (linha órfã)**
**Descrição:** 🔍 gotcha relevante — uma linha de produto sem NENHUM sinal
reconhecível (preço, sem estoque, ou consultar) fica com `preco=null` e
**desaparece silenciosamente** da Conferência (nem vira "extra", que exige preço
não-nulo — ver documento 03).
**Cole isto:**
```
Sazon Legumes 60g
```
**Resultado esperado (achado a confirmar):** essa linha isolada não gera nenhum
item visível na Conferência, mesmo que o produto exista na lista base da cotação —
o operador não tem como saber que essa linha foi "perdida" olhando só a tela de
Conferência. Testar combinada com uma lista base que contenha exatamente esse
produto, pra confirmar que ele fica de fora tanto da Conferência quanto do
Comparativo (aparece como "sem cotação ainda" para esse fornecedor, sem nenhum
aviso de que a linha foi descartada por falta de preço).
