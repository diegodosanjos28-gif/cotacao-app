# Inputs — Outros campos de texto livre

Campos de texto livre fora do fluxo principal de colar lista/resposta, mas que também
alimentam parsing heurístico no backend. Menor volume de cenários porque são campos
únicos de formulário, não textareas de múltiplas linhas.

---

## Prazo de entrega do fornecedor (`PrazoEntregaParser`)

Campo "Prazo de entrega" no cadastro de fornecedor (`FornecedorFormModal`). Usado
pelo Mapa de Compra no cenário Melhor Prazo — extrai heuristicamente o **primeiro**
número seguido de "dia(s)" do texto
(`backend/src/main/java/com/prx/cotacao/cotacao/parser/PrazoEntregaParser.java`,
regex `(\d+)\s*dias?`, case-insensitive).

**PZ-01 — Formato direto**
**Cole isto no campo Prazo de entrega:** `3 dias úteis`
**Resultado esperado:** `3` dias extraídos; usado como está no cenário Melhor Prazo.

**PZ-02 — Só o número, sem "dias"**
**Cole isto:** `3`
**Resultado esperado:** `null` (o regex exige a palavra "dia"/"dias" — só o número
sozinho não é reconhecido) — fornecedor tratado como "prazo desconhecido",
ordenado por último no cenário Melhor Prazo, nunca excluído da comparação.

**PZ-03 — Intervalo de dias (só o primeiro número é usado)**
**Cole isto:** `2 a 3 dias úteis`
**Resultado esperado:** `2` (o menor número do intervalo, simplesmente por ser o
primeiro `\d+` que a regex encontra) — não é uma média nem o maior valor.

**PZ-04 — Número antes de "dias" mas não imediatamente adjacente**
**Cole isto:** `Em torno de 5 a 7 dias, dependendo da região`
**Resultado esperado:** `5` (primeiro número da string, mesmo que "dependendo da
região" venha depois de "dias" — o regex não se importa com o que vem depois).

**PZ-05 — Texto sem nenhum número**
**Cole isto:** `entrega imediata`
**Resultado esperado:** `null` — tratado como "prazo desconhecido" (mesmo resultado
do PZ-02).

**PZ-06 — Campo vazio**
**Cole isto:** *(deixar o campo em branco)*
**Resultado esperado:** `null` (guarda explícita para `isBlank()`).

**PZ-07 — Número seguido de "dia" no singular**
**Cole isto:** `1 dia útil`
**Resultado esperado:** `1` (o `s` de "dias?" é opcional, cobre tanto singular
quanto plural).

**PZ-08 — Número com texto de unidade diferente de dias (semana/mês)**
**Cole isto:** `2 semanas`
**Resultado esperado:** `null` — o parser só reconhece "dia(s)", não converte
semanas/meses para dias; esse fornecedor cai na mesma categoria de "prazo
desconhecido" de um fornecedor que não preencheu nada, o que pode surpreender quem
cadastrou o dado esperando que "2 semanas" virasse "14 dias" — vale confirmar se é
uma limitação aceitável ou um achado de UX a documentar.

---

## Pedido mínimo (validação numérica ausente)

Campo "Pedido mínimo (R$)" no cadastro de fornecedor — `input type="number" min="0"`
no HTML, mas sem validação JS customizada nem `@Positive`/`@DecimalMin` no backend
(`FornecedorRequest`). Complementa ENT-12 do plano de QA geral com valores prontos
pra testar.

**PM-01 — Valor negativo digitado diretamente**
**Cole isto no campo Pedido mínimo:** `-50`
**Resultado esperado (achado a confirmar):** o `min="0"` do HTML5 não bloqueia de
forma confiável a digitação/colagem de um valor negativo em todos os navegadores —
testar se `-50` é aceito e persistido sem erro tanto no submit quanto no backend
direto (`POST /fornecedores` com `pedidoMinimoPadrao: -50`).

**PM-02 — Valor com muitas casas decimais**
**Cole isto:** `99.999999`
**Resultado esperado:** testar se o valor é truncado/arredondado silenciosamente na
gravação (coluna é `precision=12,scale=2` conforme o restante do sistema monetário)
ou se gera erro de validação.

**PM-03 — Zero explícito**
**Cole isto:** `0`
**Resultado esperado:** aceito normalmente; no Mapa de Compra, cenário Compra
Equilibrada, um fornecedor com pedido mínimo `0` nunca deveria aparecer como "abaixo
do mínimo" (qualquer total ≥ 0 já satisfaz).
