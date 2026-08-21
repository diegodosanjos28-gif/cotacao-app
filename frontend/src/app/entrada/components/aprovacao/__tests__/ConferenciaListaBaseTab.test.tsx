// ConferenciaListaBaseTab — aba 1 do AprovacaoModal (Fase C, 2026-08-20). Tabela leve
// de texto livre por linha, sem autocomplete/paginação/filtro (ao contrário do grid
// completo). Edição de produto é sempre via nomeProdutoLivre — produtoId é zerado
// explicitamente no commit (decisão de design registrada no componente).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import ConferenciaListaBaseTab from "../ConferenciaListaBaseTab";
import { ItemListaResponse, Produto } from "@/lib/types";

const { editarItemCotacaoMock, buscarListaMock } = vi.hoisted(() => ({
  editarItemCotacaoMock: vi.fn(),
  buscarListaMock: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, editarItemCotacao: editarItemCotacaoMock, buscarLista: buscarListaMock };
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

function makeProduto(overrides: Partial<Produto> = {}): Produto {
  return {
    id: "prod-1",
    nome: "Açúcar União 1kg",
    marca: null,
    pesoVolumeValor: null,
    pesoVolumeUnidade: null,
    unidadePadrao: null,
    embalagemQtdSugerida: null,
    criadoEm: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

beforeEach(() => {
  editarItemCotacaoMock.mockReset();
  buscarListaMock.mockReset();
});

describe("ConferenciaListaBaseTab — renderização", () => {
  it("mostra qtd/unidade/produto por linha, resolvendo o nome pelo catálogo quando há produtoIdEncontrado", () => {
    const item = makeItem({ produtoIdEncontrado: "prod-1" });
    render(<ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[makeProduto()]} onListaAtualizada={vi.fn()} cotacaoFinalizada={false} />);

    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("un")).toBeTruthy();
    expect(screen.getByText("Açúcar União 1kg")).toBeTruthy();
  });

  it("sem produtoIdEncontrado, mostra o textoOriginal", () => {
    const item = makeItem({ produtoIdEncontrado: null, textoOriginal: "3kg Arroz tipo 1" });
    render(<ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[]} onListaAtualizada={vi.fn()} cotacaoFinalizada={false} />);

    expect(screen.getByText("3kg Arroz tipo 1")).toBeTruthy();
  });
});

describe("ConferenciaListaBaseTab — edição inline", () => {
  it("clicar no lápis abre os 3 campos de texto livre pré-preenchidos", () => {
    const item = makeItem({ quantidade: 5, unidade: "kg", textoOriginal: "5kg Feijão" });
    render(<ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[]} onListaAtualizada={vi.fn()} cotacaoFinalizada={false} />);

    fireEvent.click(screen.getByTitle("Corrigir"));

    const inputs = screen.getAllByRole("textbox") as HTMLInputElement[];
    expect(inputs.map((i) => i.value)).toEqual(["5", "kg", "5kg Feijão"]);
  });

  it("salvar corrige o texto e commita com produtoId null + nomeExibido (nunca os dois campos)", async () => {
    editarItemCotacaoMock.mockResolvedValue(undefined);
    buscarListaMock.mockResolvedValue([]);
    // Item já casado com um produto do catálogo — a correção deve mesmo assim ir por
    // nomeProdutoLivre, forçando produtoId=null (decisão de design da aba).
    const item = makeItem({ produtoIdEncontrado: "prod-1", quantidade: 2, unidade: "un" });
    const onListaAtualizada = vi.fn();
    render(
      <ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[makeProduto()]} onListaAtualizada={onListaAtualizada} cotacaoFinalizada={false} />,
    );

    fireEvent.click(screen.getByTitle("Corrigir"));
    const [, , produtoInput] = screen.getAllByRole("textbox") as HTMLInputElement[];
    fireEvent.change(produtoInput, { target: { value: "Açúcar Cristal 1kg (corrigido)" } });
    fireEvent.click(screen.getByTitle("Salvar"));

    await waitFor(() =>
      expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
        quantidade: 2,
        unidade: "un",
        produtoId: undefined,
        nomeProdutoLivre: "Açúcar Cristal 1kg (corrigido)",
      }),
    );
    await waitFor(() => expect(onListaAtualizada).toHaveBeenCalled());
    // Volta pro modo leitura depois de salvar.
    expect(screen.queryByTitle("Salvar")).toBeNull();
  });

  it("erro ao salvar mostra a mensagem na própria linha", async () => {
    editarItemCotacaoMock.mockRejectedValue(new Error("falhou"));
    const item = makeItem();
    render(<ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[]} onListaAtualizada={vi.fn()} cotacaoFinalizada={false} />);

    fireEvent.click(screen.getByTitle("Corrigir"));
    fireEvent.click(screen.getByTitle("Salvar"));

    await waitFor(() => expect(screen.getByText("Não foi possível salvar a alteração.")).toBeTruthy());
  });

  it("item com resposta de fornecedor confirmada não pode ser editado (lápis desabilitado)", () => {
    const item = makeItem({ temRespostaFornecedorConfirmada: true });
    render(<ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[]} onListaAtualizada={vi.fn()} cotacaoFinalizada={false} />);

    expect((screen.getByTitle("Corrigir") as HTMLButtonElement).disabled).toBe(true);
  });

  it("cotação finalizada desabilita a edição de todos os itens", () => {
    const item = makeItem();
    render(<ConferenciaListaBaseTab cotacaoId="cot-1" itens={[item]} produtos={[]} onListaAtualizada={vi.fn()} cotacaoFinalizada={true} />);

    expect((screen.getByTitle("Corrigir") as HTMLButtonElement).disabled).toBe(true);
  });
});
