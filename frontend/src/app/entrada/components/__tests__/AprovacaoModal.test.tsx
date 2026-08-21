// AprovacaoModal — orquestração do modal de aprovação de 2 abas (Fase C, 2026-08-20).
// As duas abas (ConferenciaListaBaseTab/ConferenciaFornecedoresTab) já têm cobertura
// própria; aqui mockamos as duas pra isolar: troca de aba, estado "done" do stepper,
// seed one-shot do rascunho, gate do "Lançar" e o fluxo de sucesso.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import AprovacaoModal from "../AprovacaoModal";
import { Cotacao, CotacaoFornecedorResponse, ItemListaResponse } from "@/lib/types";

const { pushMock, finalizarCotacaoMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  finalizarCotacaoMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, finalizarCotacao: finalizarCotacaoMock };
});

// Mocka as 2 abas pra isolar a orquestração do AprovacaoModal — cada uma expõe só o
// necessário pra inspecionar as props recebidas (rascunhos/fornecedorFocoId) e
// disparar callbacks passados (onListaAtualizada não é exercitado aqui).
vi.mock("../aprovacao/ConferenciaListaBaseTab", () => ({
  default: () => <div data-testid="aba-lista-base">Lista Base</div>,
}));
vi.mock("../aprovacao/ConferenciaFornecedoresTab", () => ({
  default: ({ rascunhos, fornecedorFocoId }: { rascunhos: Record<string, unknown>; fornecedorFocoId?: string | null }) => (
    <div data-testid="aba-fornecedores">
      Fornecedores
      <span data-testid="rascunhos-json">{JSON.stringify(rascunhos)}</span>
      <span data-testid="foco">{fornecedorFocoId ?? ""}</span>
    </div>
  ),
}));

function makeCotacao(overrides: Partial<Cotacao> = {}): Cotacao {
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
    criadoEm: "2026-08-01T00:00:00Z",
    atualizadoEm: null,
    ...overrides,
  };
}

function makeCf(overrides: Partial<CotacaoFornecedorResponse> = {}): CotacaoFornecedorResponse {
  return { id: "cf-1", fornecedorId: "forn-1", nomeFornecedor: "Fornecedor 1", ordem: 0, status: "PENDENTE", ...overrides };
}

function baseProps(overrides: Partial<React.ComponentProps<typeof AprovacaoModal>> = {}) {
  return {
    open: true,
    onClose: vi.fn(),
    cotacaoId: "cot-1",
    cotacao: makeCotacao(),
    itensLista: [] as ItemListaResponse[],
    produtos: [],
    onListaAtualizada: vi.fn(),
    cotacaoFornecedores: [] as CotacaoFornecedorResponse[],
    onCotacaoFornecedoresAtualizados: vi.fn().mockResolvedValue(undefined),
    onCotacaoAtualizada: vi.fn(),
    abaInicial: 1 as const,
    onTextoLimpo: vi.fn(),
    ...overrides,
  };
}

beforeEach(() => {
  pushMock.mockReset();
  finalizarCotacaoMock.mockReset();
});

