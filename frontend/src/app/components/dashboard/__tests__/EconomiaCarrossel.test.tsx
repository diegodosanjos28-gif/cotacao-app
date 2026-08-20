import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import EconomiaCarrossel from "../EconomiaCarrossel";
import { mesAtual, primeiroDiaMes, ultimoDiaMes } from "../competencia";
import { CotacaoFinalizadaCursorPage, CotacaoFinalizadaCursorResponse } from "@/lib/types";

const { economiaCarrosselMock } = vi.hoisted(() => ({ economiaCarrosselMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, economiaCarrossel: economiaCarrosselMock };
});

// O carrossel abre com o mês vigente pré-selecionado (feedback do usuário: "add a
// default range") — calculado aqui com os mesmos helpers do componente, pra não
// hardcodar uma data e quebrar o teste dependendo de quando ele rodar.
const DEFAULT_INICIO = primeiroDiaMes(mesAtual());
const DEFAULT_FIM = ultimoDiaMes(mesAtual());

function makeCotacao(overrides: Partial<CotacaoFinalizadaCursorResponse> = {}): CotacaoFinalizadaCursorResponse {
  return {
    id: "cot-1",
    finalizadaEm: "2026-08-16T14:32:00Z",
    itensCotados: 48,
    valorTotalComprado: 12840.5,
    economizado: 2310.8,
    economizadoPct: 15.2,
    fornecedoresCount: 3,
    ...overrides,
  };
}

function makePagina(overrides: Partial<CotacaoFinalizadaCursorPage> = {}): CotacaoFinalizadaCursorPage {
  const items = overrides.items ?? [makeCotacao()];
  return {
    items,
    nextCursor: null,
    hasMore: false,
    totalElements: items.length,
    ...overrides,
  };
}

beforeEach(() => {
  economiaCarrosselMock.mockReset();
});

// carregarMais() é disparado via setTimeout(0) no mount (ver comentário em
// EconomiaCarrossel.tsx sobre o lint react-hooks/set-state-in-effect) — os testes
// usam findBy/waitFor, que já esperam o suficiente pra esse tick passar.

describe("EconomiaCarrossel — primeira página", () => {
  it("busca a primeira página (cursor null, período do mês vigente por padrão) e renderiza os cards", async () => {
    const pagina = makePagina({
      items: [makeCotacao({ id: "cot-1" }), makeCotacao({ id: "cot-2", itensCotados: 10 })],
      totalElements: 2,
    });
    economiaCarrosselMock.mockResolvedValue(pagina);

    render(<EconomiaCarrossel />);

    await waitFor(() => expect(economiaCarrosselMock).toHaveBeenCalledWith(null, 10, DEFAULT_INICIO, DEFAULT_FIM));
    expect(await screen.findByText("2 cotações")).toBeTruthy();
    expect(screen.getAllByText("Conferência")).toHaveLength(2);
  });

  it("cotação única no card usa singular ('1 cotação')", async () => {
    economiaCarrosselMock.mockResolvedValue(makePagina());

    render(<EconomiaCarrossel />);

    expect(await screen.findByText("1 cotação")).toBeTruthy();
  });

  it("badge mostra o total real do backend, não só o tamanho da primeira leva carregada", async () => {
    // 1 item na página, mas totalElements diz que há 3 no total — o achado que
    // motivou essa mudança: o badge não pode refletir só o que já chegou.
    economiaCarrosselMock.mockResolvedValue(makePagina({ items: [makeCotacao()], hasMore: true, totalElements: 3 }));

    render(<EconomiaCarrossel />);

    expect(await screen.findByText("3 cotações")).toBeTruthy();
    expect(screen.getAllByText("Conferência")).toHaveLength(1);
  });

  it("sem nenhuma cotação no período padrão (mês vigente), mostra o estado vazio específico de filtro", async () => {
    economiaCarrosselMock.mockResolvedValue(makePagina({ items: [], totalElements: 0 }));

    render(<EconomiaCarrossel />);

    expect(await screen.findByText("Nenhuma cotação finalizada no período selecionado.")).toBeTruthy();
  });

  it("erro na busca mostra mensagem, sem travar a tela", async () => {
    economiaCarrosselMock.mockRejectedValue(new Error("falhou"));

    render(<EconomiaCarrossel />);

    expect(await screen.findByText("Não foi possível carregar as cotações registradas.")).toBeTruthy();
  });
});

