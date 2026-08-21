// Landing tenant-wide (/entrada) — refactor 2026-08-20 (última leva): passou a
// hospedar o AprovacaoModal diretamente (a pedido do usuário: "a tela de fundo do
// modal deve ser a tela Entrada de Dados Cotação atual"), removendo a antiga rota
// separada /cotacoes/{id}/entrada. "Revisar e aprovar"/"Detalhes" buscam os dados
// completos da cotação (itens/produtos/fornecedores) sob demanda e abrem o modal
// direto sobre esta tela. AprovacaoModal é mockado aqui pra isolar a lógica própria
// da landing: fetch lazy ao abrir, refetch da cotação atual ao fechar.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import EntradaLandingPage from "@/app/entrada/page";
import { CotacaoAtualResponse, Cotacao } from "@/lib/types";

const {
  buscarCotacaoAtualMock,
  buscarCotacaoMock,
  buscarListaMock,
  buscarProdutosMock,
  listarFornecedoresMock,
  listarFornecedoresDaCotacaoMock,
  criarCotacaoMock,
} = vi.hoisted(() => ({
  buscarCotacaoAtualMock: vi.fn(),
  buscarCotacaoMock: vi.fn(),
  buscarListaMock: vi.fn(),
  buscarProdutosMock: vi.fn(),
  listarFornecedoresMock: vi.fn(),
  listarFornecedoresDaCotacaoMock: vi.fn(),
  criarCotacaoMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => "/entrada",
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    buscarCotacaoAtual: buscarCotacaoAtualMock,
    buscarCotacao: buscarCotacaoMock,
    buscarLista: buscarListaMock,
    buscarProdutos: buscarProdutosMock,
    listarFornecedores: listarFornecedoresMock,
    listarFornecedoresDaCotacao: listarFornecedoresDaCotacaoMock,
    criarCotacao: criarCotacaoMock,
  };
});

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return {
    ...actual,
    useAuth: () => ({ ready: true, authenticated: true, papel: "OPERADOR_CLIENTE", tenantId: "t-1" }),
  };
});

vi.mock("@/app/entrada/components/AprovacaoModal", () => ({
  default: ({ open, onClose, cotacao }: { open: boolean; onClose: () => void; cotacao: Cotacao }) =>
    open ? (
      <div role="dialog" aria-label="AprovacaoModal (mock)">
        {cotacao.titulo}
        <button type="button" onClick={onClose}>
          Fechar modal
        </button>
      </div>
    ) : null,
}));

function makeCotacaoAtual(overrides: Partial<CotacaoAtualResponse> = {}): CotacaoAtualResponse {
  return {
    id: "cot-1",
    titulo: "Cotação Agosto",
    canalOrigem: "WEB",
    ultimaAtividadeEm: "2026-08-20T10:00:00Z",
    criadoEm: "2026-08-20T09:00:00Z",
    itensListaBase: 5,
    itensCotados: 2,
    fornecedores: [],
    ...overrides,
  };
}

function makeCotacaoDetalhe(overrides: Partial<Cotacao> = {}): Cotacao {
  return {
    id: "cot-1",
    criadoPor: null,
    titulo: "Cotação Agosto",
    status: "EM_ANDAMENTO",
    canalOrigem: "WEB",
    listaRevisada: true,
    ultimaAtividadeEm: null,
    cenarioSelecionado: null,
    finalizadaEm: null,
    criadoEm: "2026-08-20T09:00:00Z",
    atualizadoEm: null,
    ...overrides,
  };
}

async function renderPage() {
  await act(async () => {
    render(<EntradaLandingPage />);
  });
}

beforeEach(() => {
  buscarCotacaoAtualMock.mockReset();
  buscarCotacaoMock.mockReset();
  buscarListaMock.mockReset();
  buscarProdutosMock.mockReset();
  listarFornecedoresMock.mockReset();
  listarFornecedoresDaCotacaoMock.mockReset();
  criarCotacaoMock.mockReset();
  buscarListaMock.mockResolvedValue([]);
  buscarProdutosMock.mockResolvedValue([]);
  listarFornecedoresMock.mockResolvedValue([]);
  listarFornecedoresDaCotacaoMock.mockResolvedValue([]);
});

