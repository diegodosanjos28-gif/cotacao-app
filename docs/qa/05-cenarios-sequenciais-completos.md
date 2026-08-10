# Cenários completos — Lista + Fornecedores em sequência

Cada cenário abaixo é uma cotação inteira, pronta pra rodar do início ao fim: cole a
**Lista de produtos** uma vez, depois adicione cada fornecedor na ordem e cole a
resposta dele (fluxo sequencial: Processar Cotação → resolver Revisar se houver →
Confirmar e Processar → só então libera o próximo fornecedor). São **23 cenários**,
cada um testando uma combinação diferente do sistema (preço, cobertura, alertas,
Mapa de Compra) — o Cenário 23 cobre o pacote de ajustes pós-call de 2026-07-29.

Nomes de fornecedor sugeridos — use-os ao cadastrar, ou troque à vontade.

> Os blocos abaixo são pensados pra colagem direta nos campos web, sem header. Se
> algum bloco for reaproveitado dentro de `simular "..."` em
> `docs/qa/scripts/02-*.sh`/`03-*.sh` (canal WhatsApp real, que passa pelo
> `ClassificadorMensagemWhatsapp`), a chamada `simular()` precisa prefixar
> `LISTA_PRODUTOS\n` (lista) ou `RESPOSTA_FORNECEDOR\n` (resposta de fornecedor)
> manualmente — sem isso a mensagem é rejeitada como "formato não reconhecido" (ver
> seção 10.3 da doc técnica e `docs/qa/scripts/README.md`).

---

## Cenário 01 — Feliz caminho, tudo cotado, sem alertas
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
3kg arroz tipo 1
2pct macarrão espaguete 500g
4un detergente neutro 500ml
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
Arroz Tipo 1 5kg - R$ 22,50
Macarrão Espaguete 500g - R$ 3,20
Detergente Neutro 500ml - R$ 2,80
```
**Fornecedor 2 — Atacadão Boa Vista:**
```
Sazon Legumes 60g - R$ 4,79
Leite Integral 1L - R$ 6,89
Arroz Tipo 1 5kg - R$ 21,90
Macarrão Espaguete 500g - R$ 3,10
Detergente Neutro 500ml - R$ 2,75
```
**Testa:** cotação simples com 2 fornecedores cotando tudo, preços próximos —
Comparativo sem "Alta Variação", Alertas sem críticos.

---

## Cenário 02 — Um item nunca cotado por ninguém
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
2un queijo prato fatiado 200g
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Fornecedor 2 — Atacadão Boa Vista:**
```
Sazon Legumes 60g - R$ 4,79
Leite Integral 1L - R$ 6,89
```
**Testa:** "Queijo Prato Fatiado" nunca aparece em nenhuma resposta → ALR-02
(Crítico: produto sem cotação), badge "sem cotação ainda" na lista de Entrada.

---

## Cenário 03 — Alta variação de preço entre fornecedores (>20%)
**Lista de produtos:**
```
5un azeite de oliva extra virgem 500ml
3kg café torrado moído
```
**Fornecedor 1 — Distribuidora Central:**
```
Azeite de Oliva Extra Virgem 500ml - R$ 18,00
Café Torrado Moído 1kg - R$ 15,00
```
**Fornecedor 2 — Importados Bom Preço:**
```
Azeite de Oliva Extra Virgem 500ml - R$ 32,00
Café Torrado Moído 1kg - R$ 18,50
```
**Testa:** azeite tem diferença de ~78% entre fornecedores → CMP-04 "Alta
Variação", ALR-04 (Atenção), maior economia potencial concentrada nesse item
(ALR-06).

---

## Cenário 04 — Cobertura parcial (só 1 de 3 fornecedores cotando um item)
**Lista de produtos:**
```
10un sazon legumes 60g
2un temperinho completo caseiro
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Temperinho Completo Caseiro - R$ 3,50
```
**Fornecedor 2 — Atacadão Boa Vista:**
```
Sazon Legumes 60g - R$ 4,79
```
**Fornecedor 3 — Mercado São José:**
```
Sazon Legumes 60g - R$ 4,95
```
**Testa:** "Temperinho Completo Caseiro" só tem 1 oferta válida com 3 fornecedores
no total → CMP-04 "Cobertura Parcial" (1 < min(3,3)), ALR-05.

---

## Cenário 05 — Fornecedor abaixo do pedido mínimo (Compra Equilibrada)
**Pré-condição:** cadastrar "Distribuidora Pequena" com pedido mínimo R$ 200,00.
**Lista de produtos:**
```
2un sazon legumes 60g
1cx leite integral 1l
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Fornecedor 2 — Distribuidora Pequena:**
```
Sazon Legumes 60g - R$ 4,50
Leite Integral 1L - R$ 6,50
```
**Testa:** total de "Distribuidora Pequena" (poucos itens baratos) fica bem abaixo
de R$ 200 → MAPA-05/ALR-03 no cenário Compra Equilibrada; no Menor Preço o
fornecedor pode aparecer normalmente mesmo abaixo do mínimo (MAPA-02).

