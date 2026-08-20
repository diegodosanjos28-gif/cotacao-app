import { describe, expect, it } from "vitest";
import { idsProdutosDosItens } from "@/lib/itensLista";
import { ItemListaResponse } from "@/lib/types";

function makeItem(overrides: Partial<ItemListaResponse> = {}): ItemListaResponse {
  return {
    id: "item-1",
    textoOriginal: "3un sazon legumes 60g",
    quantidade: 3,
    unidade: "un",
    produtoIdEncontrado: "prod-1",
    scoreMatch: 0.95,
    matched: true,
    temRespostaFornecedorConfirmada: false,
    ...overrides,
  };
}

describe("idsProdutosDosItens", () => {
  it("lista vazia devolve array vazio", () => {
    expect(idsProdutosDosItens([])).toEqual([]);
  });

  it("extrai o produtoIdEncontrado de cada item", () => {
    const itens = [
      makeItem({ id: "item-1", produtoIdEncontrado: "prod-1" }),
      makeItem({ id: "item-2", produtoIdEncontrado: "prod-2" }),
    ];

    expect(idsProdutosDosItens(itens)).toEqual(["prod-1", "prod-2"]);
  });

  it("descarta itens sem produto identificado (produtoIdEncontrado null)", () => {
    const itens = [
      makeItem({ id: "item-1", produtoIdEncontrado: "prod-1" }),
      makeItem({ id: "item-2", produtoIdEncontrado: null }),
    ];

    expect(idsProdutosDosItens(itens)).toEqual(["prod-1"]);
  });

  it("dedup: dois itens apontando pro mesmo produto geram um único id na lista", () => {
    const itens = [
      makeItem({ id: "item-1", produtoIdEncontrado: "prod-1" }),
      makeItem({ id: "item-2", produtoIdEncontrado: "prod-1" }),
    ];

    expect(idsProdutosDosItens(itens)).toEqual(["prod-1"]);
  });

  it("todos os itens sem produto identificado devolve array vazio", () => {
    const itens = [
      makeItem({ id: "item-1", produtoIdEncontrado: null }),
      makeItem({ id: "item-2", produtoIdEncontrado: null }),
    ];

    expect(idsProdutosDosItens(itens)).toEqual([]);
  });
});
