import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import ConferenciaNotaModal from "../ConferenciaNotaModal";
import { ComparativoItemResponse, CotacaoFinalizadaCursorResponse, PrecoFornecedor } from "@/lib/types";

const { comparativoMock } = vi.hoisted(() => ({ comparativoMock: vi.fn() }));
const { exportarConferenciaNotaMock } = vi.hoisted(() => ({ exportarConferenciaNotaMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, comparativo: comparativoMock };
});

vi.mock("@/lib/conferenciaNotaPdf", () => ({
  exportarConferenciaNota: exportarConferenciaNotaMock,
}));

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
    precosPorFornecedor: [makeOferta()],
    ...overrides,
  };
}

function makeCotacao(overrides: Partial<CotacaoFinalizadaCursorResponse> = {}): CotacaoFinalizadaCursorResponse {
  return {
    id: "cot-1",
    finalizadaEm: "2026-08-16T14:32:00Z",
    itensCotados: 1,
    valorTotalComprado: 20,
    economizado: 0,
    economizadoPct: 0,
    fornecedoresCount: 1,
    ...overrides,
  };
}

beforeEach(() => {
  comparativoMock.mockReset();
  exportarConferenciaNotaMock.mockReset();
});

describe("ConferenciaNotaModal — Exportar PDF", () => {
  it("mostra o botão Exportar PDF e chama exportarConferenciaNota com a cotação e os itens carregados", async () => {
    const itens = [makeItem()];
    comparativoMock.mockResolvedValue(itens);
    const cotacao = makeCotacao();

    render(<ConferenciaNotaModal cotacao={cotacao} onClose={() => {}} />);

    const botao = await screen.findByRole("button", { name: "Exportar PDF" });
    fireEvent.click(botao);

    expect(exportarConferenciaNotaMock).toHaveBeenCalledWith(cotacao, itens);
  });

  it("sem nenhum fornecedor com oferta válida, não mostra o botão Exportar PDF", async () => {
    comparativoMock.mockResolvedValue([]);

    render(<ConferenciaNotaModal cotacao={makeCotacao()} onClose={() => {}} />);

    await waitFor(() => expect(screen.getByText("Nenhum fornecedor com oferta válida nesta cotação.")).toBeTruthy());
    expect(screen.queryByRole("button", { name: "Exportar PDF" })).toBeNull();
  });

  it("modal fechado (cotacao null) não busca nem renderiza nada visível", () => {
    render(<ConferenciaNotaModal cotacao={null} onClose={() => {}} />);

    expect(screen.queryByText("Conferência de Nota")).toBeNull();
  });
});