---

## Cenário 06 — Múltiplos itens "sem estoque" de um único fornecedor
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
3kg arroz tipo 1
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - sem estoque
Leite Integral 1L - R$ 6,99
Arroz Tipo 1 5kg - não tem
```
**Fornecedor 2 — Atacadão Boa Vista:**
```
Sazon Legumes 60g - R$ 4,79
Leite Integral 1L - R$ 6,89
Arroz Tipo 1 5kg - R$ 21,90
```
**Testa:** 🔍 achado a confirmar (ver `03-conferencia-classificacao-inputs.md`
CF-12) — os itens "sem estoque" do Fornecedor 1 podem não aparecer em nenhum lugar
da Conferência; confirmar se ao menos aparecem como "—"/sem oferta desse fornecedor
no Comparativo, já que o Fornecedor 2 cobre os mesmos 3 itens normalmente.

---

## Cenário 07 — Vários itens "a combinar"/"consultar" (preço pendente)
**Lista de produtos:**
```
2un whisky importado 1l
1cx vinho reserva especial 750ml
3un champagne nacional
```
**Fornecedor 1 — Adega Premium:**
```
Whisky Importado 1L - consultar
Vinho Reserva Especial 750ml - a combinar
Champagne Nacional - sob consulta
```
**Testa:** os 3 itens caem em Revisar por PACKAGE_PRICE_SUSPECTED, todos com
candidato de preço desabilitado — "Confirmar e Processar" deve continuar
bloqueado até editar manualmente cada um com um preço real (ENT-25, ENT-34).

---

## Cenário 08 — Marca diferente em vários itens do mesmo fornecedor
**Lista de produtos:**
```
5un maggi caldo de galinha temperinho
3un heinz ketchup tradicional 397g
2cx nissin macarrão instantâneo lamen
```
**Fornecedor 1 — Distribuidora Central:**
```
Knorr Caldo de Galinha Temperinho - R$ 3,20
Predilecta Ketchup Tradicional 397g - R$ 4,50
Yoki Macarrão Instantâneo Lamen - R$ 2,10
```
**Testa:** os 3 itens caem em Atenção por BRAND_CHANGED (marca da lista base
diferente da oferecida) — confirmar que "Confirmar e Processar" não bloqueia (só
Revisar bloqueia, Atenção não).

---

## Cenário 09 — Gramagem/volume divergente em vários itens
**Lista de produtos:**
```
10un sazon legumes 60g
3cx leite integral 1l
2pct bolacha recheada 130g
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 120g - R$ 8,90
Leite Integral 500ml - R$ 3,80
Bolacha Recheada 400g - R$ 7,20
```
**Testa:** os 3 itens caem em Revisar por WEIGHT_CHANGED/divergência de volume —
confirmar que o candidato mostra claramente "peso/volume solicitado ≠ oferecido"
antes do operador decidir aceitar ou não.

---

## Cenário 10 — Peso/volume informado quando a lista base não tinha
**Lista de produtos:**
```
3un detergente concentrado
2un amaciante de roupas
```
**Fornecedor 1 — Distribuidora Central:**
```
Detergente Concentrado 900g - R$ 2,10
Amaciante de Roupas 2L - R$ 8,50
```
**Testa:** WEIGHT_ADDED no primeiro item, VOLUME_ADDED no segundo — confirmar que
o sistema escolhe o motivo certo conforme a dimensão (peso vs. volume) da medida
informada.

---

## Cenário 11 — Quantidade por embalagem nova e divergente
**Lista de produtos:**
```
2un refrigerante lata
3un água mineral garrafa caixa com 6
```
**Fornecedor 1 — Distribuidora Central:**
```
Refrigerante Lata - Caixa com 12 - R$ 45,00
Água Mineral Garrafa - Caixa com 12 - R$ 30,00
```
**Testa:** primeiro item = PACKAGE_QTY_ADDED (base não tinha embalagem); segundo
item = PACKAGE_QTY_CHANGED (base tinha "com 6", oferecido tem "com 12").

---

## Cenário 12 — Fornecedor oferece múltiplas opções do mesmo produto
**Lista de produtos:**
```
5un sazon legumes 60g
3cx refrigerante lata 350ml
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g Original - R$ 4,89
Sazon Legumes 60g Light - R$ 5,49
Refrigerante Lata 350ml Cola - R$ 3,20
Refrigerante Lata 350ml Guaraná - R$ 3,00
```
**Testa:** MULTIPLE_OPTIONS nos dois itens — cada um deve mostrar 2 candidatos com
radio button; "Confirmar e Processar" bloqueado até escolher um de cada
(ENT-21/ENT-25).

---

## Cenário 13 — Fornecedor oferece item extra fora da lista
**Lista de produtos:**
```
5un sazon legumes 60g
3cx leite integral 1l
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
Chocolate Amargo 70% Cacau 100g - R$ 9,90
Barra de Cereal Integral 25g - R$ 2,50
```
**Testa:** os 2 últimos itens viram EXTRA_ITEM, sem controle de resolução além de
"Adicionar item à lista"; ao confirmar, os dois somem sem virar
`cotacao_produto_fornecedor` (ENT-28).

---

## Cenário 14 — 4 fornecedores cotando o mesmo conjunto, preços bem distribuídos
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
3kg arroz tipo 1
2pct macarrão espaguete 500g
4un detergente neutro 500ml
2un açúcar refinado 1kg
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
Arroz Tipo 1 5kg - R$ 22,50
Macarrão Espaguete 500g - R$ 3,20
Detergente Neutro 500ml - R$ 2,80
Açúcar Refinado 1kg - R$ 4,10
```
**Fornecedor 2 — Atacadão Boa Vista:**
```
Sazon Legumes 60g - R$ 4,70
Leite Integral 1L - R$ 6,80
Arroz Tipo 1 5kg - R$ 21,50
Macarrão Espaguete 500g - R$ 3,05
Detergente Neutro 500ml - R$ 2,60
Açúcar Refinado 1kg - R$ 3,95
```
**Fornecedor 3 — Mercado São José:**
```
Sazon Legumes 60g - R$ 5,10
Leite Integral 1L - R$ 7,20
Arroz Tipo 1 5kg - R$ 23,00
Macarrão Espaguete 500g - R$ 3,30
Detergente Neutro 500ml - R$ 2,95
Açúcar Refinado 1kg - R$ 4,25
```
**Fornecedor 4 — Comercial Reunidas:**
```
Sazon Legumes 60g - R$ 4,60
Leite Integral 1L - R$ 6,70
Arroz Tipo 1 5kg - R$ 21,90
Macarrão Espaguete 500g - R$ 3,00
Detergente Neutro 500ml - R$ 2,70
Açúcar Refinado 1kg - R$ 3,90
```
**Testa:** aba Fornecedores do Comparativo (ranking, cobertura 100% pros 4),
"Comercial Reunidas" deve vencer a maioria dos itens (CMP-13); bom cenário padrão
pra explorar as 3 abas do Comparativo com dado farto.

