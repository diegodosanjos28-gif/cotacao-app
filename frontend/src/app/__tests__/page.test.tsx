// Cobre o Dashboard (/) migrado de <table> manual (CotacaoLinha/EconomiaCotacaoLinha,
// cada um dono do seu próprio par de <tr>) para o DataGrid compartilhado, onde a linha
// de resumo virou uma coluna `cell` e a linha expandida virou `renderRowDetail`. A
// mecânica genérica do DataGrid (colSpan, loading/empty) já é coberta em
// components/grid/__tests__/DataGrid.test.tsx, e o conteúdo das linhas expandidas em
// si (CotacaoResumoExpandido/EconomiaCotacaoDetalhe) tem teste próprio — aqui só o que é
// específico da integração: o toggle de expand/colapso via DataGrid.getIsExpanded() nas
// duas tabelas, e os casos de "Todas as cotações" que dependiam de props de CotacaoLinha
// (badge "Ajuste pendente", destino do link do título).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import DashboardPage from "@/app/page";
import { Cotacao, ComparativoItemResponse, PrecoFornecedor } from "@/lib/types";

const { pushMock, replaceMock } = vi.hoisted(() => ({ pushMock: vi.fn(), replaceMock: vi.fn() }));
const { listarCotacoesMock, comparativoMock } = vi.hoisted(() => ({
  listarCotacoesMock: vi.fn(),
  comparativoMock: vi.fn(),
}));
const { exportarConferenciaNotaMock } = vi.hoisted(() => ({ exportarConferenciaNotaMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  usePathname: () => "/",
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    listarCotacoes: listarCotacoesMock,
    comparativo: comparativoMock,
  };
});

vi.mock("@/lib/conferenciaNotaPdf", () => ({
  exportarConferenciaNota: exportarConferenciaNotaMock,
}));

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return {
    ...actual,
    useAuth: () => ({ ready: true, authenticated: true, papel: "OPERADOR_CLIENTE", tenantId: "t-1" }),
  };
});

function makeCotacao(overrides: Partial<Cotacao> = {}): Cotacao {
  return {
    id: "cot-1",
    criadoPor: null,
    titulo: "Cotação teste",
    status: "EM_ANDAMENTO",
    canalOrigem: "WEB",
    listaRevisada: true,
    ultimaAtividadeEm: null,
    cenarioSelecionado: null,
    finalizadaEm: null,
    criadoEm: "2026-07-30T10:00:00Z",
    atualizadoEm: null,
    ...overrides,
  };
}

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

function paginaDe(cotacoes: Cotacao[]) {
  return { content: cotacoes, totalElements: cotacoes.length, totalPages: 1, number: 0, size: 20 };
}

function renderPage() {
  render(<DashboardPage />);
}

// A tabela "Todas as cotações" recalcula `colunasCotacoes` (e portanto o estado de
// expansão de linha do react-table, que vive dentro da própria tabela) toda vez que
// `itensPorCotacao` muda de identidade — interagir antes desse segundo fetch assentar
// faz o clique no chevron "sumir" num re-render seguinte. Espera a coluna "Economia
// potencial" trocar de "…" (carregando) pelo valor final antes de qualquer interação.
async function aguardarEconomiaPotencialCarregada() {
  await waitFor(() => expect(screen.queryByText("…")).toBeNull());
}

beforeEach(() => {
  pushMock.mockReset();
  replaceMock.mockReset();
  listarCotacoesMock.mockReset();
  comparativoMock.mockReset();
  exportarConferenciaNotaMock.mockReset();
  comparativoMock.mockResolvedValue([]);
});

describe("Dashboard — tabela 'Todas as cotações', coluna Título", () => {
  it("expande/colapsa o resumo da linha ao clicar no chevron, alternando aria-expanded", async () => {
    listarCotacoesMock.mockResolvedValue(paginaDe([makeCotacao({ id: "cot-1", titulo: "Cotação Web" })]));
    comparativoMock.mockResolvedValue([]);

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Web")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    const chevron = screen.getByRole("button", { name: "Expandir detalhes" });
    expect(chevron.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText("Nenhum produto adicionado a esta cotação ainda.")).toBeNull();

    fireEvent.click(chevron);

    await waitFor(() => expect(chevron.getAttribute("aria-expanded")).toBe("true"));
    expect(screen.getByRole("button", { name: "Recolher detalhes" })).toBeTruthy();
    expect(screen.getByText("Nenhum produto adicionado a esta cotação ainda.")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Recolher detalhes" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Expandir detalhes" })).toBeTruthy());
    expect(screen.queryByText("Nenhum produto adicionado a esta cotação ainda.")).toBeNull();
  });
});

