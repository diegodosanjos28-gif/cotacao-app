// Teste de regressão portado de ConferenciaPanel.test.tsx (removido na Fase C) e
// atualizado na leva seguinte (2026-08-20) que absorveu FornecedoresCotacoesSection
// pra dentro desta aba: adicionar fornecedor (só WEB), captura de resposta (via
// FornecedorCapturaCard, também só WEB) e o bug de "loading eterno" achado em
// 2026-08-17 (ver comentário no componente, useEffect de auto-fetch).
//
// Causa raiz do bug de loading: o efeito de auto-fetch (dispara onConferirResposta
// quando o fornecedor ativo está PROCESSADO e ainda não tem preview em memória) tinha
// `preview` nas próprias deps. `onConferirResposta` (fornecido pelo AprovacaoModal)
// grava o preview resultante no rascunho *antes* de sua própria promise assentar — o
// que muda `rascunhos[atual.id].preview` de null pro objeto e re-dispara o próprio
// efeito no meio do caminho, rodando o cleanup ANTES da promise original terminar. O
// fix usa um ref (`atualIdRef`) atualizado só quando o fornecedor ativo realmente
// muda — o `.finally()` da fetch original só ignora o resultado se o fornecedor ativo
// *de fato* mudou nesse meio-tempo.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, fireEvent, waitFor } from "@testing-library/react";
import ConferenciaFornecedoresTab from "../ConferenciaFornecedoresTab";
import { Cotacao, CotacaoFornecedorResponse, PreviewRespostaResponse } from "@/lib/types";
import { RascunhoFornecedor } from "../rascunhoFornecedor";

const { adicionarFornecedorNaCotacaoMock } = vi.hoisted(() => ({ adicionarFornecedorNaCotacaoMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, adicionarFornecedorNaCotacao: adicionarFornecedorNaCotacaoMock };
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
    criadoEm: "2026-08-01T00:00:00Z",
    atualizadoEm: null,
    ...overrides,
  };
}

function makeCotacaoFornecedor(overrides: Partial<CotacaoFornecedorResponse> = {}): CotacaoFornecedorResponse {
  return {
    id: "cf-1",
    fornecedorId: "forn-1",
    nomeFornecedor: "Fornecedor 1",
    ordem: 0,
    status: "PROCESSADO",
    ...overrides,
  };
}

function makePreview(): PreviewRespostaResponse {
  return { contadores: { total: 0, ok: 0, atencao: 0, revisar: 0 }, itens: [] };
}

function makeRascunhos(entries: Record<string, Partial<RascunhoFornecedor>> = {}): Record<string, RascunhoFornecedor> {
  const out: Record<string, RascunhoFornecedor> = {};
  for (const [id, r] of Object.entries(entries)) {
    out[id] = { texto: "", preview: null, resolucoes: {}, spinOffs: {}, excluidos: {}, ...r };
  }
  return out;
}

function baseProps(overrides: Partial<React.ComponentProps<typeof ConferenciaFornecedoresTab>> = {}) {
  return {
    cotacaoId: "cot-1",
    cotacao: makeCotacao(),
    cotacaoFornecedores: [] as CotacaoFornecedorResponse[],
    todosFornecedores: [],
    onFornecedorAtualizado: vi.fn(),
    onFornecedorInativado: vi.fn(),
    onConferirResposta: vi.fn(),
    onCancelarConferencia: vi.fn().mockResolvedValue(undefined),
    onCotacaoFornecedoresAtualizados: vi.fn().mockResolvedValue(undefined),
    onProcessarResposta: vi.fn().mockResolvedValue(undefined),
    rascunhos: {} as Record<string, RascunhoFornecedor>,
    onTextoChange: vi.fn(),
    onEstadoResolucaoChange: vi.fn(),
    ...overrides,
  };
}

beforeEach(() => {
  adicionarFornecedorNaCotacaoMock.mockReset();
});

