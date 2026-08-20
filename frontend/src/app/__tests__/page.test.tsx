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
const { listarCotacoesMock, comparativoLoteMock, economiaResumoMock } = vi.hoisted(() => ({
  listarCotacoesMock: vi.fn(),
  comparativoLoteMock: vi.fn(),
  economiaResumoMock: vi.fn(),
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
    comparativoLote: comparativoLoteMock,
    economiaResumo: economiaResumoMock,
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

// listarCotacoes é chamado por 3 fetches independentes nesta página: o catálogo
// completo (sem args, alimenta KPIs/"Economia de Cotações"), o lembrete de
// "aguardando conferência" ({status: "EM_ANDAMENTO", ...}) e a tabela "Todas as
// cotações" propriamente ({q, page, size}). Um mockResolvedValue único (sem checar
// args) faz esses 3 fetches devolverem o MESMO conteúdo — se a fixture tiver alguma
// cotação com status EM_ANDAMENTO, ela aparece duplicada (banner + tabela), quebrando
// asserts de texto único. Este helper isola a tabela: o lembrete sempre volta vazio.
function mockListaDaTabela(cotacoes: Cotacao[]) {
  listarCotacoesMock.mockImplementation((opcoes?: { status?: string }) =>
    Promise.resolve(opcoes?.status === "EM_ANDAMENTO" ? paginaDe([]) : paginaDe(cotacoes)),
  );
}

function renderPage() {
  render(<DashboardPage />);
}

// Seção "Todas as cotações" — usado pra escopar consultas de botão/texto de
// paginação, já que agora "Economia de Cotações" também renderiza seus próprios
// controles de Pagination (ambíguo se as duas tiverem >1 total ao mesmo tempo).
function secaoTodasCotacoes() {
  return screen.getByText("Todas as cotações").closest("section")!;
}

function secaoEconomiaCotacoes() {
  return screen.getByText("Economia de Cotações").closest("section")!;
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
  comparativoLoteMock.mockReset();
  economiaResumoMock.mockReset();
  exportarConferenciaNotaMock.mockReset();
  comparativoLoteMock.mockResolvedValue({});
  economiaResumoMock.mockResolvedValue({
    cotacoesProcessadas: 0,
    economiaAcumulada: 0,
    mediaEconomiaPct: 0,
    fornecedorMaisCompetitivoNome: null,
    fornecedorMaisCompetitivoContagem: null,
  });
});

describe("Dashboard — tabela 'Todas as cotações', coluna Título", () => {
  it("expande/colapsa o resumo da linha ao clicar no chevron, alternando aria-expanded", async () => {
    mockListaDaTabela([makeCotacao({ id: "cot-1", titulo: "Cotação Web" })]);

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
    mockListaDaTabela([
      makeCotacao({ id: "cot-1", titulo: "Cotação Web", canalOrigem: "WEB", listaRevisada: false }),
      makeCotacao({ id: "cot-2", titulo: "Cotação Whats Pendente", canalOrigem: "WHATSAPP", listaRevisada: false }),
      makeCotacao({ id: "cot-3", titulo: "Cotação Whats Revisada", canalOrigem: "WHATSAPP", listaRevisada: true }),
    ]);

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
    mockListaDaTabela([
      makeCotacao({ id: "cot-9", titulo: "Cotação Whats", canalOrigem: "WHATSAPP", listaRevisada: false }),
    ]);

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
    mockListaDaTabela([cotacao]);
    comparativoLoteMock.mockResolvedValue({ [cotacao.id]: itens });
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

describe("Dashboard — paginação server-side de 'Todas as cotações'", () => {
  // listarCotacoes é chamado 3 vezes com formas diferentes de argumento nesta página:
  // sem argumento nenhum (KPIs/Economia, 1ª página do catálogo inteiro), com
  // {status: "EM_ANDAMENTO", ...} (lembrete de conferência pendente) e com
  // {q, page, size} (a própria tabela "Todas as cotações", paginada de verdade).
  function paginaCom(cotacoesDaPagina: Cotacao[], opts: { number: number; totalElements: number }) {
    return { content: cotacoesDaPagina, totalElements: opts.totalElements, totalPages: Math.ceil(opts.totalElements / 20), number: opts.number, size: 20 };
  }

  it("mostra os controles de paginação quando há mais cotações que o tamanho de página, e busca a página certa ao clicar em 'Próxima'/'Anterior'", async () => {
    const pagina0 = paginaCom([makeCotacao({ id: "cot-pg0", titulo: "Cotação Página 0" })], {
      number: 0,
      totalElements: 25,
    });
    const pagina1 = paginaCom([makeCotacao({ id: "cot-pg1", titulo: "Cotação Página 1" })], {
      number: 1,
      totalElements: 25,
    });
    // FINALIZADA (grid "Economia de Cotações") volta vazio — este teste é só sobre a
    // paginação de "Todas as cotações", que não filtra por status. Sem isso, os dois
    // grids renderizariam "Próxima"/"Anterior" ao mesmo tempo (ambíguo pro getByRole).
    listarCotacoesMock.mockImplementation((opcoes?: { status?: string; page?: number }) => {
      if (opcoes?.status === "EM_ANDAMENTO" || opcoes?.status === "FINALIZADA") return Promise.resolve(paginaDe([]));
      return Promise.resolve(opcoes?.page === 1 ? pagina1 : pagina0);
    });

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Página 0")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    expect(screen.getByText("Página 1 de 2")).toBeTruthy();
    expect(listarCotacoesMock).toHaveBeenCalledWith({ q: undefined, page: 0, size: 20 });

    fireEvent.click(screen.getByRole("button", { name: "Próxima" }));

    await waitFor(() => expect(screen.getByText("Cotação Página 1")).toBeTruthy());
    expect(screen.queryByText("Cotação Página 0")).toBeNull();
    expect(listarCotacoesMock).toHaveBeenCalledWith({ q: undefined, page: 1, size: 20 });

    fireEvent.click(screen.getByRole("button", { name: "Anterior" }));

    await waitFor(() => expect(screen.getByText("Cotação Página 0")).toBeTruthy());
    expect(screen.queryByText("Cotação Página 1")).toBeNull();
  });

  it("com o total cabendo numa página só, os botões Anterior/Próxima aparecem desabilitados (Pagination só se esconde de vez com total 0)", async () => {
    // mockListaDaTabela também popula a grid "Economia de Cotações" (mesma cotação
    // tem status FINALIZADA) — os dois grids mostram "Página 1 de 1" ao mesmo tempo,
    // então as consultas precisam ficar escopadas à seção "Todas as cotações".
    mockListaDaTabela([makeCotacao({ id: "cot-1", titulo: "Cotação Única", status: "FINALIZADA", finalizadaEm: "2026-08-01T10:00:00Z" })]);

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Única")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    const secao = within(secaoTodasCotacoes());
    expect(secao.getByText("Página 1 de 1")).toBeTruthy();
    expect((secao.getByRole("button", { name: "Anterior" }) as HTMLButtonElement).disabled).toBe(true);
    expect((secao.getByRole("button", { name: "Próxima" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("lista de cotações vazia (nenhum resultado pra busca) não mostra os controles de paginação", async () => {
    mockListaDaTabela([]);

    await renderPage();
    await aguardarEconomiaPotencialCarregada();
    await waitFor(() => expect(screen.getByText("Nenhuma cotação ainda. Clique em qualquer aba de navegação para criar a primeira.")).toBeTruthy());

    expect(screen.queryByRole("button", { name: "Próxima" })).toBeNull();
  });

  it("digitar um termo de busca volta a tabela pra página 0, mesmo estando numa página seguinte", async () => {
    const pagina0 = paginaCom([makeCotacao({ id: "cot-pg0", titulo: "Cotação Página 0" })], {
      number: 0,
      totalElements: 25,
    });
    const pagina1 = paginaCom([makeCotacao({ id: "cot-pg1", titulo: "Cotação Página 1" })], {
      number: 1,
      totalElements: 25,
    });
    const buscaFiltrada = paginaDe([makeCotacao({ id: "cot-busca", titulo: "Achou Por Busca" })]);
    listarCotacoesMock.mockImplementation((opcoes?: { status?: string; page?: number; q?: string }) => {
      if (opcoes?.status === "EM_ANDAMENTO" || opcoes?.status === "FINALIZADA") return Promise.resolve(paginaDe([]));
      if (opcoes?.q) return Promise.resolve(buscaFiltrada);
      return Promise.resolve(opcoes?.page === 1 ? pagina1 : pagina0);
    });

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Página 0")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();
    fireEvent.click(screen.getByRole("button", { name: "Próxima" }));
    await waitFor(() => expect(screen.getByText("Cotação Página 1")).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText("Buscar por título ou canal"), { target: { value: "achou" } });

    await waitFor(() => expect(screen.getByText("Achou Por Busca")).toBeTruthy());
    expect(listarCotacoesMock).toHaveBeenCalledWith({ q: "achou", page: 0, size: 20 });
  });
});

describe("Dashboard — KPIs de 'Economia de Cotações' (GET /cotacoes/economia-resumo)", () => {
  it("mostra os 4 KPIs vindos prontos do backend, não computados a partir das cotações carregadas no frontend", async () => {
    // De propósito: nem listarCotacoes nem comparativo têm nenhuma cotação/item que
    // sozinho explicaria esses números — se os KPIs ainda fossem computados
    // client-side (como antes), estes valores não apareceriam.
    mockListaDaTabela([]);
    economiaResumoMock.mockResolvedValue({
      cotacoesProcessadas: 42,
      economiaAcumulada: 1234.5,
      mediaEconomiaPct: 17.25,
      fornecedorMaisCompetitivoNome: "Distribuidora Vencedora",
      fornecedorMaisCompetitivoContagem: 9,
    });

    await renderPage();

    await waitFor(() => expect(screen.getByText("42")).toBeTruthy());
    expect(screen.getByText("Distribuidora Vencedora")).toBeTruthy();
    expect(screen.getByText("9 itens vencidos")).toBeTruthy();
  });

  it("sem nenhum fornecedor vencedor (fornecedorMaisCompetitivoNome null), mostra o traço no lugar do nome", async () => {
    mockListaDaTabela([]);
    economiaResumoMock.mockResolvedValue({
      cotacoesProcessadas: 0,
      economiaAcumulada: 0,
      mediaEconomiaPct: 0,
      fornecedorMaisCompetitivoNome: null,
      fornecedorMaisCompetitivoContagem: null,
    });

    await renderPage();
    await aguardarEconomiaPotencialCarregada();

    expect(screen.getByText("—")).toBeTruthy();
    expect(screen.getByText("sem dados suficientes")).toBeTruthy();
  });
});

describe("Dashboard — paginação server-side de 'Economia de Cotações'", () => {
  function paginaEconomiaCom(cotacoesDaPagina: Cotacao[], opts: { number: number; totalElements: number }) {
    return { content: cotacoesDaPagina, totalElements: opts.totalElements, totalPages: Math.ceil(opts.totalElements / 20), number: opts.number, size: 20 };
  }

  it("pagina de forma independente de 'Todas as cotações' — clicar em 'Próxima' na grid de economia não afeta a outra tabela", async () => {
    // A grid "Economia de Cotações" não exibe o título da cotação (só data, itens,
    // fornecedores, total e economia) — usa a data formatada (coluna "Data") como
    // marcador único de cada página, já que finalizadaEm difere entre elas.
    const pagina0Economia = paginaEconomiaCom(
      [makeCotacao({ id: "eco-pg0", titulo: "Economia Página 0", status: "FINALIZADA", finalizadaEm: "2026-08-01T10:00:00Z" })],
      { number: 0, totalElements: 25 },
    );
    const pagina1Economia = paginaEconomiaCom(
      [makeCotacao({ id: "eco-pg1", titulo: "Economia Página 1", status: "FINALIZADA", finalizadaEm: "2026-08-15T10:00:00Z" })],
      { number: 1, totalElements: 25 },
    );
    const todasCotacoes = paginaDe([makeCotacao({ id: "todas-1", titulo: "Cotação Qualquer" })]);

    listarCotacoesMock.mockImplementation((opcoes?: { status?: string; page?: number }) => {
      if (opcoes?.status === "EM_ANDAMENTO") return Promise.resolve(paginaDe([]));
      if (opcoes?.status === "FINALIZADA") return Promise.resolve(opcoes.page === 1 ? pagina1Economia : pagina0Economia);
      return Promise.resolve(todasCotacoes);
    });

    await renderPage();
    await waitFor(() => expect(within(secaoEconomiaCotacoes()).getByText("01/08/2026")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    const economia = within(secaoEconomiaCotacoes());
    fireEvent.click(economia.getByRole("button", { name: "Próxima" }));

    await waitFor(() => expect(within(secaoEconomiaCotacoes()).getByText("15/08/2026")).toBeTruthy());
    expect(within(secaoEconomiaCotacoes()).queryByText("01/08/2026")).toBeNull();
    // "Todas as cotações" não deve ter sido afetada pela paginação da outra grid.
    expect(within(secaoTodasCotacoes()).getByText("Cotação Qualquer")).toBeTruthy();
    expect(listarCotacoesMock).toHaveBeenCalledWith({ status: "FINALIZADA", page: 1, size: 20, sort: "finalizadaEm,desc" });
  });
});