---

## Cenário 15 — Cotação grande (10 produtos), teste de volume
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
3kg arroz tipo 1
2pct macarrão espaguete 500g
4un detergente neutro 500ml
2un açúcar refinado 1kg
6un óleo de soja 900ml
3dz ovos brancos grandes
2fd papel toalha bobina
5un sabonete em barra 90g
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
Arroz Tipo 1 5kg - R$ 22,50
Macarrão Espaguete 500g - R$ 3,20
Detergente Neutro 500ml - R$ 2,80
Açúcar Refinado 1kg - R$ 4,10
Óleo de Soja 900ml - R$ 7,90
Ovos Brancos Grandes - R$ 12,00
Papel Toalha Bobina - R$ 9,50
Sabonete em Barra 90g - R$ 1,80
```
**Fornecedor 2 — Atacadão Boa Vista:**
```
Sazon Legumes 60g - R$ 4,70
Leite Integral 1L - R$ 6,80
Arroz Tipo 1 5kg - R$ 21,50
Macarrão Espaguete 500g - R$ 3,05
Detergente Neutro 500ml - R$ 2,60
Açúcar Refinado 1kg - R$ 3,95
Óleo de Soja 900ml - R$ 7,60
Ovos Brancos Grandes - R$ 11,50
Papel Toalha Bobina - R$ 9,10
Sabonete em Barra 90g - R$ 1,70
```
**Testa:** performance percebida da UI com 10 itens × 2 fornecedores; scroll da
Conferência; export/impressão do Comparativo com tabela maior.

---

## Cenário 16 — Cotação mínima (1 produto só)
**Lista de produtos:**
```
1un sazon legumes 60g
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
```
**Testa:** caminho mínimo possível — Comparativo/Mapa/Alertas com 1 item só, sem
quebrar nenhum card (economia potencial deve ser R$ 0,00 já que só há 1 oferta,
sem "menor vs. maior" pra comparar).

---

## Cenário 17 — Prazos de entrega bem diferentes (cenário Melhor Prazo do Mapa)
**Pré-condição:** cadastrar os 3 fornecedores com prazo de entrega diferente:
"Entrega Rápida Ltda" = `1 dia útil`, "Distribuidora Central" = `5 dias úteis`,
"Fornecedor Sem Prazo" = `entrega imediata` (não numérico — vira "prazo
desconhecido", ordenado por último).
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
```
**Fornecedor 1 — Entrega Rápida Ltda:**
```
Sazon Legumes 60g - R$ 5,20
Leite Integral 1L - R$ 7,30
```
**Fornecedor 2 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Fornecedor 3 — Fornecedor Sem Prazo:**
```
Sazon Legumes 60g - R$ 4,50
Leite Integral 1L - R$ 6,50
```
**Testa:** no cenário Melhor Prazo do Mapa, "Entrega Rápida Ltda" deve vencer
apesar do preço mais alto; "Fornecedor Sem Prazo" some pro fim do ranking mas
continua elegível (MAPA-03), nunca excluído.

