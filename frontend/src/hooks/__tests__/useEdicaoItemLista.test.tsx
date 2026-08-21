// useEdicaoItemLista — plumbing de edição-e-commit extraída de GridProdutosSection
// (Fase C do refactor da Entrada de Dados, 2026-08-20). Cobertura de comportamento
// puro do hook (draft readers, commitRow, overrides síncronos, itemBloqueado,
// tratamento de erro) — a cobertura de UI/grid completa continua em
// GridProdutosSection.test.tsx.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useEdicaoItemLista } from "../useEdicaoItemLista";
import { ItemListaResponse } from "@/lib/types";

const { editarItemCotacaoMock } = vi.hoisted(() => ({ editarItemCotacaoMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, editarItemCotacao: editarItemCotacaoMock };
});

function makeItem(overrides: Partial<ItemListaResponse> = {}): ItemListaResponse {
  return {
    id: "item-1",
    textoOriginal: "2un Açúcar 1kg",
    quantidade: 2,
    unidade: "un",
    produtoIdEncontrado: null,
    scoreMatch: 0,
    matched: false,
    temRespostaFornecedorConfirmada: false,
    ...overrides,
  };
}

beforeEach(() => {
  editarItemCotacaoMock.mockReset();
});

describe("useEdicaoItemLista — draft readers sem rascunho", () => {
  it("caem no valor original do item", () => {
    const item = makeItem({ quantidade: 5, unidade: "kg", produtoIdEncontrado: "prod-1" });
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo: vi.fn() }),
    );

    expect(result.current.draftQuantidade(item)).toBe("5");
    expect(result.current.draftUnidade(item)).toBe("kg");
    expect(result.current.draftProdutoId(item)).toBe("prod-1");
    expect(result.current.draftNomeExibido(item)).toBeNull();
  });
});

describe("useEdicaoItemLista — onCellEdit", () => {
  it("draftProdutoId(null) explícito é distinto de 'sem rascunho' (produto limpo de propósito)", () => {
    const item = makeItem({ produtoIdEncontrado: "prod-1" });
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo: vi.fn() }),
    );

    act(() => result.current.onCellEdit(item.id, "produtoId", null));

    expect(result.current.draftProdutoId(item)).toBeNull();
  });
});

describe("useEdicaoItemLista — commitRow", () => {
  it("chama editarItemCotacao com os valores do rascunho e onSalvo em caso de sucesso", async () => {
    editarItemCotacaoMock.mockResolvedValue(undefined);
    const onSalvo = vi.fn().mockResolvedValue(undefined);
    const item = makeItem();
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo }),
    );

    act(() => result.current.onCellEdit(item.id, "quantidade", "7"));
    await act(async () => {
      await result.current.commitRow(item);
    });

    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
      quantidade: 7,
      unidade: "un",
      produtoId: undefined,
      nomeProdutoLivre: undefined,
    });
    expect(onSalvo).toHaveBeenCalledTimes(1);
  });

  it("overrides síncronos têm prioridade sobre o rascunho já registrado (evita a Qtd stale)", async () => {
    editarItemCotacaoMock.mockResolvedValue(undefined);
    const item = makeItem({ quantidade: 2, unidade: "un" });
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo: vi.fn().mockResolvedValue(undefined) }),
    );

    // Digitou uma nova Qtd (ainda sem blur, só no rascunho) e trocou a Unidade no
    // mesmo instante, passando o override — commitRow deve usar a Qtd recém-digitada
    // (via rascunho) junto com a Unidade do override, nunca a Unidade original.
    act(() => result.current.onCellEdit(item.id, "quantidade", "9"));
    await act(async () => {
      await result.current.commitRow(item, { unidade: "kg" });
    });

    expect(editarItemCotacaoMock).toHaveBeenCalledWith(
      "cot-1",
      "item-1",
      expect.objectContaining({ quantidade: 9, unidade: "kg" }),
    );
  });

  it("produtoId presente tem prioridade sobre nomeProdutoLivre (nunca envia os dois)", async () => {
    editarItemCotacaoMock.mockResolvedValue(undefined);
    const item = makeItem();
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo: vi.fn().mockResolvedValue(undefined) }),
    );

    await act(async () => {
      await result.current.commitRow(item, { produtoId: "prod-9", nomeExibido: "Nome livre" });
    });

    expect(editarItemCotacaoMock).toHaveBeenCalledWith(
      "cot-1",
      "item-1",
      expect.objectContaining({ produtoId: "prod-9", nomeProdutoLivre: undefined }),
    );
  });

  it("item bloqueado nunca chama a API", async () => {
    const item = makeItem();
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => true, onSalvo: vi.fn() }),
    );

    await act(async () => {
      await result.current.commitRow(item);
    });

    expect(editarItemCotacaoMock).not.toHaveBeenCalled();
  });

  it("quantidade inválida (<=0 ou não numérica) é um no-op silencioso, sem chamar a API", async () => {
    const item = makeItem();
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo: vi.fn() }),
    );

    act(() => result.current.onCellEdit(item.id, "quantidade", "0"));
    await act(async () => {
      await result.current.commitRow(item);
    });

    expect(editarItemCotacaoMock).not.toHaveBeenCalled();
  });

  it("erro na API popula `erros[item.id]` e reverte o rascunho (não persiste um valor não salvo)", async () => {
    editarItemCotacaoMock.mockRejectedValue(new Error("falhou"));
    const item = makeItem();
    const { result } = renderHook(() =>
      useEdicaoItemLista({ cotacaoId: "cot-1", itemBloqueado: () => false, onSalvo: vi.fn() }),
    );

    act(() => result.current.onCellEdit(item.id, "quantidade", "9"));
    await act(async () => {
      await result.current.commitRow(item);
    });

    await waitFor(() => expect(result.current.erros["item-1"]).toBeTruthy());
    // Rascunho revertido: draftQuantidade volta a refletir o item original, não o "9".
    expect(result.current.draftQuantidade(item)).toBe("2");
  });
});
