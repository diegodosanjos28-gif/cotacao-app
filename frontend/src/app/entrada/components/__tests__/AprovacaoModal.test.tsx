// AprovacaoModal — orquestração do modal de aprovação de 2 abas. Web e WhatsApp usam
// o mesmo modal até na etapa de produto (aba 1 = GridProdutosSection completo,
// 2026-08-20) — as abas já têm cobertura própria (GridProdutosSection.test.tsx,
// ConferenciaFornecedoresTab.test.tsx); aqui mockamos as duas pra isolar: troca de
// aba, estado "done" do stepper, o gate "precisaAjuste" (WHATSAPP não revisado), o
// gate do "Lançar" e o fluxo de sucesso.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import AprovacaoModal from "../AprovacaoModal";
import { Cotacao, CotacaoFornecedorResponse, ItemListaResponse } from "@/lib/types";

const { pushMock, finalizarCotacaoMock, concluirAjusteListaMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  finalizarCotacaoMock: vi.fn(),
  concluirAjusteListaMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, finalizarCotacao: finalizarCotacaoMock, concluirAjusteLista: concluirAjusteListaMock };
});

// Mocka as 2 abas pra isolar a orquestração do AprovacaoModal.
vi.mock("@/app/cotacoes/[id]/entrada/components/GridProdutosSection", () => ({
  default: () => <div data-testid="aba-lista-base">Lista Base</div>,
}));
vi.mock("../aprovacao/ConferenciaFornecedoresTab", () => ({
  default: ({ todosFornecedores }: { todosFornecedores: unknown[] }) => (
    <div data-testid="aba-fornecedores">
      Fornecedores
      <span data-testid="qtd-catalogo">{todosFornecedores.length}</span>
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
    onProdutosAtualizados: vi.fn(),
    cotacaoFornecedores: [] as CotacaoFornecedorResponse[],
    todosFornecedores: [],
    onFornecedorAtualizado: vi.fn(),
    onFornecedorInativado: vi.fn(),
    onCotacaoFornecedoresAtualizados: vi.fn().mockResolvedValue(undefined),
    onCotacaoAtualizada: vi.fn(),
    ...overrides,
  };
}

function irParaAbaFornecedores() {
  fireEvent.click(screen.getByRole("button", { name: "Conferência das Cotações" }));
}

beforeEach(() => {
  pushMock.mockReset();
  finalizarCotacaoMock.mockReset();
  concluirAjusteListaMock.mockReset();
});

describe("AprovacaoModal — abertura e troca de aba", () => {
  it("abre sempre na aba 1 (Lista Base)", () => {
    render(<AprovacaoModal {...baseProps()} />);

    expect(screen.getByTestId("aba-lista-base")).toBeTruthy();
  });

  it("clicar na aba 'Conferência das Cotações' troca pra ela, repassando o catálogo de fornecedores", () => {
    render(
      <AprovacaoModal
        {...baseProps({ todosFornecedores: [{ id: "f-1" }, { id: "f-2" }] as unknown as React.ComponentProps<typeof AprovacaoModal>["todosFornecedores"] })}
      />,
    );

    irParaAbaFornecedores();

    expect(screen.getByTestId("aba-fornecedores")).toBeTruthy();
    expect(screen.getByTestId("qtd-catalogo").textContent).toBe("2");
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
    render(<AprovacaoModal {...baseProps()} />);

    fireEvent.click(screen.getByRole("button", { name: "Lista base conferida" }));
    expect(screen.getByTestId("aba-fornecedores")).toBeTruthy();

    // Volta pra aba 1 — o check deve persistir (não é um estado ligado só a aprStep).
    fireEvent.click(screen.getByRole("button", { name: "Conferência da Lista Base" }));
    expect(screen.getByText("✓")).toBeTruthy();
  });

  it("reabrir o modal (open muda) reseta listaBaseConferida", () => {
    const { rerender } = render(<AprovacaoModal {...baseProps({ open: true })} />);
    fireEvent.click(screen.getByRole("button", { name: "Lista base conferida" }));
    fireEvent.click(screen.getByRole("button", { name: "Conferência da Lista Base" }));
    expect(screen.getByText("✓")).toBeTruthy();

    rerender(<AprovacaoModal {...baseProps({ open: false })} />);
    rerender(<AprovacaoModal {...baseProps({ open: true })} />);

    expect(screen.queryByText("✓")).toBeNull();
  });
});

describe("AprovacaoModal — precisaAjuste (WHATSAPP com lista ainda não revisada)", () => {
  function cotacaoPrecisaAjuste(overrides: Partial<Cotacao> = {}) {
    return makeCotacao({ canalOrigem: "WHATSAPP", listaRevisada: false, ...overrides });
  }

  it("aba 2 fica desabilitada e mostra o banner de ajuste na aba 1", () => {
    render(<AprovacaoModal {...baseProps({ cotacao: cotacaoPrecisaAjuste() })} />);

    expect((screen.getByRole("button", { name: "Conferência das Cotações" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Revise os itens recebidos por WhatsApp/)).toBeTruthy();
  });

  it("rodapé mostra 'Concluir ajuste e seguir para aprovação' em vez de 'Lista base conferida'", () => {
    render(<AprovacaoModal {...baseProps({ cotacao: cotacaoPrecisaAjuste() })} />);

    expect(screen.getByRole("button", { name: "Concluir ajuste e seguir para aprovação" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Lista base conferida" })).toBeNull();
  });

  it("clicar em 'Concluir ajuste...' chama concluirAjusteLista, atualiza a cotação e avança pra aba 2", async () => {
    const cotacaoAtualizada = makeCotacao({ canalOrigem: "WHATSAPP", listaRevisada: true });
    concluirAjusteListaMock.mockResolvedValue(cotacaoAtualizada);
    const onCotacaoAtualizada = vi.fn();

    render(
      <AprovacaoModal
        {...baseProps({
          cotacao: cotacaoPrecisaAjuste(),
          itensLista: [{} as ItemListaResponse],
          onCotacaoAtualizada,
        })}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Concluir ajuste e seguir para aprovação" }));

    await waitFor(() => expect(concluirAjusteListaMock).toHaveBeenCalledWith("cot-1"));
    expect(onCotacaoAtualizada).toHaveBeenCalledWith(cotacaoAtualizada);
    await waitFor(() => expect(screen.getByTestId("aba-fornecedores")).toBeTruthy());
  });

  it("botão de concluir ajuste fica desabilitado sem nenhum item na lista", () => {
    render(<AprovacaoModal {...baseProps({ cotacao: cotacaoPrecisaAjuste(), itensLista: [] })} />);

    expect((screen.getByRole("button", { name: "Concluir ajuste e seguir para aprovação" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("cotação já revisada (listaRevisada=true) não mostra o banner nem desabilita a aba 2", () => {
    render(<AprovacaoModal {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WHATSAPP", listaRevisada: true }) })} />);

    expect(screen.queryByText(/Revise os itens recebidos por WhatsApp/)).toBeNull();
    expect((screen.getByRole("button", { name: "Conferência das Cotações" }) as HTMLButtonElement).disabled).toBe(false);
  });
});

describe("AprovacaoModal — gate e fluxo de 'Lançar'", () => {
  it("botão 'Lançar' fica desabilitado enquanto nem todos os fornecedores estão confirmados", () => {
    render(
      <AprovacaoModal {...baseProps({ cotacaoFornecedores: [makeCf({ status: "CONFIRMADO" }), makeCf({ id: "cf-2", status: "PROCESSADO" })] })} />,
    );
    irParaAbaFornecedores();

    const botao = screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
  });

  it("sem nenhum fornecedor, 'Lançar' também fica desabilitado (total=0 não conta como 'todos confirmados')", () => {
    render(<AprovacaoModal {...baseProps({ cotacaoFornecedores: [] })} />);
    irParaAbaFornecedores();

    const botao = screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }) as HTMLButtonElement;
    expect(botao.disabled).toBe(true);
  });

  it("com todos confirmados, 'Lançar' mostra a tela de sucesso e navega pro Comparativo, SEM finalizar a cotação", async () => {
    // Decisão do usuário: "a finalização deve estar ainda apenas no mapa de compra" —
    // "Lançar" não chama finalizarCotacao nem qualquer outro endpoint; só navega.
    // Comparativo/Mapa de Compra já leem os cenários calculados ao vivo sobre uma
    // cotação EM_ANDAMENTO, sem exigir mudança de status.
    vi.useFakeTimers();
    const onCotacaoAtualizada = vi.fn();
    const onClose = vi.fn();

    render(
      <AprovacaoModal
        {...baseProps({
          cotacaoFornecedores: [makeCf({ status: "CONFIRMADO" })],
          onCotacaoAtualizada,
          onClose,
        })}
      />,
    );
    irParaAbaFornecedores();

    const botao = screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ });
    fireEvent.click(botao);

    expect(finalizarCotacaoMock).not.toHaveBeenCalled();
    expect(onCotacaoAtualizada).not.toHaveBeenCalled();
    expect(screen.getByText("Cotação aprovada e lançada!")).toBeTruthy();

    // Depois do delay, fecha o modal e navega pro Comparativo.
    await act(async () => {
      vi.advanceTimersByTime(2600);
    });
    expect(onClose).toHaveBeenCalled();
    expect(pushMock).toHaveBeenCalledWith("/cotacoes/cot-1/comparativo");

    vi.useRealTimers();
  });
});
