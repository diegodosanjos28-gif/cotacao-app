import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import FornecedoresPainel from "../FornecedoresPainel";
import { Fornecedor } from "@/lib/types";

const { listarFornecedoresMock } = vi.hoisted(() => ({ listarFornecedoresMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, listarFornecedores: listarFornecedoresMock };
});

function makeFornecedor(nome: string, overrides: Partial<Fornecedor> = {}): Fornecedor {
  return {
    id: `forn-${nome}`,
    nome,
    prazoEntregaPadrao: "48h",
    condicaoPagamentoPadrao: "30 dias",
    pedidoMinimoPadrao: null,
    observacoesPadrao: null,
    status: "ATIVO",
    origemCadastro: "MANUAL",
    criadoEm: "2026-08-01T10:00:00Z",
    atualizadoEm: null,
    ...overrides,
  };
}

// 8 fornecedores — mais que o tamanho de página (5) do painel, pra exercitar
// paginação real, não só o caso "tudo cabe numa página".
const OITO_FORNECEDORES = [
  "Atacadão Silva", "Distribuidora Norte", "Comercial Boa Vista", "Mercado Central",
  "Frios & Cia", "Bebidas Sul", "Hortifruti Leste", "Padaria Modelo",
].map((nome) => makeFornecedor(nome));

beforeEach(() => {
  listarFornecedoresMock.mockReset();
});

describe("FornecedoresPainel — paginação", () => {
  it("com mais fornecedores que o tamanho de página, mostra só a primeira leva e os controles de página", async () => {
    listarFornecedoresMock.mockResolvedValue(OITO_FORNECEDORES);

    render(<FornecedoresPainel />);

    await waitFor(() => expect(screen.getByText("Atacadão Silva")).toBeTruthy());
    // Página 1 = os 5 primeiros da lista.
    expect(screen.getByText("Frios & Cia")).toBeTruthy();
    expect(screen.queryByText("Bebidas Sul")).toBeNull();
    expect(screen.queryByText("Hortifruti Leste")).toBeNull();
    expect(screen.queryByText("Padaria Modelo")).toBeNull();
    expect(screen.getByText("1/2")).toBeTruthy();
    expect(screen.getByText("1–5 de 8")).toBeTruthy();
  });

  it("'próxima página' avança pro restante dos fornecedores", async () => {
    listarFornecedoresMock.mockResolvedValue(OITO_FORNECEDORES);

    render(<FornecedoresPainel />);
    await waitFor(() => expect(screen.getByText("Atacadão Silva")).toBeTruthy());

    fireEvent.click(screen.getByLabelText("Fornecedores — próxima página"));

    expect(await screen.findByText("Bebidas Sul")).toBeTruthy();
    expect(screen.getByText("Hortifruti Leste")).toBeTruthy();
    expect(screen.getByText("Padaria Modelo")).toBeTruthy();
    expect(screen.queryByText("Atacadão Silva")).toBeNull();
    expect(screen.getByText("2/2")).toBeTruthy();

    // No fim, "próxima" desabilita; "anterior" volta pra página 1.
    expect((screen.getByLabelText("Fornecedores — próxima página") as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText("Fornecedores — página anterior"));
    expect(await screen.findByText("Atacadão Silva")).toBeTruthy();
  });

  it("com 5 ou menos fornecedores, não mostra os controles de página", async () => {
    listarFornecedoresMock.mockResolvedValue(OITO_FORNECEDORES.slice(0, 5));

    render(<FornecedoresPainel />);
    await waitFor(() => expect(screen.getByText("Atacadão Silva")).toBeTruthy());

    expect(screen.queryByLabelText("Fornecedores — próxima página")).toBeNull();
  });
});

describe("FornecedoresPainel — busca", () => {
  it("filtra pelo nome instantaneamente (client-side) e reinicia a paginação", async () => {
    listarFornecedoresMock.mockResolvedValue(OITO_FORNECEDORES);

    render(<FornecedoresPainel />);
    await waitFor(() => expect(screen.getByText("Atacadão Silva")).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText("Buscar fornecedor..."), { target: { value: "Sul" } });

    // Só "Bebidas Sul" bate — sem paginação nenhuma (chamada de API nenhuma, só 1
    // fetch total feito no mount).
    await waitFor(() => expect(screen.getByText("Bebidas Sul")).toBeTruthy());
    expect(screen.queryByText("Atacadão Silva")).toBeNull();
    expect(screen.queryByLabelText("Fornecedores — próxima página")).toBeNull();
    expect(listarFornecedoresMock).toHaveBeenCalledTimes(1);
  });

  it("busca sem resultado mostra mensagem específica, diferente de 'nenhum cadastrado'", async () => {
    listarFornecedoresMock.mockResolvedValue(OITO_FORNECEDORES);

    render(<FornecedoresPainel />);
    await waitFor(() => expect(screen.getByText("Atacadão Silva")).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText("Buscar fornecedor..."), { target: { value: "xyz-inexistente" } });

    expect(await screen.findByText("Nenhum fornecedor encontrado.")).toBeTruthy();
  });

  it("busca é case-insensitive", async () => {
    listarFornecedoresMock.mockResolvedValue(OITO_FORNECEDORES);

    render(<FornecedoresPainel />);
    await waitFor(() => expect(screen.getByText("Atacadão Silva")).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText("Buscar fornecedor..."), { target: { value: "MERCADO" } });

    expect(await screen.findByText("Mercado Central")).toBeTruthy();
  });
});