describe("AprovacaoModal — abertura e troca de aba", () => {
  it("abre na aba pedida por abaInicial", () => {
    render(<AprovacaoModal {...baseProps({ abaInicial: 2 })} />);

    expect(screen.getByTestId("aba-fornecedores")).toBeTruthy();
  });

  it("clicar na aba 'Conferência das Cotações' troca pra ela", () => {
    render(<AprovacaoModal {...baseProps({ abaInicial: 1 })} />);

    expect(screen.getByTestId("aba-lista-base")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Conferência das Cotações" }));

    expect(screen.getByTestId("aba-fornecedores")).toBeTruthy();
  });

  it("chips do header mostram confirmados/total e itens da lista base", () => {
    render(
      <AprovacaoModal
        {...baseProps({
          itensLista: [{} as ItemListaResponse, {} as ItemListaResponse],
          cotacaoFornecedores: [makeCf({ status: "CONFIRMADO" }), makeCf({ id: "cf-2", status: "PENDENTE" })],
        })}
      />,
    );

    expect(screen.getByText("1 / 2 fornecedores")).toBeTruthy();
    expect(screen.getByText("2 itens na lista base")).toBeTruthy();
  });
});

describe("AprovacaoModal — 'Lista base conferida' marca o stepper como done", () => {
  it("clicar em 'Lista base conferida' avança pra aba 2 e o marcador 1 vira check verde mesmo ao voltar", () => {
    render(<AprovacaoModal {...baseProps({ abaInicial: 1 })} />);

    fireEvent.click(screen.getByRole("button", { name: "Lista base conferida" }));
    expect(screen.getByTestId("aba-fornecedores")).toBeTruthy();

    // Volta pra aba 1 — o check deve persistir (não é um estado ligado só a aprStep).
    fireEvent.click(screen.getByRole("button", { name: "Conferência da Lista Base" }));
    expect(screen.getByText("✓")).toBeTruthy();
  });

  it("reabrir o modal (open muda) reseta listaBaseConferida", () => {
    const { rerender } = render(<AprovacaoModal {...baseProps({ open: true, abaInicial: 1 })} />);
    fireEvent.click(screen.getByRole("button", { name: "Lista base conferida" }));
    fireEvent.click(screen.getByRole("button", { name: "Conferência da Lista Base" }));
    expect(screen.getByText("✓")).toBeTruthy();

    rerender(<AprovacaoModal {...baseProps({ open: false, abaInicial: 1 })} />);
    rerender(<AprovacaoModal {...baseProps({ open: true, abaInicial: 1 })} />);

    expect(screen.queryByText("✓")).toBeNull();
  });
});

describe("AprovacaoModal — seed one-shot do rascunho", () => {
  it("seedRascunho popula o mapa de rascunhos passado pra aba de Fornecedores, sem exigir nenhuma chamada de API adicional", () => {
    const preview = { contadores: { total: 1, ok: 1, atencao: 0, revisar: 0 }, itens: [] };
    render(
      <AprovacaoModal
        {...baseProps({
          abaInicial: 2,
          fornecedorFocoId: "cf-1",
          seedRascunho: { cfId: "cf-1", texto: "5un item - R$ 10,00", preview },
        })}
      />,
    );

    const rascunhos = JSON.parse(screen.getByTestId("rascunhos-json").textContent ?? "{}");
    expect(rascunhos["cf-1"].texto).toBe("5un item - R$ 10,00");
    expect(rascunhos["cf-1"].preview).toEqual(preview);
    expect(screen.getByTestId("foco").textContent).toBe("cf-1");
  });
});

describe("AprovacaoModal — gate e fluxo de 'Lançar'", () => {
  it("botão 'Lançar' fica desabilitado enquanto nem todos os fornecedores estão confirmados", () => {
    render(
      <AprovacaoModal
        {...baseProps({ abaInicial: 2, cotacaoFornecedores: [makeCf({ status: "CONFIRMADO" }), makeCf({ id: "cf-2", status: "PROCESSADO" })] })}
      />,
    );

    const botao = screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
  });

  it("sem nenhum fornecedor, 'Lançar' também fica desabilitado (total=0 não conta como 'todos confirmados')", () => {
    render(<AprovacaoModal {...baseProps({ abaInicial: 2, cotacaoFornecedores: [] })} />);

    const botao = screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
  });

  it("com todos confirmados, 'Lançar' finaliza a cotação e mostra a tela de sucesso", async () => {
    vi.useFakeTimers();
    const cotacaoFinalizada = makeCotacao({ status: "FINALIZADA" });
    finalizarCotacaoMock.mockResolvedValue(cotacaoFinalizada);
    const onCotacaoAtualizada = vi.fn();
    const onClose = vi.fn();

    render(
      <AprovacaoModal
        {...baseProps({
          abaInicial: 2,
          cotacaoFornecedores: [makeCf({ status: "CONFIRMADO" })],
          onCotacaoAtualizada,
          onClose,
        })}
      />,
    );

    const botao = screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ });
    await act(async () => {
      fireEvent.click(botao);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(finalizarCotacaoMock).toHaveBeenCalledWith("cot-1", "MENOR_PRECO");
    expect(onCotacaoAtualizada).toHaveBeenCalledWith(cotacaoFinalizada);
    expect(screen.getByText("Cotação aprovada e lançada!")).toBeTruthy();

    // Depois do delay, fecha o modal e navega pro Comparativo.
    await act(async () => {
      vi.advanceTimersByTime(2600);
    });
    expect(onClose).toHaveBeenCalled();
    expect(pushMock).toHaveBeenCalledWith("/cotacoes/cot-1/comparativo");

    vi.useRealTimers();
  });

  it("erro ao finalizar mostra mensagem e não fecha o modal", async () => {
    finalizarCotacaoMock.mockRejectedValue(new Error("falhou"));

    render(<AprovacaoModal {...baseProps({ abaInicial: 2, cotacaoFornecedores: [makeCf({ status: "CONFIRMADO" })] })} />);

    fireEvent.click(screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }));

    await waitFor(() => expect(screen.getByText("Não foi possível finalizar a cotação.")).toBeTruthy());
    expect(screen.queryByText("Cotação aprovada e lançada!")).toBeNull();
  });
});