---

## Cenário 18 — Reconfirmação de fornecedor com resposta alterada
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
```
**Fornecedor 1 — Distribuidora Central (1ª resposta, processar e confirmar):**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Fornecedor 1 — Distribuidora Central (voltar ao bloco dele, colar de novo com
preço atualizado, processar e confirmar de novo):**
```
Sazon Legumes 60g - R$ 4,50
Leite Integral 1L - R$ 6,60
```
**Testa:** `cotacao_fornecedor` volta pra PROCESSADO ao reprocessar (ENT-29);
depois de reconfirmar, o Comparativo deve refletir só os preços novos (4,50/6,60),
não os antigos.

---

## Cenário 19 — Cotação pronta para finalizar sem alertas (checkbox não aparece)
**Lista de produtos:**
```
5un sazon legumes 60g
3cx leite integral 1l
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Testa:** MAPA-13 — com 1 fornecedor só e sem pedido mínimo cadastrado, "Concluir
Ordem de Compra" não deve pedir nenhum checkbox de ciência; finalizar direto e
conferir que a tela vira somente-leitura (MAPA-17).

---

## Cenário 20 — Cotação para finalizar COM fornecedor abaixo do mínimo (checkbox obrigatório)
**Pré-condição:** cadastrar "Fornecedor Mínimo Alto" com pedido mínimo R$ 500,00.
**Lista de produtos:**
```
2un sazon legumes 60g
1cx leite integral 1l
```
**Fornecedor 1 — Fornecedor Mínimo Alto:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L - R$ 6,99
```
**Testa:** MAPA-14 — no cenário Compra Equilibrada, ao tentar "Concluir Ordem de
Compra", o checkbox "Estou ciente que Fornecedor Mínimo Alto ficará abaixo do
pedido mínimo" deve aparecer e ser obrigatório antes de habilitar a confirmação.

---

## Cenário 21 — Combo "tudo junto": marca + gramagem + embalagem + múltiplas opções + item extra
**Lista de produtos:**
```
5un maggi caldo de galinha temperinho
10un sazon legumes 60g
2un refrigerante lata
3un detergente concentrado
```
**Fornecedor 1 — Distribuidora Central:**
```
Knorr Caldo de Galinha Temperinho - R$ 3,20
Sazon Legumes 120g - R$ 8,90
Sazon Legumes 60g Original - R$ 4,89
Refrigerante Lata - Caixa com 12 - R$ 45,00
Detergente Concentrado 900g - R$ 2,10
Chocolate Amargo 70% Cacau 100g - R$ 9,90
```
**Testa:** um único fornecedor gerando ao mesmo tempo BRAND_CHANGED (caldo),
MULTIPLE_OPTIONS (sazon legumes recebe 2 candidatos — o de 120g fica separado por
divergência de volume e o de 60g "original" bate certo, dependendo de como o
agrupamento reparte), PACKAGE_QTY_ADDED (refrigerante), WEIGHT_ADDED (detergente)
e EXTRA_ITEM (chocolate) — bom cenário de estresse pra Conferência mostrando
vários tipos de badge/motivo na mesma tela ao mesmo tempo. Resultado exato de
qual candidato "sazon" cai onde deve ser conferido na tela, não assumido.

---

## Cenário 22 — Preços com formatos variados no mesmo fornecedor (robustez de parsing)
**Lista de produtos:**
```
10un sazon legumes 60g
5cx leite integral 1l
3kg arroz tipo 1
2pct macarrão espaguete 500g
1un combo vinho premium 12un
```
**Fornecedor 1 — Distribuidora Central:**
```
Sazon Legumes 60g - R$ 4,89
Leite Integral 1L R$6.99
Arroz Tipo 1 5kg - R$ 22,5
Macarrão Espaguete 500g - R$ 3,20 cada
Combo Vinho Premium 12un - R$ 1.234,56
```
**Testa:** 5 variações de formatação de preço na mesma resposta (vírgula, ponto,
sem hífen, 1 casa decimal, texto extra "cada", separador de milhar BR) — todos
devem ser extraídos corretamente sem nenhum virar R$ 0,00 ou erro (ver RF-01/RF-02/
RF-03 do documento 02).

---

## Cenário 23 — Pacote de ajustes pós-call (2026-07-29): item sem preço, múltiplas
opções com spin-off, e preço suspeito por referência, tudo na mesma cotação

**Lista de produtos:**
```
3un feijão carioca tipo 1
5un sazon legumes 60g
2un sal grosso moído
3fardo arroz agulhinha tipo 1
```

**Fornecedor 1 — Distribuidora Central:**
```
Feijão Carioca Tipo 1 - R$ 8,00
Sazon Legumes 60g Original - R$ 4,89
Sazon Legumes 60g Light - R$ 5,49
Sal Grosso Moído
Arroz Agulhinha Tipo 1 - R$ 22,00
```

**Testa (Fornecedor 1):** `sazon legumes` vira **MULTIPLE_OPTIONS** (2 candidatos —
resolver selecionando "Original" como vencedor e, no candidato "Light" não
selecionado, testar tanto "Excluir" quanto "Adicionar como novo item na lista base",
ver CF-14); `sal grosso moído` casa mas sem nenhum preço reconhecível — não aparece
como pendência (**B.2**, não bloqueia a confirmação, mesmo com o resto da resposta
tendo itens Revisar); `feijão`/`arroz` confirmam OK diretamente (score alto, sem
divergência); `fardo` na lista base é reconhecido como alias de unidade (**A.3**,
não vira "não identificado" por causa da unidade). Confirmar tudo antes de seguir
pro Fornecedor 2.

**Fornecedor 2 — Atacadão Boa Vista:**
```
Feijão Carioca Tipo 1 - R$ 13,00
Arroz Agulhinha Tipo 1 - R$ 21,50
```

**Testa (Fornecedor 2):** `feijão` a R$13,00 é 1,625x o preço já confirmado do
Fornecedor 1 (R$8,00) — ≥1,5x, então entra **REVISAR**/`PACKAGE_PRICE_SUSPECTED`
**dentro da própria Conferência** deste fornecedor (**B.3**, não só como badge no
Comparativo depois); resolver informando "Unid./embalagem" (qualquer valor, ex.
`12`) e confirmar. `arroz` a R$21,50 fica bem abaixo de 1,5x o preço do Fornecedor 1
(R$22,00 × 1,5 = R$33,00) — confirma direto, sem motivo.

**Depois de confirmar o Fornecedor 2, reprocessar e reconfirmar ele de novo** com o
mesmo texto, mas informando "Unid./embalagem" = `6` desta vez (valor diferente do
`12` já gravado) — **B.0**: a confirmação não deve falhar, e o snapshot
(`embalagem_qtd_confirmada`) deve continuar `12`, não virar `6`.

**Resultado esperado no Comparativo (depois de tudo confirmado):** "sal grosso
moído" aparece com "—" para os dois fornecedores (nenhum informou preço); "sazon
legumes 60g" tem 1 linha na lista base (Original) mais o item novo criado pelo
spin-off (se o operador escolheu "Adicionar como novo item" no Fornecedor 1); badge
"Possível preço de caixa/fardo" no "feijão carioca" do Atacadão Boa Vista (mesmo
critério de 1,5x, unificado com o gatilho da Conferência via `PrecoReferenciaService`).
