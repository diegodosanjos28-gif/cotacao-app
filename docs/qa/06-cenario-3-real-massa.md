# Cenário 3 real — lista de 68 linhas + 6 fornecedores

Massa de teste do **teste de aceitação real** de 2026-07-28: a lista base de compra de
verdade do cliente (68 linhas) somada às respostas reais de 6 fornecedores
(CERNUTTI, RWR, DEQUECH e outros 3). Diferente dos cenários sintéticos de
[`05-cenarios-sequenciais-completos.md`](05-cenarios-sequenciais-completos.md), esta é
massa de campo — foi ela que revelou os 5 problemas corrigidos em 2026-07-28.

> **Nota de proveniência (2026-07-29):** a seção ["Cenários adicionais — pacote de
> ajustes pós-call"](#cenários-adicionais--pacote-de-ajustes-pós-call-2026-07-29) no
> fim deste documento **não** vem do relato do teste de 2026-07-28 — o pacote de
> ajustes é posterior a ele. São linhas construídas no mesmo estilo/voz da massa real
> (informal, sem `R$`, erros de digitação de propósito) especificamente para exercitar
> os comportamentos novos, reaproveitando fornecedores/produtos já usados neste
> documento onde fez sentido. Marcadas explicitamente para não se confundir com a
> massa capturada do cliente — a regra de "nada inventado" do resto do arquivo vale
> igual, só que aqui o objetivo é cobertura de regressão, não reprodução literal de um
> teste específico.

O valor dela está justamente no que ela tem e os cenários sintéticos não tinham:
**5 dos 6 fornecedores não escrevem `R$`**. Todo cenário sintético anterior usava `R$`,
então a suíte inteira passava verde enquanto o parser devolvia preço `null` em quase
toda linha real.

Nota importante: o "não escrevem `R$`" aqui é sobre `ParserRespostaFornecedorService`
extrair o **preço** de cada linha (seções 6.2/6.6 da doc técnica) — continua valendo
sem alteração, os blocos abaixo seguem exercitando exatamente isso. É um gotcha
diferente do que existia no **classificador** de mensagem WhatsApp (que até 08/2026
também decidia o tipo da mensagem checando presença de `R$`, e foi substituído por um
marcador explícito de 1ª linha — ver seção 10.3 da doc técnica e
`docs/qa/scripts/04-seed-gotcha-fornecedor-sem-rs.sh`). Se algum bloco abaixo for usado
via `/dev/whatsapp/simular` (canal WhatsApp real, que passa pelo classificador) em vez
de colado direto no campo da Entrada de Dados, precisa do prefixo `RESPOSTA_FORNECEDOR\n`
manual — sem isso a mensagem é rejeitada como "formato não reconhecido" antes mesmo de
chegar no parser.

## ⚠️ Estado deste documento

As linhas abaixo são as **confirmadas** — cada uma foi extraída do relato do teste real e
está travada por teste unitário (ver "Rastreabilidade"). **A massa completa (as 68 linhas
da lista base e os 6 blocos de resposta na íntegra) ainda não está neste arquivo** — ela
não foi capturada junto com o relato do teste. Para completar: cole cada bloco literal
nas seções marcadas `<!-- COLAR -->` abaixo, sem reformatar (os erros de digitação, os
pontos duplos e a falta de `R$` **são** o objeto do teste — normalizar a massa destrói o
valor dela).

Nada aqui foi reconstruído por inferência: uma linha que não estava no relato não foi
inventada para preencher espaço.

---

## Lista base (campo "Lista de produtos")

Linhas confirmadas:

```
24 unid leite de coco menina 200 ml
```

**Resultado esperado:** nenhum aviso de formato. `unid` é alias de `un` — antes do fix
esta linha era falso positivo de "Falta a unidade após a quantidade (un, fd, cx...)".

Os avisos citam a linha como `Lnn` usando o **número real da linha** no textarea (linha em
branco consome um número), sincronizado com o gutter de numeração ao lado do campo.

<!-- COLAR: as 68 linhas da lista base, na íntegra e sem reformatar -->

---

## CERNUTTI — preço sem `R$`, separador decimal vírgula

```
3 cx vinagre heinig 1,89
1 fardo sal realta
1 fardo polenta sinhá
```

**Resultado esperado:**

| Linha | Preço | Descrição extraída | Observação |
|---|---|---|---|
| `3 cx vinagre heinig 1,89` | `1,89` | `vinagre heinig` | `qtdInformada=3`, `unInformada=cx`; o prefixo de qtd+unidade sai da descrição |
| `1 fardo sal realta` | `null` | — | não há número com separador decimal; **não** pode virar `1,00` |
| `1 fardo polenta sinhá` | `null` | — | idem |

**Atualizado em 2026-07-29 (pacote de ajustes pós-call, B.2 — reverte o comportamento
documentado antes desta data):** se `1 fardo sal realta`/`1 fardo polenta sinhá`
casarem com um item da lista base (68 linhas, massa ainda incompleta — confirmar
quando colada), essas duas linhas **não aparecem mais** na Conferência do Fornecedor
nem bloqueiam "Confirmar e Processar". Fornecedor sem preço para o item é tratado como
"não informou" — mesmo efeito de não ter mencionado o item. Ao confirmar, nenhuma
linha é gravada em `cotacao_produto_fornecedor` para elas; no Comparativo, aparecem
como "—" para a CERNUTTI. **Antes** desta data (comportamento revertido): a opção era
clicável na Conferência e abria o formulário manual pedindo só o preço, bloqueando a
confirmação até o operador completar.

<!-- COLAR: resposta da CERNUTTI na íntegra -->

---

## RWR — preço sem `R$`, separador decimal ponto

```
1 fardo polentina. 3.79
1 cx margarina 500 g delícia..6,49
```

**Resultado esperado:**

| Linha | Preço | Descrição extraída |
|---|---|---|
| `1 fardo polentina. 3.79` | `3,79` | `1 fardo polentina` (`fardo` não está na lista de unidades do strip — `f`/`fd` estão, `fardo` não; comportamento do protótipo, preservado) |
| `1 cx margarina 500 g delícia..6,49` | `6,49` | `margarina 500 g delícia` |

O `.` antes do preço e o `..` duplo são separadores válidos — a classe de separador do
protótipo é `[\s\-–—:.=]+`.

<!-- COLAR: resposta da RWR na íntegra -->

---

## DEQUECH — preço com `R$` (formato que já funcionava)

```
Feijão 1,600kg - R$11,29
```

**Resultado esperado:** preço `11,29`, **não** `1,60`. Esta é a linha que justifica o
único desvio deliberado em relação ao protótipo: o regex do protótipo casa `1,60` dentro
de `1,600` (bug real do original), então o porte acrescenta um lookahead `(?!\d)` ao
padrão numérico. Regressão obrigatória em qualquer mexida no parser de preço.

<!-- COLAR: resposta da DEQUECH na íntegra -->

---

## Itens extras (fora da lista base)

```
Maionese salada 500g 4,99
Maionese 180g fugini 2,99
```

**Resultado esperado:** as duas aparecem na Conferência como **item novo**
(`EXTRA_ITEM`), com as ações Adicionar à lista / Associar a outro item / Editar
manualmente / Recusar funcionando.

Dependência importante: `ClassificacaoConferenciaService` só cria item extra quando
`it.preco() != null`. Antes do fix do parser, essas duas linhas (sem `R$`) tinham preço
`null` e **desapareciam silenciosamente** da Conferência — não eram um bug de
classificação, eram o mesmo bug de parsing.

<!-- COLAR: os 3 blocos de resposta restantes na íntegra -->

---

## Cenários adicionais — pacote de ajustes pós-call (2026-07-29)

Ver [nota de proveniência](#cenário-3-real--lista-de-68-linhas--6-fornecedores) no
topo — estas linhas não vêm do relato do teste de 2026-07-28, foram construídas no
mesmo estilo pra exercitar os comportamentos novos.

### A.3 — Dicionário de unidades ampliado (lista base)

```
3 fardo arroz tio joão 5kg
2 galao agua mineral crystal
1 display bala fini
5 saco cimento votoran
```

**Resultado esperado:** nenhuma das 4 linhas gera aviso de formato incorreto —
`fardo`, `galao`, `display` e `saco` entraram no dicionário `UNIT_ALIASES` nesta leva
(antes, só `fd`/`f` eram reconhecidos como fardo; `galao`, `display` e `saco` não
existiam). A linha inteira não vira "não identificado" por causa da unidade.

### B.1 — Múltiplas opções de marca: Excluir / Adicionar como novo item

Lista base:
```
2 cx sazon tempero completo
```

Resposta de fornecedor (reaproveita os nomes de produto já usados nos testes
automatizados de `MULTIPLE_OPTIONS`, ver Rastreabilidade):
```
2 cx sazon legumes 60g 2,89
2 cx sazon ervas finas 60g 2,99
```

**Resultado esperado:** as duas linhas casam com o mesmo item base → `REVISAR`,
motivo "Fornecedor enviou múltiplas opções", um radio por candidato. Selecionar
"sazon legumes" como vencedor (`SELECIONAR_CANDIDATO`) deixa "sazon ervas finas" não
selecionado — nesse candidato aparecem os botões **Excluir** (feedback visual; já era
descartado por padrão) e **Adicionar como novo item na lista base**
(`ADICIONAR_CANDIDATO_A_LISTA`, texto/preço pré-preenchidos e editáveis). Confirmando
com o spin-off preenchido: cria um `CotacaoProduto` novo ("sazon ervas finas 60g") sem
tocar no item original; se o item original ficar sem `SELECIONAR_CANDIDATO` próprio
na mesma submissão, a confirmação inteira é bloqueada mesmo com o spin-off pronto.

### B.2 — Item sem preço (ver também a seção CERNUTTI acima, atualizada)

A seção CERNUTTI deste documento já é o exemplo real: `1 fardo sal realta` e
`1 fardo polenta sinhá` são linhas de campo genuínas, sem preço, que hoje (pós
pacote de ajustes) simplesmente não aparecem na Conferência — não precisou construir
um cenário novo, só atualizar o resultado esperado da massa já capturada.

### B.3 — Preço suspeito de fardo/caixa entre fornecedores (gatilho de 1,5x OU R$10,00 absolutos)

Reaproveita a linha real da DEQUECH (seção acima) como referência confirmada:

```
Feijão 1,600kg - R$11,29
```

Segundo fornecedor (construído), mesmo item, preço bem acima:

```
Feijao 1,6kg 18,50
```

**Resultado esperado:** com a DEQUECH já confirmada a R$11,29, o segundo fornecedor
(R$18,50 ≈ 1,64x a referência, acima do limiar de 1,5x) entra `REVISAR`/
`PACKAGE_PRICE_SUSPECTED` **dentro da própria Conferência** deste segundo fornecedor
— não só como badge no Comparativo depois de tudo confirmado. Campo "Unid./
embalagem" aparece para resolver (mesmo campo/destino de sempre — grava em
`embalagem_qtd_confirmada`, snapshot imutável). Se o operador reconfirmar esse
fornecedor depois tentando mudar a quantidade informada, o valor já gravado
**sempre vence** (achado corrigido nesta leva, B.0) — a rodada nova é descartada
silenciosamente (log de auditoria no backend), não sobrescreve.

## Rastreabilidade — o que cada linha trava

Todas as linhas confirmadas acima estão cobertas por teste automatizado, não só por este
documento:

- `ParserRespostaFornecedorServiceTest`: `extrai_preco_sem_rifo_com_qtd_e_unidade_no_inicio`,
  `extrai_preco_sem_rifo_com_ponto_final_antes_do_preco`,
  `extrai_preco_sem_rifo_com_pontos_duplos_como_separador`,
  `nao_reconhece_preco_quando_nao_ha_separador_decimal`,
  `desvio_intencional_do_prototipo_nao_confunde_peso_com_preco`.
- `frontend/src/lib/__tests__/validarLista.test.ts`: alias `unid` e numeração real de
  linha.
- `frontend/src/app/.../__tests__/ConferenciaModal.test.tsx`: opção sem preço
  selecionável, formulário manual abrindo preenchido.

Cobertura adicionada no pacote de ajustes pós-call (2026-07-29), para os cenários da
seção acima:
- `ParserListaProdutosServiceTest` — novos aliases de unidade (`fardo`, `galao`,
  `display`, `saco`, etc.).
- `ClassificacaoConferenciaServiceTest` —
  `item_identificado_sem_preco_gera_ok_sem_motivos` (B.2),
  `item_sem_estoque_sem_preco_continua_ok_sem_motivos`,
  `preco_com_valor_maior_ou_igual_a_1_5x_a_referencia_gera_package_price_suspected_revisar` (B.3),
  `preco_abaixo_de_1_5x_a_referencia_nao_gera_package_price_suspected`.
- `ConfirmacaoRespostaServiceTest` —
  `adicionar_candidato_a_lista_cria_novo_item_base_a_partir_de_opcao_nao_selecionada` (B.1),
  `adicionar_candidato_a_lista_sem_resolver_item_original_bloqueia_confirmacao_inteira`,
  `confirmar_item_identificado_sem_preco_nao_persiste_linha_nem_lanca_erro` (B.2),
  `confirmar_item_que_perdeu_preco_em_reconfirmacao_remove_linha_existente`,
  `reconfirmar_com_embalagem_qtd_diferente_preserva_snapshot_antigo_em_vez_de_sobrescrever` (B.0).
- `FornecedorRespostaServiceTest` —
  `preview_com_preco_muito_acima_de_fornecedor_ja_confirmado_gera_package_price_suspected` (B.3),
  `preview_com_preco_normal_perto_de_fornecedor_ja_confirmado_nao_gera_package_price_suspected`.
- `ComparativoServiceTest` — `diferenca_absoluta_grande_mas_abaixo_de_1_5x_nao_diverge`,
  `diferenca_absoluta_pequena_mas_igual_ou_acima_de_1_5x_diverge` (badge unificado com
  o mesmo critério da Conferência via `PrecoReferenciaService`).
- `PrecoReferenciaServiceTest` — novo arquivo, mediana/referência/limiar isolados.

Vale a regra do [`README.md`](README.md) desta pasta: se o comportamento observado
divergir do documentado aqui, o código-fonte é a fonte de verdade — atualize o cenário.