describe("ConferenciaFornecedoresTab — banner de contexto e estados sem fornecedor", () => {
  it("mostra o banner azul de contexto", () => {
    render(<ConferenciaFornecedoresTab {...baseProps({ cotacaoFornecedores: [makeCotacaoFornecedor({ status: "PENDENTE" })] })} />);

    expect(screen.getByText("Conferência das Cotações dos Fornecedores")).toBeTruthy();
  });

  it("cotação WEB sem nenhum fornecedor mostra a sidebar de cadastro direto", () => {
    render(<ConferenciaFornecedoresTab {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WEB" }), cotacaoFornecedores: [] })} />);

    expect(screen.getByPlaceholderText("Buscar fornecedor por nome...")).toBeTruthy();
  });

  it("cotação WHATSAPP sem nenhum fornecedor mostra mensagem de espera, sem opção de adicionar", () => {
    render(<ConferenciaFornecedoresTab {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WHATSAPP" }), cotacaoFornecedores: [] })} />);

    expect(screen.getByText(/Nenhuma resposta de fornecedor via WhatsApp recebida ainda/)).toBeTruthy();
    expect(screen.queryByPlaceholderText("Buscar fornecedor por nome...")).toBeNull();
  });
});

describe("ConferenciaFornecedoresTab — 'Adicionar Fornecedor' só em cotações WEB", () => {
  it("cotação WEB com fornecedores existentes mostra o botão '+ Adicionar Fornecedor'", () => {
    render(
      <ConferenciaFornecedoresTab
        {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WEB" }), cotacaoFornecedores: [makeCotacaoFornecedor({ status: "CONFIRMADO" })] })}
      />,
    );

    expect(screen.getByRole("button", { name: "+ Adicionar Fornecedor" })).toBeTruthy();
  });

  it("cotação WHATSAPP com fornecedores existentes NÃO mostra o botão de adicionar", () => {
    render(
      <ConferenciaFornecedoresTab
        {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WHATSAPP" }), cotacaoFornecedores: [makeCotacaoFornecedor({ status: "CONFIRMADO" })] })}
      />,
    );

    expect(screen.queryByRole("button", { name: "+ Adicionar Fornecedor" })).toBeNull();
  });

  it("selecionar um fornecedor no painel e adicionar chama adicionarFornecedorNaCotacao e fecha o painel", async () => {
    adicionarFornecedorNaCotacaoMock.mockResolvedValue(makeCotacaoFornecedor({ id: "cf-novo" }));
    const onCotacaoFornecedoresAtualizados = vi.fn().mockResolvedValue(undefined);
    render(
      <ConferenciaFornecedoresTab
        {...baseProps({
          cotacao: makeCotacao({ canalOrigem: "WEB" }),
          cotacaoFornecedores: [],
          todosFornecedores: [
            {
              id: "forn-novo",
              nome: "Novo Fornecedor",
              prazoEntregaPadrao: null,
              condicaoPagamentoPadrao: null,
              pedidoMinimoPadrao: null,
              observacoesPadrao: null,
              status: "ATIVO",
              origemCadastro: "MANUAL",
              criadoEm: "2026-08-01T00:00:00Z",
              atualizadoEm: null,
            },
          ],
          onCotacaoFornecedoresAtualizados,
        })}
      />,
    );

    fireEvent.click(screen.getByText("Novo Fornecedor"));
    fireEvent.click(screen.getByRole("button", { name: /Adicionar cotação/ }));

    await waitFor(() => expect(adicionarFornecedorNaCotacaoMock).toHaveBeenCalledWith("cot-1", { fornecedorId: "forn-novo" }));
    await waitFor(() => expect(onCotacaoFornecedoresAtualizados).toHaveBeenCalled());
  });
});

describe("ConferenciaFornecedoresTab — captura (fornecedor PENDENTE)", () => {
  it("cotação WEB mostra o FornecedorCapturaCard (paste + Processar) pro fornecedor PENDENTE selecionado", () => {
    const cf = makeCotacaoFornecedor({ status: "PENDENTE" });
    render(<ConferenciaFornecedoresTab {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WEB" }), cotacaoFornecedores: [cf] })} />);

    expect(screen.getByPlaceholderText(/Sazon Legumes/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Processar Resposta Cotação" })).toBeTruthy();
  });

  it("cotação WHATSAPP mostra mensagem de espera pro fornecedor PENDENTE, sem capturar nada manualmente", () => {
    const cf = makeCotacaoFornecedor({ status: "PENDENTE" });
    render(<ConferenciaFornecedoresTab {...baseProps({ cotacao: makeCotacao({ canalOrigem: "WHATSAPP" }), cotacaoFornecedores: [cf] })} />);

    expect(screen.getByText(/Aguardando a resposta deste fornecedor via WhatsApp/)).toBeTruthy();
    expect(screen.queryByPlaceholderText(/Sazon Legumes/)).toBeNull();
  });

  it("digitar no textarea chama onTextoChange e clicar em Processar chama onProcessarResposta", () => {
    const cf = makeCotacaoFornecedor({ status: "PENDENTE" });
    const onTextoChange = vi.fn();
    const onProcessarResposta = vi.fn().mockResolvedValue(undefined);
    render(
      <ConferenciaFornecedoresTab
        {...baseProps({
          cotacao: makeCotacao({ canalOrigem: "WEB" }),
          cotacaoFornecedores: [cf],
          rascunhos: makeRascunhos({ "cf-1": { texto: "5un item - R$ 10,00" } }),
          onTextoChange,
          onProcessarResposta,
        })}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText(/Sazon Legumes/), { target: { value: "novo texto" } });
    expect(onTextoChange).toHaveBeenCalledWith("cf-1", "novo texto");

    fireEvent.click(screen.getByRole("button", { name: "Processar Resposta Cotação" }));
    expect(onProcessarResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-1" }), "5un item - R$ 10,00");
  });
});

describe("ConferenciaFornecedoresTab — auto-fetch de fornecedor PROCESSADO sem preview", () => {
  it("dispara onConferirResposta e mostra 'Carregando...' enquanto a promise está pendente", () => {
    const cf = makeCotacaoFornecedor();
    const onConferirResposta = vi.fn(() => new Promise<boolean>(() => {}));

    render(<ConferenciaFornecedoresTab {...baseProps({ cotacaoFornecedores: [cf], onConferirResposta })} />);

    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-1" }));
    expect(screen.getByText("Carregando...")).toBeTruthy();
  });

  // Reproduz a ordem exata do bug: o `rascunhos` prop muda (via rerender, simulando o
  // AprovacaoModal gravando o rascunho) ANTES da promise de onConferirResposta
  // resolver — não depois. Isso re-dispara o efeito (preview está nas deps) e roda o
  // cleanup antes da promise original assentar.
  it("não trava em 'Carregando...' quando o preview chega (via re-render) antes da promise de onConferirResposta resolver", async () => {
    const cf = makeCotacaoFornecedor();
    let resolverPromise: ((v: boolean) => void) | undefined;
    const promise = new Promise<boolean>((resolve) => {
      resolverPromise = resolve;
    });
    const onConferirResposta = vi.fn(() => promise);

    const { rerender } = render(<ConferenciaFornecedoresTab {...baseProps({ cotacaoFornecedores: [cf], onConferirResposta })} />);

    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Carregando...")).toBeTruthy();

    rerender(
      <ConferenciaFornecedoresTab
        {...baseProps({
          cotacaoFornecedores: [cf],
          onConferirResposta,
          rascunhos: makeRascunhos({ "cf-1": { preview: makePreview() } }),
        })}
      />,
    );

    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Carregando...")).toBeTruthy();

    await act(async () => {
      resolverPromise?.(true);
      await promise;
    });

    await waitFor(() => expect(screen.queryByText("Carregando...")).toBeNull());
  });

  it("chama onConferirResposta só uma vez mesmo com o re-render de preview no meio do caminho", async () => {
    const cf = makeCotacaoFornecedor();
    let resolverPromise: ((v: boolean) => void) | undefined;
    const promise = new Promise<boolean>((resolve) => {
      resolverPromise = resolve;
    });
    const onConferirResposta = vi.fn(() => promise);

    const { rerender } = render(<ConferenciaFornecedoresTab {...baseProps({ cotacaoFornecedores: [cf], onConferirResposta })} />);

    rerender(
      <ConferenciaFornecedoresTab
        {...baseProps({
          cotacaoFornecedores: [cf],
          onConferirResposta,
          rascunhos: makeRascunhos({ "cf-1": { preview: makePreview() } }),
        })}
      />,
    );

    await act(async () => {
      resolverPromise?.(true);
      await promise;
    });

    expect(onConferirResposta).toHaveBeenCalledTimes(1);
  });
});

describe("ConferenciaFornecedoresTab — proteção do ref contra cancelamento real", () => {
  it("resolução tardia da fetch do fornecedor anterior não mexe em `carregando` depois que o fornecedor ativo muda", async () => {
    const cf1 = makeCotacaoFornecedor({ id: "cf-1", nomeFornecedor: "Fornecedor 1", status: "PROCESSADO" });
    const cf2 = makeCotacaoFornecedor({ id: "cf-2", nomeFornecedor: "Fornecedor 2", status: "PENDENTE" });

    let resolverCf1: ((v: boolean) => void) | undefined;
    const promiseCf1 = new Promise<boolean>((resolve) => {
      resolverCf1 = resolve;
    });
    const onConferirResposta = vi.fn(() => promiseCf1);

    const { rerender } = render(<ConferenciaFornecedoresTab {...baseProps({ cotacaoFornecedores: [cf1, cf2], onConferirResposta })} />);

    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-1" }));
    const botaoFornecedor1 = screen.getByRole("button", { name: /Fornecedor 1/ }) as HTMLButtonElement;
    expect(botaoFornecedor1.disabled).toBe(true);

    // Fornecedor 2 passa a vir primeiro na lista — com `activeId` ainda no default
    // (null), `atual` recai em `sequencia[0]`, que agora é o fornecedor 2 (PENDENTE,
    // canal WEB por padrão em baseProps — mostra o FornecedorCapturaCard).
    rerender(<ConferenciaFornecedoresTab {...baseProps({ cotacaoFornecedores: [cf2, cf1], onConferirResposta })} />);

    // O fornecedor 2 está PENDENTE, não PROCESSADO — o efeito de auto-fetch não
    // dispara uma nova chamada para ele, e a única chamada continua sendo a do
    // fornecedor 1.
    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Processar Resposta Cotação" })).toBeTruthy();

    // A fetch antiga do fornecedor 1 finalmente resolve — como o fornecedor ativo
    // realmente mudou nesse meio-tempo, o ref não bate mais e o resultado é
    // descartado: `carregando` continua true (o chip do fornecedor 1 segue desabilitado).
    await act(async () => {
      resolverCf1?.(true);
      await promiseCf1;
    });

    await waitFor(() => {
      const botao = screen.getByRole("button", { name: /Fornecedor 1/ }) as HTMLButtonElement;
      expect(botao.disabled).toBe(true);
    });
  });
});