describe("Dashboard — tabela 'Todas as cotações', coluna Canal", () => {
  it("mostra o badge 'Ajuste pendente' só para WHATSAPP com lista ainda não revisada", async () => {
    listarCotacoesMock.mockResolvedValue(
      paginaDe([
        makeCotacao({ id: "cot-1", titulo: "Cotação Web", canalOrigem: "WEB", listaRevisada: false }),
        makeCotacao({ id: "cot-2", titulo: "Cotação Whats Pendente", canalOrigem: "WHATSAPP", listaRevisada: false }),
        makeCotacao({ id: "cot-3", titulo: "Cotação Whats Revisada", canalOrigem: "WHATSAPP", listaRevisada: true }),
      ]),
    );

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Web")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    expect(screen.getByText("Ajuste pendente")).toBeTruthy();
    // Só uma das três linhas atende a combinação exata (WHATSAPP + listaRevisada=false).
    expect(screen.getAllByText("Ajuste pendente")).toHaveLength(1);

    const linhaComBadge = screen.getByText("Ajuste pendente").closest("tr")!;
    expect(within(linhaComBadge).getByText("Cotação Whats Pendente")).toBeTruthy();
  });
});

describe("Dashboard — tabela 'Todas as cotações', link do título", () => {
  it("o título linka sempre para /cotacoes/{id}/entrada, mesmo em WHATSAPP com ajuste pendente", async () => {
    listarCotacoesMock.mockResolvedValue(
      paginaDe([makeCotacao({ id: "cot-9", titulo: "Cotação Whats", canalOrigem: "WHATSAPP", listaRevisada: false })]),
    );

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Whats")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    const link = screen.getByRole("link", { name: "Cotação Whats" }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/cotacoes/cot-9/entrada");
  });
});

describe("Dashboard — tabela 'Economia de Cotações', botão 'Conferência de Nota'", () => {
  function cenarioFinalizada() {
    const cotacao = makeCotacao({
      id: "cot-fin",
      titulo: "Cotação Finalizada",
      status: "FINALIZADA",
      finalizadaEm: "2026-08-01T10:00:00Z",
    });
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        precosPorFornecedor: [
          makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Distribuidora Alfa", precoUnitarioCalculado: 10 }),
          makeOferta({ fornecedorId: "forn-2", nomeFornecedor: "Beta", precoUnitarioCalculado: 15 }),
        ],
      }),
    ];
    listarCotacoesMock.mockResolvedValue(paginaDe([cotacao]));
    comparativoMock.mockResolvedValue(itens);
    return { cotacao, itens };
  }

  it("não é um link — é um botão que alterna aria-expanded ao clicar", async () => {
    cenarioFinalizada();

    await renderPage();
    const badge = await screen.findByRole("button", { name: "Conferência de Nota" });
    await aguardarEconomiaPotencialCarregada();
    expect(badge.tagName).toBe("BUTTON");
    expect(badge.getAttribute("href")).toBeNull();
    expect(badge.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(badge);
    await waitFor(() => expect(badge.getAttribute("aria-expanded")).toBe("true"));
    // "Distribuidora Alfa" também aparece no StatCard "Fornecedor Mais Competitivo" no
    // topo do Dashboard — o botão "Exportar PDF" é exclusivo do painel expandido.
    expect(screen.getByRole("button", { name: "Exportar PDF" })).toBeTruthy();

    fireEvent.click(badge);
    await waitFor(() => expect(badge.getAttribute("aria-expanded")).toBe("false"));
    expect(screen.queryByRole("button", { name: "Exportar PDF" })).toBeNull();
  });

  it("chama exportarConferenciaNota com a cotação e os itens ao clicar em Exportar PDF", async () => {
    const { cotacao, itens } = cenarioFinalizada();

    await renderPage();
    const badge = await screen.findByRole("button", { name: "Conferência de Nota" });
    await aguardarEconomiaPotencialCarregada();
    fireEvent.click(badge);

    const exportar = await screen.findByRole("button", { name: "Exportar PDF" });
    fireEvent.click(exportar);

    expect(exportarConferenciaNotaMock).toHaveBeenCalledWith(cotacao, itens);
  });
});
