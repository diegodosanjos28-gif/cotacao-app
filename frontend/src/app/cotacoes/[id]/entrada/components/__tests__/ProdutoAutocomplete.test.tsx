// Autocomplete de produto do grid unificado (Prompt 12 — generaliza o que antes era
// exclusivo da tela "Ajuste de Lista" do WhatsApp, removida). Sem onUsarNomeLivre (uso
// em LinhaGridProdutos, editar item já persistido), não oferece "+ usar como novo
// produto" — essa opção só existe quando o chamador passa onUsarNomeLivre (uso em
// NovaLinhaGridProdutos, adicionar item manual, que aceita nome nunca visto).
//
// Reescrito para a busca paginada no servidor (ver componente): não recebe mais
// `produtos` como prop — mocka buscarProdutos (de @/lib/api) em vez de passar um
// array pré-carregado.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import ProdutoAutocomplete from "@/app/cotacoes/[id]/entrada/components/ProdutoAutocomplete";
import { Page, Produto } from "@/lib/types";

const { buscarProdutosMock } = vi.hoisted(() => ({
  buscarProdutosMock: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    buscarProdutos: buscarProdutosMock,
  };
});

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

function makePage(content: Produto[], overrides: Partial<Page<Produto>> = {}): Page<Produto> {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 8, ...overrides };
}

const PRODUTOS = [
  makeProduto({ id: "prod-1", nome: "Arroz Branco 5kg" }),
  makeProduto({ id: "prod-2", nome: "Feijão Carioca 1kg" }),
  makeProduto({ id: "prod-3", nome: "Sazon Legumes 60g" }),
];

function renderAutocomplete(overrides: Partial<React.ComponentProps<typeof ProdutoAutocomplete>> = {}) {
  const onSelecionar = vi.fn();
  render(<ProdutoAutocomplete valorAtualNome={null} onSelecionar={onSelecionar} {...overrides} />);
  return { onSelecionar };
}

function input() {
  return screen.getByPlaceholderText("Buscar produto...") as HTMLInputElement;
}

beforeEach(() => {
  buscarProdutosMock.mockReset();
  buscarProdutosMock.mockResolvedValue(makePage(PRODUTOS));
});

describe("ProdutoAutocomplete", () => {
  it("mostra o nome do produto já selecionado (valorAtualNome) quando fechado, sem texto digitado", () => {
    renderAutocomplete({ valorAtualNome: "Arroz Branco 5kg" });
    expect(input().value).toBe("Arroz Branco 5kg");
    expect(buscarProdutosMock).not.toHaveBeenCalled();
  });

  it("ao focar, busca no servidor (sem termo) e abre a lista com o resultado", async () => {
    renderAutocomplete({ valorAtualNome: "Arroz Branco 5kg" });
    fireEvent.focus(input());

    expect(input().value).toBe("");
    await waitFor(() => expect(buscarProdutosMock).toHaveBeenCalledWith({ q: undefined, page: 0, size: 8 }));
    expect(await screen.findByText("Feijão Carioca 1kg")).toBeTruthy();
    expect(screen.getByText("Sazon Legumes 60g")).toBeTruthy();
  });

  it("digitar um termo dispara busca debounced no servidor com o termo digitado", async () => {
    buscarProdutosMock.mockResolvedValue(makePage([PRODUTOS[1]]));
    renderAutocomplete();
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "feijao" } });

    await waitFor(() => expect(buscarProdutosMock).toHaveBeenCalledWith({ q: "feijao", page: 0, size: 8 }));
    expect(await screen.findByText("Feijão Carioca 1kg")).toBeTruthy();
    expect(screen.queryByText("Arroz Branco 5kg")).toBeNull();
  });

  it("sem onUsarNomeLivre: busca sem resultado mostra 'Nenhum produto encontrado' (e não oferece novo produto)", async () => {
    buscarProdutosMock.mockResolvedValue(makePage([], { totalElements: 0 }));
    renderAutocomplete();
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "xicrocola inexistente" } });

    expect(await screen.findByText("Nenhum produto encontrado")).toBeTruthy();
    expect(screen.queryByText(/como novo produto/)).toBeNull();
  });

  it("clicar numa sugestão chama onSelecionar com o produto certo, limpa o texto e fecha a lista", async () => {
    const { onSelecionar } = renderAutocomplete();
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "sazon" } });

    fireEvent.click(await screen.findByText("Sazon Legumes 60g"));

    expect(onSelecionar).toHaveBeenCalledWith(PRODUTOS[2]);
    expect(screen.queryByText("Nenhum produto encontrado")).toBeNull();
    expect(input().value).toBe("");
  });

  it("fecha a lista de sugestões ao clicar fora do componente", async () => {
    render(
      <div>
        <ProdutoAutocomplete valorAtualNome={null} onSelecionar={vi.fn()} />
        <button type="button">Fora</button>
      </div>,
    );
    fireEvent.focus(input());
    expect(await screen.findByText("Feijão Carioca 1kg")).toBeTruthy();

    fireEvent.mouseDown(screen.getByRole("button", { name: "Fora" }));

    expect(screen.queryByText("Feijão Carioca 1kg")).toBeNull();
  });

  it("desabilitado: input fica disabled e não abre lista de sugestões ao focar", async () => {
    renderAutocomplete({ disabled: true });
    expect(input().disabled).toBe(true);

    // fireEvent.focus dispara o evento sintético mesmo num input disabled (diferente
    // de um foco real de usuário, que o navegador bloqueia) — o dropdown continua
    // controlado só por `disabled` no JSX (aberto && !disabled), então mesmo que o
    // fetch dispare, a lista não é renderizada. Aguarda o fetch resolver (mesmo sem
    // efeito visível) pra não vazar a atualização de estado pro teste seguinte.
    fireEvent.focus(input());
    await waitFor(() => expect(buscarProdutosMock).toHaveBeenCalled());
    expect(screen.queryByText("Feijão Carioca 1kg")).toBeNull();
  });
});