describe("EntradaLandingPage — abrir o AprovacaoModal sob demanda", () => {
  it("clicar em 'Revisar e aprovar' busca os dados completos e abre o modal sobre a landing", async () => {
    buscarCotacaoAtualMock.mockResolvedValue(makeCotacaoAtual());
    buscarCotacaoMock.mockResolvedValue(makeCotacaoDetalhe());

    await renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "Revisar e aprovar" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Revisar e aprovar" }));

    await waitFor(() => expect(buscarCotacaoMock).toHaveBeenCalledWith("cot-1"));
    await waitFor(() => expect(screen.getByRole("dialog", { name: "AprovacaoModal (mock)" })).toBeTruthy());
    // A landing (card/carrossel/Hall) continua visível atrás do modal — não navegou.
    expect(screen.getByText("Cotação atual")).toBeTruthy();
  });

  it("'Detalhes' abre o mesmo modal", async () => {
    buscarCotacaoAtualMock.mockResolvedValue(makeCotacaoAtual());
    buscarCotacaoMock.mockResolvedValue(makeCotacaoDetalhe());

    await renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "Detalhes" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Detalhes" }));

    await waitFor(() => expect(screen.getByRole("dialog", { name: "AprovacaoModal (mock)" })).toBeTruthy());
  });

  it("fechar o modal refaz a busca da cotação atual (pra refletir mudanças feitas dentro dele)", async () => {
    buscarCotacaoAtualMock.mockResolvedValue(makeCotacaoAtual());
    buscarCotacaoMock.mockResolvedValue(makeCotacaoDetalhe());

    await renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "Revisar e aprovar" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Revisar e aprovar" }));
    await waitFor(() => expect(screen.getByRole("dialog", { name: "AprovacaoModal (mock)" })).toBeTruthy());

    expect(buscarCotacaoAtualMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByText("Fechar modal"));

    await waitFor(() => expect(buscarCotacaoAtualMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("dialog", { name: "AprovacaoModal (mock)" })).toBeNull();
  });

  it("sem cotação atual, os botões do estado vazio não abrem o modal (usam 'Criar cotação pela web')", async () => {
    buscarCotacaoAtualMock.mockResolvedValue(undefined);

    await renderPage();

    await waitFor(() => expect(screen.getByText("Sem cotações no momento")).toBeTruthy());
    expect(screen.getByRole("button", { name: "Criar cotação pela web" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Revisar e aprovar" })).toBeNull();
  });

  it("'Criar cotação pela web' cria a cotação e abre o AprovacaoModal direto pra ela (sem navegar)", async () => {
    buscarCotacaoAtualMock.mockResolvedValue(undefined);
    criarCotacaoMock.mockResolvedValue(makeCotacaoDetalhe({ id: "cot-nova", titulo: "Nova cotação" }));
    buscarCotacaoMock.mockResolvedValue(makeCotacaoDetalhe({ id: "cot-nova", titulo: "Nova cotação" }));

    await renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "Criar cotação pela web" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "Criar cotação pela web" }));
    fireEvent.change(screen.getByPlaceholderText(/Título da nova cotação/), { target: { value: "Nova cotação" } });
    fireEvent.click(screen.getByRole("button", { name: "Nova cotação" }));

    await waitFor(() => expect(criarCotacaoMock).toHaveBeenCalledWith("Nova cotação"));
    await waitFor(() => expect(buscarCotacaoMock).toHaveBeenCalledWith("cot-nova"));
    await waitFor(() => expect(screen.getByRole("dialog", { name: "AprovacaoModal (mock)" })).toBeTruthy());
  });

  it("erro ao buscar os dados completos mostra mensagem, sem abrir o modal", async () => {
    buscarCotacaoAtualMock.mockResolvedValue(makeCotacaoAtual());
    buscarCotacaoMock.mockRejectedValue(new Error("falhou"));

    await renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "Revisar e aprovar" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Revisar e aprovar" }));

    await waitFor(() => expect(screen.getByText("Não foi possível carregar a cotação.")).toBeTruthy());
    expect(screen.queryByRole("dialog", { name: "AprovacaoModal (mock)" })).toBeNull();
  });
});
