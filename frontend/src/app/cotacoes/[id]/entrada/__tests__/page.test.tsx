// Prompt 12: /ajuste-lista deixou de existir como rota própria — o antigo guard
// reverso (redirecionar pra /ajuste-lista) virou renderização condicional dentro da
// própria /entrada. Uma cotação WHATSAPP com listaRevisada=false carrega normalmente
// (grid + fornecedores/lista incluídos), mas o restante da tela (Fornecedores,
// Conferência) fica escondido até "Concluir ajuste e seguir para conferência".

import { Suspense } from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import EntradaPage from "@/app/cotacoes/[id]/entrada/page";
import { Cotacao } from "@/lib/types";

const { replaceMock, pushMock } = vi.hoisted(() => ({ replaceMock: vi.fn(), pushMock: vi.fn() }));
const {
  buscarCotacaoMock,
  buscarListaMock,
  buscarProdutosMock,
  listarFornecedoresMock,
  listarFornecedoresDaCotacaoMock,
} = vi.hoisted(() => ({
  buscarCotacaoMock: vi.fn(),
  buscarListaMock: vi.fn(),
  buscarProdutosMock: vi.fn(),
  listarFornecedoresMock: vi.fn(),
  listarFornecedoresDaCotacaoMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: pushMock }),
  usePathname: () => "/cotacoes/cot-1/entrada",
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    buscarCotacao: buscarCotacaoMock,
    buscarLista: buscarListaMock,
    buscarProdutos: buscarProdutosMock,
    listarFornecedores: listarFornecedoresMock,
    listarFornecedoresDaCotacao: listarFornecedoresDaCotacaoMock,
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

async function renderPage(id = "cot-1") {
  await act(async () => {
    render(
      <Suspense fallback={null}>
        <EntradaPage params={Promise.resolve({ id })} />
      </Suspense>,
    );
  });
}

beforeEach(() => {
  replaceMock.mockReset();
  pushMock.mockReset();
  buscarCotacaoMock.mockReset();
  buscarListaMock.mockReset();
  buscarProdutosMock.mockReset();
  listarFornecedoresMock.mockReset();
  listarFornecedoresDaCotacaoMock.mockReset();
  buscarProdutosMock.mockResolvedValue([]);
  localStorage.setItem("cotacao.accessToken", "fake-token");
});

describe("EntradaPage — modo de ajuste dobrado (Prompt 12)", () => {
  it("cotação WhatsApp com lista ainda não revisada carrega normalmente, sem redirecionar", async () => {
    buscarCotacaoMock.mockResolvedValue(makeCotacao({ canalOrigem: "WHATSAPP", listaRevisada: false }));
    listarFornecedoresMock.mockResolvedValue([]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([]);
    buscarListaMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() => expect(screen.getByText("Cotação teste")).toBeTruthy());
    expect(replaceMock).not.toHaveBeenCalled();
    expect(listarFornecedoresMock).toHaveBeenCalled();
    expect(listarFornecedoresDaCotacaoMock).toHaveBeenCalled();
    expect(buscarListaMock).toHaveBeenCalled();
  });

  it("nesse caso, mostra o botão de concluir ajuste e esconde a seção de Fornecedores", async () => {
    buscarCotacaoMock.mockResolvedValue(makeCotacao({ canalOrigem: "WHATSAPP", listaRevisada: false }));
    listarFornecedoresMock.mockResolvedValue([]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([]);
    buscarListaMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() =>
      expect(screen.getByText("Concluir ajuste e seguir para conferência")).toBeTruthy(),
    );
    expect(screen.queryByText("Fornecedores")).toBeNull();
  });

  it("cotação WhatsApp já revisada carrega normalmente com Fornecedores visível", async () => {
    buscarCotacaoMock.mockResolvedValue(makeCotacao({ canalOrigem: "WHATSAPP", listaRevisada: true }));
    listarFornecedoresMock.mockResolvedValue([]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([]);
    buscarListaMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() => expect(screen.getByText("Cotação teste")).toBeTruthy());
    expect(replaceMock).not.toHaveBeenCalled();
    expect(screen.queryByText("Concluir ajuste e seguir para conferência")).toBeNull();
  });

  it("cotação WEB carrega normalmente (sem modo de ajuste, mesmo com listaRevisada=false)", async () => {
    buscarCotacaoMock.mockResolvedValue(makeCotacao({ canalOrigem: "WEB", listaRevisada: false }));
    listarFornecedoresMock.mockResolvedValue([]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([]);
    buscarListaMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() => expect(screen.getByText("Cotação teste")).toBeTruthy());
    expect(replaceMock).not.toHaveBeenCalled();
    expect(screen.queryByText("Concluir ajuste e seguir para conferência")).toBeNull();
  });
});