describe("ProdutoAutocomplete — paginação (resultado com mais de 8 itens)", () => {
  const PAGINA_0 = makePage(
    Array.from({ length: 8 }, (_, i) => makeProduto({ id: `prod-pg0-${i}`, nome: `Produto Página 0 - ${i}` })),
    { totalElements: 10, totalPages: 2, number: 0 },
  );
  const PAGINA_1 = makePage([makeProduto({ id: "prod-pg1-0", nome: "Produto Página 1 - Extra" })], {
    totalElements: 10,
    totalPages: 2,
    number: 1,
  });

  it("não mostra controles de paginação quando o total cabe numa página só (<= 8)", async () => {
    renderAutocomplete();
    fireEvent.focus(input());

    await screen.findByText("Feijão Carioca 1kg");
    expect(screen.queryByRole("button", { name: "Próxima página" })).toBeNull();
  });

  it("mostra controles de paginação quando o total excede o tamanho da página (> 8)", async () => {
    buscarProdutosMock.mockResolvedValue(PAGINA_0);
    renderAutocomplete();
    fireEvent.focus(input());

    await screen.findByText("Produto Página 0 - 0");
    expect(screen.getByRole("button", { name: "Próxima página" })).toBeTruthy();
    expect(screen.getByText("1/2")).toBeTruthy();
  });

  it("clicar em 'Próxima página' busca a página seguinte no servidor e mostra o resultado dela", async () => {
    buscarProdutosMock.mockImplementation(({ page }: { q?: string; page: number; size: number }) =>
      Promise.resolve(page === 1 ? PAGINA_1 : PAGINA_0),
    );
    renderAutocomplete();
    fireEvent.focus(input());
    await screen.findByText("Produto Página 0 - 0");

    fireEvent.click(screen.getByRole("button", { name: "Próxima página" }));

    await waitFor(() => expect(buscarProdutosMock).toHaveBeenCalledWith({ q: undefined, page: 1, size: 8 }));
    expect(await screen.findByText("Produto Página 1 - Extra")).toBeTruthy();
    expect(screen.queryByText("Produto Página 0 - 0")).toBeNull();
  });

  it("clicar em 'Página anterior' volta pra página anterior", async () => {
    buscarProdutosMock.mockImplementation(({ page }: { q?: string; page: number; size: number }) =>
      Promise.resolve(page === 1 ? PAGINA_1 : PAGINA_0),
    );
    renderAutocomplete();
    fireEvent.focus(input());
    await screen.findByText("Produto Página 0 - 0");
    fireEvent.click(screen.getByRole("button", { name: "Próxima página" }));
    await screen.findByText("Produto Página 1 - Extra");

    fireEvent.click(screen.getByRole("button", { name: "Página anterior" }));

    await waitFor(() => expect(buscarProdutosMock).toHaveBeenLastCalledWith({ q: undefined, page: 0, size: 8 }));
    expect(await screen.findByText("Produto Página 0 - 0")).toBeTruthy();
  });
});

describe("ProdutoAutocomplete — onUsarNomeLivre (Prompt 12, uso em NovaLinhaGridProdutos)", () => {
  it("com onUsarNomeLivre, digitar um nome sem match exato na página atual oferece '+ usar como novo produto'", async () => {
    renderAutocomplete({ onUsarNomeLivre: vi.fn() });
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "produto nunca visto" } });

    expect(await screen.findByText('+ usar "produto nunca visto" como novo produto')).toBeTruthy();
  });

  it("clicar em '+ usar como novo produto' chama onUsarNomeLivre com o texto digitado", async () => {
    const onUsarNomeLivre = vi.fn();
    renderAutocomplete({ onUsarNomeLivre });
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "produto nunca visto" } });
    fireEvent.click(await screen.findByText('+ usar "produto nunca visto" como novo produto'));

    expect(onUsarNomeLivre).toHaveBeenCalledWith("produto nunca visto");
  });

  it("com match exato na página atual (mesmo nome de um produto já retornado pelo servidor), não oferece criar novo", async () => {
    buscarProdutosMock.mockResolvedValue(makePage([PRODUTOS[0]]));
    renderAutocomplete({ onUsarNomeLivre: vi.fn() });
    fireEvent.focus(input());
    fireEvent.change(input(), { target: { value: "Arroz Branco 5kg" } });

    await screen.findByText("Arroz Branco 5kg");
    expect(screen.queryByText(/como novo produto/)).toBeNull();
  });
});
