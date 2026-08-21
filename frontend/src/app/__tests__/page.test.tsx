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
import { Cotacao } from "@/lib/types";

const { pushMock, replaceMock } = vi.hoisted(() => ({ pushMock: vi.fn(), replaceMock: vi.fn() }));
const {
  listarCotacoesMock,
  comparativoLoteMock,
  economiaResumoMock,
  economiaCarrosselMock,
  listarFornecedoresMock,
  variacaoPrecoMock,
  concentracaoFornecedoresMock,
} = vi.hoisted(() => ({
  listarCotacoesMock: vi.fn(),
  comparativoLoteMock: vi.fn(),
  economiaResumoMock: vi.fn(),
  economiaCarrosselMock: vi.fn(),
  listarFornecedoresMock: vi.fn(),
  variacaoPrecoMock: vi.fn(),
  concentracaoFornecedoresMock: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  usePathname: () => "/",
}));

// economiaCarrossel/listarFornecedores/variacaoPreco/concentracaoFornecedores
// alimentam os componentes que substituíram a antiga grid "Economia de Cotações"
// (carrossel, painel de fornecedores, painéis de insight) — sem mock aqui essas
// chamadas cairiam na implementação real de fetch() dentro do jsdom.
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    listarCotacoes: listarCotacoesMock,
    comparativoLote: comparativoLoteMock,
    economiaResumo: economiaResumoMock,
    economiaCarrossel: economiaCarrosselMock,
    listarFornecedores: listarFornecedoresMock,
    variacaoPreco: variacaoPrecoMock,
    concentracaoFornecedores: concentracaoFornecedoresMock,
  };
});

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

function paginaDe(cotacoes: Cotacao[]) {
  return { content: cotacoes, totalElements: cotacoes.length, totalPages: 1, number: 0, size: 20 };
}

// listarCotacoes é chamado por 2 fetches independentes nesta página: o lembrete de
// "aguardando conferência" ({status: "EM_ANDAMENTO", ...}) e a tabela "Todas as
// cotações" propriamente ({q, page, size}) — os KPIs do topo vêm de economiaResumo(),
// não mais de listarCotacoes. Um mockResolvedValue único (sem checar args) faz os 2
// fetches devolverem o MESMO conteúdo — se a fixture tiver alguma cotação com status
// EM_ANDAMENTO, ela aparece duplicada (banner + tabela), quebrando asserts de texto
// único. Este helper isola a tabela: o lembrete sempre volta vazio.
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
  economiaCarrosselMock.mockReset();
  listarFornecedoresMock.mockReset();
  variacaoPrecoMock.mockReset();
  concentracaoFornecedoresMock.mockReset();
  comparativoLoteMock.mockResolvedValue({});
  economiaResumoMock.mockResolvedValue({
    cotacoesProcessadas: 0,
    economiaAcumulada: 0,
    mediaEconomiaPct: 0,
    fornecedorMaisCompetitivoNome: null,
    fornecedorMaisCompetitivoContagem: null,
  });
  // Componentes do carrossel/painéis de insight/Fornecedores (substituíram a antiga
  // grid "Economia de Cotações") — defaults vazios, sem interesse nos testes deste
  // arquivo (têm suíte própria em components/dashboard/__tests__).
  economiaCarrosselMock.mockResolvedValue({ items: [], nextCursor: null, hasMore: false });
  listarFornecedoresMock.mockResolvedValue([]);
  variacaoPrecoMock.mockResolvedValue({ produtos: [], totalProdutosComparados: 0 });
  concentracaoFornecedoresMock.mockResolvedValue({ fornecedores: [], limiteDependenciaPct: 40, algumEmRisco: false });
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
  // Rota /cotacoes/{id}/entrada não existe mais desde o refactor 2026-08-20 (achado
  // do usuário, 2026-08-21: clique aqui caía em 404) — a Entrada de Dados é uma
  // landing única (/entrada) que resolve a cotação atual sozinha.
  it("o título linka sempre para /entrada, mesmo em WHATSAPP com ajuste pendente", async () => {
    mockListaDaTabela([
      makeCotacao({ id: "cot-9", titulo: "Cotação Whats", canalOrigem: "WHATSAPP", listaRevisada: false }),
    ]);

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação Whats")).toBeTruthy());
    await aguardarEconomiaPotencialCarregada();

    const link = screen.getByRole("link", { name: "Cotação Whats" }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/entrada");
  });
});

describe("Dashboard — paginação server-side de 'Todas as cotações'", () => {
  // listarCotacoes é chamado 2 vezes com formas diferentes de argumento nesta
  // página: {status: "EM_ANDAMENTO", ...} (lembrete de conferência pendente) e
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
    // EM_ANDAMENTO (banner de lembrete) volta vazio — este teste é só sobre a
    // paginação de "Todas as cotações". Sem isso, o banner poderia disputar texto
    // com a tabela.
    listarCotacoesMock.mockImplementation((opcoes?: { status?: string; page?: number }) => {
      if (opcoes?.status === "EM_ANDAMENTO") return Promise.resolve(paginaDe([]));
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
    mockListaDaTabela([makeCotacao({ id: "cot-1", titulo: "Cotação Única" })]);

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
      if (opcoes?.status === "EM_ANDAMENTO") return Promise.resolve(paginaDe([]));
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
