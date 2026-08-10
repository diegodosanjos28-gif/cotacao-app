import { describe, expect, it } from "vitest";
import { detalhamentoPorFornecedor } from "@/lib/comparativo";
import { ComparativoItemResponse, PrecoFornecedor } from "@/lib/types";

function makeOferta(overrides: Partial<PrecoFornecedor> = {}): PrecoFornecedor {
  return {
    fornecedorId: "forn-1",
    nomeFornecedor: "Distribuidora Alfa",
    precoInformado: 10,
    precoUnitarioCalculado: 10,
    semEstoque: false,
    status: "OK",
    divergenciaComparativa: false,
    ...overrides,
  };
}

function makeItem(overrides: Partial<ComparativoItemResponse> = {}): ComparativoItemResponse {
  return {
    cotacaoProdutoId: "item-1",
    produtoId: "prod-1",
    nomeProduto: "Arroz 5kg",
    quantidade: 2,
    unidade: "un",
    precosPorFornecedor: [],
    ...overrides,
  };
}

describe("detalhamentoPorFornecedor", () => {
  it("exclui item sem nenhuma oferta válida, sem crashar", () => {
    const itens = [makeItem({ precosPorFornecedor: [makeOferta({ status: "NAO_IDENTIFICADO" })] })];
    expect(detalhamentoPorFornecedor(itens)).toEqual([]);
  });

  it("agrupa por fornecedor com total correto e itens na ordem da cotação", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        nomeProduto: "Arroz 5kg",
        quantidade: 2,
        precosPorFornecedor: [makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Alfa", precoUnitarioCalculado: 10 })],
      }),
      makeItem({
        cotacaoProdutoId: "item-2",
        nomeProduto: "Feijão 1kg",
        quantidade: 3,
        precosPorFornecedor: [makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Alfa", precoUnitarioCalculado: 5 })],
      }),
    ];

    const detalhes = detalhamentoPorFornecedor(itens);

    expect(detalhes).toHaveLength(1);
    expect(detalhes[0].fornecedorId).toBe("forn-1");
    expect(detalhes[0].itens.map((i) => i.nomeProduto)).toEqual(["Arroz 5kg", "Feijão 1kg"]);
    expect(detalhes[0].itens[0].total).toBe(20); // 10 * 2
    expect(detalhes[0].itens[1].total).toBe(15); // 5 * 3
    expect(detalhes[0].totalFornecedor).toBe(35);
  });

  it("gera uma entrada por fornecedor quando múltiplos fornecedores cotam itens diferentes, preservando ordem de aparição", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        precosPorFornecedor: [
          makeOferta({ fornecedorId: "forn-2", nomeFornecedor: "Beta", precoUnitarioCalculado: 8 }),
          makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Alfa", precoUnitarioCalculado: 10 }),
        ],
      }),
    ];

    const detalhes = detalhamentoPorFornecedor(itens);

    expect(detalhes.map((d) => d.fornecedorId)).toEqual(["forn-2", "forn-1"]);
  });

  it("exclui fornecedor que aparece na cotação mas nunca tem oferta válida", () => {
    const itens = [
      makeItem({
        precosPorFornecedor: [
          makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Alfa", precoUnitarioCalculado: 10 }),
          makeOferta({ fornecedorId: "forn-2", nomeFornecedor: "Beta", semEstoque: true }),
        ],
      }),
    ];

    const detalhes = detalhamentoPorFornecedor(itens);

    expect(detalhes).toHaveLength(1);
    expect(detalhes[0].fornecedorId).toBe("forn-1");
  });
});
