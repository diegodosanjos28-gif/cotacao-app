// Cobre o hint visual "ao digitar" de validarLista.ts — porte de validarListaExec() do
// protótipo COTA&TESTA V5. Este módulo NUNCA é o parser autoritativo (esse é
// ParserListaProdutosService.java no backend); os testes aqui só travam o comportamento
// visual (quais avisos aparecem, numeração) contra os exemplos do guia de formatação.

import { describe, expect, it } from "vitest";
import { comecaComQuantidadeEUnidade, linhasNaoVazias, validarLinhasLista } from "@/lib/validarLista";

describe("linhasNaoVazias", () => {
  it("separa por linha, faz trim e remove linhas vazias", () => {
    const texto = "  15un sazon legumes 60g  \n\n  2cx leite integral 1l  \n   \n";
    expect(linhasNaoVazias(texto)).toEqual(["15un sazon legumes 60g", "2cx leite integral 1l"]);
  });

  it("texto vazio retorna array vazio", () => {
    expect(linhasNaoVazias("")).toEqual([]);
  });

  it("texto só com espaços/quebras de linha retorna array vazio", () => {
    expect(linhasNaoVazias("   \n\t\n   \n")).toEqual([]);
  });
});

describe("validarLinhasLista — linhas válidas (sem aviso)", () => {
  it.each([
    ["15un sazon legumes 60g"],
    ["2cx leite integral 1l"],
    ["6 unidades arroz"],
    ["1 fardo bombom"],
    ["2 caixas leite"],
    // "unid" é alias de "un" — linha real do Cenário 3 que virava falso positivo de
    // "falta a unidade após a quantidade".
    ["24 unid leite de coco menina 200 ml"],
    ["10 unid arroz"],
    // Ampliação do dicionário (tabela de unidade comercial NF-e/SEFAZ) — sem os
    // aliases novos aqui, essas linhas caíam erroneamente em "falta a unidade".
    ["2gl agua mineral"],
    ["5cento ovos"],
    ["10gr acucar"],
    ["3 dzs ovos"],
    ["1bandej ovos"],
    ["3disp chiclete"],
  ])("%s não gera aviso", (linha) => {
    expect(validarLinhasLista(linha)).toEqual([]);
  });
});

describe("validarLinhasLista — os 5 casos de aviso", () => {
  it("1. linha muito curta (<=3 caracteres)", () => {
    expect(validarLinhasLista("Obs")).toEqual([
      { numero: 1, mensagem: "Linha muito curta — pode ser cabeçalho" },
    ]);
  });

  it("2. quantidade + unidade reconhecida, mas com possíveis múltiplos itens na mesma linha", () => {
    expect(validarLinhasLista("10un arroz, 5cx feijao")).toEqual([
      { numero: 1, mensagem: "Possível múltiplos itens na mesma linha" },
    ]);
  });

  it("3. quantidade sem unidade reconhecida", () => {
    expect(validarLinhasLista("10 arroz")).toEqual([
      { numero: 1, mensagem: "Falta a unidade após a quantidade (un, fd, cx...)" },
    ]);
  });

  it("4. parece cabeçalho (só letras/espaços, 2 a 25 chars, sem dígito)", () => {
    expect(validarLinhasLista("Produtos de limpeza")).toEqual([
      { numero: 1, mensagem: "Parece cabeçalho — remova ou adicione qtd+unidade" },
    ]);
  });

  it("5. não começa com quantidade + unidade (fallback)", () => {
    expect(validarLinhasLista("arroz tipo 1")).toEqual([
      { numero: 1, mensagem: "Não começa com quantidade + unidade" },
    ]);
  });
});

describe("validarLinhasLista — numeração", () => {
  it("usa o número REAL da linha, contando as linhas em branco do meio do texto", () => {
    // Numerar só as linhas não-vazias (comportamento anterior) daria 2 e 3 — e o "Lnn"
    // do aviso apontaria para a linha errada no gutter de ListaProdutosSection, que é
    // onde o usuário vai procurar.
    const texto = ["15un sazon legumes 60g", "", "Obs", "", "", "10 arroz"].join("\n");

    expect(validarLinhasLista(texto)).toEqual([
      { numero: 3, mensagem: "Linha muito curta — pode ser cabeçalho" },
      { numero: 6, mensagem: "Falta a unidade após a quantidade (un, fd, cx...)" },
    ]);
  });

  it("texto vazio ou só espaços retorna array vazio de avisos", () => {
    expect(validarLinhasLista("")).toEqual([]);
    expect(validarLinhasLista("   \n  \n\t")).toEqual([]);
  });
});

// Usado pela Conferência do Fornecedor (item extra / spin-off de MULTIPLE_OPTIONS) pra
// bloquear a criação de um item novo na lista base sem quantidade + unidade.
describe("comecaComQuantidadeEUnidade", () => {
  it("aceita texto começando com quantidade + unidade reconhecida", () => {
    expect(comecaComQuantidadeEUnidade("5un sazon legumes 60g")).toBe(true);
    expect(comecaComQuantidadeEUnidade("2cx leite integral 1l")).toBe(true);
    expect(comecaComQuantidadeEUnidade("1fardo arroz agulhinha")).toBe(true);
    expect(comecaComQuantidadeEUnidade("  10kg açúcar refinado  ")).toBe(true);
  });

  it("rejeita texto sem quantidade+unidade no início (texto bruto do fornecedor)", () => {
    expect(comecaComQuantidadeEUnidade("Sazon Legumes 60g Light - R$ 5,49")).toBe(false);
    expect(comecaComQuantidadeEUnidade("Maionese salada 500g 4,99")).toBe(false);
    expect(comecaComQuantidadeEUnidade("")).toBe(false);
  });

  it("rejeita quantidade sem unidade reconhecida", () => {
    expect(comecaComQuantidadeEUnidade("3 caixotes de leite")).toBe(false);
  });
});