describe("EconomiaCarrossel — paginação por cursor (scroll perto do fim)", () => {
  it("acrescenta a próxima página (não substitui) e usa o cursor recebido, não o histórico inteiro de uma vez", async () => {
    const pagina1 = makePagina({
      items: [makeCotacao({ id: "cot-1" })],
      nextCursor: "2026-08-16T14:32:00Z_cot-1",
      hasMore: true,
      totalElements: 2,
    });
    const pagina2 = makePagina({ items: [makeCotacao({ id: "cot-2" })], totalElements: 2 });
    economiaCarrosselMock.mockResolvedValueOnce(pagina1).mockResolvedValueOnce(pagina2);

    const { container } = render(<EconomiaCarrossel />);

    await waitFor(() => expect(screen.getByText("2 cotações")).toBeTruthy());

    // jsdom não calcula layout real — simula "quase no fim da track" definindo as
    // métricas de scroll manualmente antes de disparar o evento nativo.
    const track = container.querySelector(".flex.gap-3\\.5.overflow-x-auto") as HTMLDivElement;
    Object.defineProperty(track, "scrollWidth", { value: 2000, configurable: true });
    Object.defineProperty(track, "clientWidth", { value: 1000, configurable: true });
    Object.defineProperty(track, "scrollLeft", { value: 1800, configurable: true });

    fireEvent.scroll(track);

    await waitFor(() => expect(economiaCarrosselMock).toHaveBeenCalledTimes(2));
    expect(economiaCarrosselMock).toHaveBeenNthCalledWith(
      2, "2026-08-16T14:32:00Z_cot-1", 10, DEFAULT_INICIO, DEFAULT_FIM);
    // Acrescentou — os 2 cards (das 2 páginas) aparecem juntos, nenhum some.
    expect(screen.getAllByText("Conferência")).toHaveLength(2);
  });

  it("não busca de novo quando hasMore é false, mesmo com scroll no fim", async () => {
    economiaCarrosselMock.mockResolvedValue(makePagina());

    const { container } = render(<EconomiaCarrossel />);
    await waitFor(() => expect(economiaCarrosselMock).toHaveBeenCalledTimes(1));

    const track = container.querySelector(".flex.gap-3\\.5.overflow-x-auto") as HTMLDivElement;
    Object.defineProperty(track, "scrollWidth", { value: 2000, configurable: true });
    Object.defineProperty(track, "clientWidth", { value: 1000, configurable: true });
    Object.defineProperty(track, "scrollLeft", { value: 1800, configurable: true });
    fireEvent.scroll(track);

    // Dá tempo pra uma eventual (indevida) segunda chamada acontecer.
    await new Promise((r) => setTimeout(r, 50));
    expect(economiaCarrosselMock).toHaveBeenCalledTimes(1);
  });
});

describe("EconomiaCarrossel — filtro de período", () => {
  it("abre com o mês vigente pré-selecionado nos dois campos de data", async () => {
    economiaCarrosselMock.mockResolvedValue(makePagina());

    render(<EconomiaCarrossel />);
    await screen.findByText("1 cotação");

    expect((screen.getByLabelText("Data inicial do período") as HTMLInputElement).value).toBe(DEFAULT_INICIO);
    expect((screen.getByLabelText("Data final do período") as HTMLInputElement).value).toBe(DEFAULT_FIM);
    // Filtro padrão já ativo — "Limpar" fica visível desde o primeiro carregamento.
    expect(await screen.findByText("Limpar")).toBeTruthy();
  });

  it("trocar a data inicial reinicia a paginação e busca com o novo inicio + o fim atual", async () => {
    const paginaInicial = makePagina({
      items: [makeCotacao({ id: "cot-1" })],
      nextCursor: "cursor-1",
      hasMore: true,
      totalElements: 5,
    });
    const paginaFiltrada = makePagina({ items: [makeCotacao({ id: "cot-filtrada" })], totalElements: 1 });
    economiaCarrosselMock.mockResolvedValueOnce(paginaInicial).mockResolvedValueOnce(paginaFiltrada);

    render(<EconomiaCarrossel />);
    await waitFor(() => expect(screen.getByText("5 cotações")).toBeTruthy());

    // Precisa ser garantidamente diferente de DEFAULT_INICIO (mês vigente) — um
    // valor igual ao já selecionado é um no-op de estado (React não re-renderiza
    // pra um primitivo idêntico), então não dispararia o remount/refetch testado.
    const novoInicio = "2020-01-15";
    fireEvent.change(screen.getByLabelText("Data inicial do período"), { target: { value: novoInicio } });

    await waitFor(() => expect(economiaCarrosselMock).toHaveBeenCalledTimes(2));
    expect(economiaCarrosselMock).toHaveBeenNthCalledWith(2, null, 10, novoInicio, DEFAULT_FIM);
    // A leva anterior (cot-1) some — o card volta a mostrar só o resultado do novo
    // período, não os dois acumulados (o filtro reinicia, não acrescenta).
    await waitFor(() => expect(screen.getByText("1 cotação")).toBeTruthy());
    expect(screen.getAllByText("Conferência")).toHaveLength(1);
  });

  it("'Limpar' remove o filtro padrão e busca o histórico inteiro (inicio/fim null)", async () => {
    economiaCarrosselMock.mockResolvedValue(makePagina());

    render(<EconomiaCarrossel />);
    await screen.findByText("1 cotação");
    await screen.findByText("Limpar");

    fireEvent.click(screen.getByText("Limpar"));

    await waitFor(() => expect(economiaCarrosselMock).toHaveBeenCalledTimes(2));
    expect(economiaCarrosselMock).toHaveBeenNthCalledWith(2, null, 10, null, null);
    await waitFor(() => expect(screen.queryByText("Limpar")).toBeNull());
  });
});
