// Autocomplete de produto do grid unificado (Prompt 12 — generaliza o que antes era
// exclusivo da tela "Ajuste de Lista" do WhatsApp, removida). Sem onUsarNomeLivre (uso
// em LinhaGridProdutos, editar item já persistido), não oferece "+ usar como novo
// produto" — essa opção só existe quando o chamador passa onUsarNomeLivre (uso em
// NovaLinhaGridProdutos, adicionar item manual, que aceita nome nunca visto).

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import ProdutoAutocomplete from "@/app/cotacoes/[id]/entrada/components/ProdutoAutocomplete";
import { Produto } from "@/lib/types";

function makeProduto(overrides: Partial<Produto> = {}): Produto {
  return {
    id: "prod-1",
    nome: "Arroz Branco 5kg",
    marca: null,
    pesoVolumeValor: null,
    pesoVolumeUnidade: null,
    unidadePadrao: "un",
    embalagemQtdSugerida: null,
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

const PRODUTOS = [
  makeProduto({ id: "prod-1", nome: "Arroz Branco 5kg" }),
  makeProduto({ id: "prod-2", nome: "Feijão Carioca 1kg" }),
  makeProduto({ id: "prod-3", nome: "Sazon Legumes 60g" }),
];

function renderAutocomplete(overrides: Partial<React.ComponentProps<typeof ProdutoAutocomplete>> = {}) {
  const onSelecionar = vi.fn();
  render(
    <ProdutoAutocomplete
      produtos={PRODUTOS}
      valorAtualNome={null}
      onSelecionar={onSelecionar}
      {...overrides}
    />,
  );
  return { onSelecionar };
}

function input() {
  return screen.getByPlaceholderText("Buscar produto...") as HTMLInputElement;
}

describe("ProdutoAutocomplete", () => {
  it("mostra o nome do produto já selecionado (valorAtualNome) quando fechado, sem texto digitado", () => {
    renderAutocomplete({ valorAtualNome: "Arroz Branco 5kg" });
    expect(input().value).toBe("Arroz Branco 5kg");
  });

  it("ao focar, abre a lista com todos os produtos (texto some, campo fica pronto pra busca)", () => {
    renderAutocomplete({ valorAtualNome: "Arroz Branco 5kg" });
    fireEvent.focus(input());

    expect(input().value).toBe("");
    expect(screen.getByText("Feijão Carioca 1kg")).toBeTruthy();
    expect(screen.getByText("Sazon Legumes 60g")).toBeTruthy();
  });

  it("filtra por texto digitado (case-insensitive, sem acento) via normTxt", () => {
    renderAutocomplete();
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "feijao" } });

    expect(screen.getByText("Feijão Carioca 1kg")).toBeTruthy();
    expect(screen.queryByText("Arroz Branco 5kg")).toBeNull();
    expect(screen.queryByText("Sazon Legumes 60g")).toBeNull();
  });

  it("sem onUsarNomeLivre: filtro sem correspondência mostra 'Nenhum produto encontrado' (e não oferece novo produto)", () => {
    renderAutocomplete();
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "xicrocola inexistente" } });

    expect(screen.getByText("Nenhum produto encontrado")).toBeTruthy();
    expect(screen.queryByText(/como novo produto/)).toBeNull();
  });

  it("clicar numa sugestão chama onSelecionar com o produto certo, limpa o texto e fecha a lista", () => {
    const { onSelecionar } = renderAutocomplete();
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "sazon" } });

    fireEvent.click(screen.getByText("Sazon Legumes 60g"));

    expect(onSelecionar).toHaveBeenCalledWith(PRODUTOS[2]);
    expect(screen.queryByText("Nenhum produto encontrado")).toBeNull();
    expect(screen.queryByText("Arroz Branco 5kg")).toBeNull(); // lista fechada
  });

  it("fecha a lista de sugestões ao clicar fora do componente", () => {
    render(
      <div>
        <ProdutoAutocomplete produtos={PRODUTOS} valorAtualNome={null} onSelecionar={vi.fn()} />
        <button type="button">Fora</button>
      </div>,
    );
    fireEvent.focus(input());
    expect(screen.getByText("Feijão Carioca 1kg")).toBeTruthy();

    fireEvent.mouseDown(screen.getByRole("button", { name: "Fora" }));

    expect(screen.queryByText("Feijão Carioca 1kg")).toBeNull();
  });

  it("desabilitado: input fica disabled e não abre lista de sugestões ao focar", () => {
    renderAutocomplete({ disabled: true });
    expect(input().disabled).toBe(true);

    fireEvent.focus(input());
    expect(screen.queryByText("Feijão Carioca 1kg")).toBeNull();
  });
});

describe("ProdutoAutocomplete — onUsarNomeLivre (Prompt 12, uso em NovaLinhaGridProdutos)", () => {
  it("com onUsarNomeLivre, digitar um nome sem match exato oferece '+ usar como novo produto'", () => {
    renderAutocomplete({ onUsarNomeLivre: vi.fn() });
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "produto nunca visto" } });

    expect(screen.getByText('+ usar "produto nunca visto" como novo produto')).toBeTruthy();
  });

  it("clicar em '+ usar como novo produto' chama onUsarNomeLivre com o texto digitado", () => {
    const onUsarNomeLivre = vi.fn();
    renderAutocomplete({ onUsarNomeLivre });
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "produto nunca visto" } });
    fireEvent.click(screen.getByText('+ usar "produto nunca visto" como novo produto'));

    expect(onUsarNomeLivre).toHaveBeenCalledWith("produto nunca visto");
  });

  it("com match exato (mesmo nome de um produto existente), não oferece criar novo", () => {
    renderAutocomplete({ onUsarNomeLivre: vi.fn() });
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "Arroz Branco 5kg" } });

    expect(screen.queryByText(/como novo produto/)).toBeNull();
  });
});
