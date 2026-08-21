// CotacoesAnterioresCarrossel — mesma mecânica de paginação por cursor de
// EconomiaCarrossel (ver EconomiaCarrossel.test.tsx), sem filtro de período, mais a
// integração com useCotacoesOcultas (soft-hide client-side via "Fechar").

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import CotacoesAnterioresCarrossel from "../CotacoesAnterioresCarrossel";
import { CotacaoAnteriorCursorPage, CotacaoAnteriorCursorResponse } from "@/lib/types";

const { cotacoesAnterioresMock, pushMock } = vi.hoisted(() => ({
  cotacoesAnterioresMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, cotacoesAnteriores: cotacoesAnterioresMock };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return {
    ...actual,
    useAuth: () => ({ ready: true, authenticated: true, papel: "OPERADOR_CLIENTE", tenantId: "t-1" }),
  };
});

function makeCotacao(overrides: Partial<CotacaoAnteriorCursorResponse> = {}): CotacaoAnteriorCursorResponse {
  return {
    id: "cot-1",
    finalizadaEm: "2026-08-16T14:32:00Z",
    itensListaBase: 48,
    itensCotados: 44,
    fornecedoresCount: 3,
    ...overrides,
  };
}

function makePagina(overrides: Partial<CotacaoAnteriorCursorPage> = {}): CotacaoAnteriorCursorPage {
  const items = overrides.items ?? [makeCotacao()];
  return { items, nextCursor: null, hasMore: false, totalElements: items.length, ...overrides };
}

beforeEach(() => {
  cotacoesAnterioresMock.mockReset();
  pushMock.mockReset();
  localStorage.clear();
});

describe("CotacoesAnterioresCarrossel — primeira página", () => {
  it("busca a primeira página (cursor null) e renderiza os mini cards", async () => {
    cotacoesAnterioresMock.mockResolvedValue(
      makePagina({ items: [makeCotacao({ id: "cot-1" }), makeCotacao({ id: "cot-2" })] }),
    );

    render(<CotacoesAnterioresCarrossel />);

    await waitFor(() => expect(cotacoesAnterioresMock).toHaveBeenCalledWith(null, 10));
    expect(await screen.findAllByText("Reabrir")).toHaveLength(2);
  });

  it("sem nenhuma cotação anterior mostra o estado vazio", async () => {
    cotacoesAnterioresMock.mockResolvedValue(makePagina({ items: [] }));

    render(<CotacoesAnterioresCarrossel />);

    expect(await screen.findByText("Nenhuma cotação anterior por aqui ainda.")).toBeTruthy();
  });

  it("erro na busca mostra mensagem, sem travar a tela", async () => {
    cotacoesAnterioresMock.mockRejectedValue(new Error("falhou"));

    render(<CotacoesAnterioresCarrossel />);

    expect(await screen.findByText("Não foi possível carregar as cotações anteriores.")).toBeTruthy();
  });
});

describe("CotacoesAnterioresCarrossel — paginação por cursor (scroll perto do fim)", () => {
  it("acrescenta a próxima página (não substitui) usando o cursor recebido", async () => {
    const pagina1 = makePagina({ items: [makeCotacao({ id: "cot-1" })], nextCursor: "cursor-1", hasMore: true });
    const pagina2 = makePagina({ items: [makeCotacao({ id: "cot-2" })] });
    cotacoesAnterioresMock.mockResolvedValueOnce(pagina1).mockResolvedValueOnce(pagina2);

    const { container } = render(<CotacoesAnterioresCarrossel />);
    await waitFor(() => expect(screen.getAllByText("Reabrir")).toHaveLength(1));

    const track = container.querySelector(".overflow-x-auto") as HTMLDivElement;
    Object.defineProperty(track, "scrollWidth", { value: 2000, configurable: true });
    Object.defineProperty(track, "clientWidth", { value: 1000, configurable: true });
    Object.defineProperty(track, "scrollLeft", { value: 1800, configurable: true });
    fireEvent.scroll(track);

    await waitFor(() => expect(cotacoesAnterioresMock).toHaveBeenCalledTimes(2));
    expect(cotacoesAnterioresMock).toHaveBeenNthCalledWith(2, "cursor-1", 10);
    expect(screen.getAllByText("Reabrir")).toHaveLength(2);
  });

  it("não busca de novo quando hasMore é false, mesmo com scroll no fim", async () => {
    cotacoesAnterioresMock.mockResolvedValue(makePagina());

    const { container } = render(<CotacoesAnterioresCarrossel />);
    await waitFor(() => expect(cotacoesAnterioresMock).toHaveBeenCalledTimes(1));

    const track = container.querySelector(".overflow-x-auto") as HTMLDivElement;
    Object.defineProperty(track, "scrollWidth", { value: 2000, configurable: true });
    Object.defineProperty(track, "clientWidth", { value: 1000, configurable: true });
    Object.defineProperty(track, "scrollLeft", { value: 1800, configurable: true });
    fireEvent.scroll(track);

    await new Promise((r) => setTimeout(r, 50));
    expect(cotacoesAnterioresMock).toHaveBeenCalledTimes(1);
  });
});

describe("CotacoesAnterioresCarrossel — soft-hide via 'Fechar' (useCotacoesOcultas)", () => {
  it("clicar em 'Fechar' remove o card da lista renderizada mesmo estando na resposta da API", async () => {
    cotacoesAnterioresMock.mockResolvedValue(
      makePagina({ items: [makeCotacao({ id: "cot-1" }), makeCotacao({ id: "cot-2" })] }),
    );

    render(<CotacoesAnterioresCarrossel />);
    await waitFor(() => expect(screen.getAllByText("Reabrir")).toHaveLength(2));

    fireEvent.click(screen.getAllByText("Fechar")[0]);

    await waitFor(() => expect(screen.getAllByText("Reabrir")).toHaveLength(1));
    expect(localStorage.getItem("cotacoes.ocultas.t-1")).toContain("cot-1");
  });

  it("um item já oculto (localStorage prévio) não aparece nem na primeira renderização", async () => {
    localStorage.setItem("cotacoes.ocultas.t-1", JSON.stringify(["cot-1"]));
    cotacoesAnterioresMock.mockResolvedValue(
      makePagina({ items: [makeCotacao({ id: "cot-1" }), makeCotacao({ id: "cot-2" })] }),
    );

    render(<CotacoesAnterioresCarrossel />);

    await waitFor(() => expect(screen.getAllByText("Reabrir")).toHaveLength(1));
  });
});
