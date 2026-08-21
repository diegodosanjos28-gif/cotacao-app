// Teste de regressão portado de ConferenciaPanel.test.tsx (removido nesta mesma leva,
// Fase C do refactor da Entrada de Dados, 2026-08-20) para o bug de "loading eterno"
// achado pelo usuário em 2026-08-17 (ver comentário no componente, useEffect de
// auto-fetch). A lógica do efeito não mudou — só a forma como texto/preview chegam
// (agora via `rascunhos[cfId]`, dono é o AprovacaoModal, não mais texto/preview/
// estadoResolucao soltos vindos de entrada/page.tsx).
//
// Causa raiz: o efeito de auto-fetch (dispara onConferirResposta quando o fornecedor
// ativo está PROCESSADO e ainda não tem preview em memória) tinha `preview` nas
// próprias deps. `onConferirResposta` (fornecido pelo AprovacaoModal) grava o preview
// resultante no rascunho *antes* de sua própria promise assentar — o que muda
// `rascunhos[atual.id].preview` de null pro objeto e re-dispara o próprio efeito no
// meio do caminho, rodando o cleanup ANTES da promise original terminar. O fix usa um
// ref (`atualIdRef`) atualizado só quando o fornecedor ativo realmente muda — o
// `.finally()` da fetch original só ignora o resultado se o fornecedor ativo *de
// fato* mudou nesse meio-tempo.

import { describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import ConferenciaFornecedoresTab from "../ConferenciaFornecedoresTab";
import { CotacaoFornecedorResponse, PreviewRespostaResponse } from "@/lib/types";
import { RascunhoFornecedor } from "../rascunhoFornecedor";

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

function renderTab(overrides: {
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  onConferirResposta: (cf: CotacaoFornecedorResponse) => Promise<boolean>;
  rascunhos?: Record<string, RascunhoFornecedor>;
  fornecedorFocoId?: string | null;
}) {
  return render(
    <ConferenciaFornecedoresTab
      cotacaoId="cot-1"
      cotacaoFornecedores={overrides.cotacaoFornecedores}
      onConferirResposta={overrides.onConferirResposta}
      onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
      onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
      fornecedorFocoId={overrides.fornecedorFocoId ?? null}
      rascunhos={overrides.rascunhos ?? {}}
      onEstadoResolucaoChange={vi.fn()}
    />,
  );
}

describe("ConferenciaFornecedoresTab — banner de contexto e estado sem fornecedor", () => {
  it("mostra o banner azul de contexto acima dos chips", () => {
    renderTab({
      cotacaoFornecedores: [makeCotacaoFornecedor({ status: "PENDENTE" })],
      onConferirResposta: vi.fn(),
    });

    expect(screen.getByText("Conferência das Cotações dos Fornecedores")).toBeTruthy();
  });

  it("sem nenhum fornecedor, mostra mensagem dedicada", () => {
    renderTab({ cotacaoFornecedores: [], onConferirResposta: vi.fn() });

    expect(screen.getByText(/Nenhum fornecedor adicionado a esta cotação ainda/)).toBeTruthy();
  });
});

describe("ConferenciaFornecedoresTab — auto-fetch de fornecedor PROCESSADO sem preview", () => {
  it("dispara onConferirResposta e mostra 'Carregando...' enquanto a promise está pendente", () => {
    const cf = makeCotacaoFornecedor();
    const onConferirResposta = vi.fn(() => new Promise<boolean>(() => {}));

    renderTab({ cotacaoFornecedores: [cf], onConferirResposta });

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

    const { rerender } = renderTab({ cotacaoFornecedores: [cf], onConferirResposta, rascunhos: {} });

    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Carregando...")).toBeTruthy();

    // O AprovacaoModal grava o preview no rascunho antes da promise original
    // assentar — simulado aqui via re-render com preview não-nulo, promise ainda
    // pendente. Isso re-dispara o efeito de auto-fetch (preview está nas deps), mas
    // como preview já não é mais null, o corpo do efeito retorna cedo sem uma nova
    // chamada a onConferirResposta.
    rerender(
      <ConferenciaFornecedoresTab
        cotacaoId="cot-1"
        cotacaoFornecedores={[cf]}
        onConferirResposta={onConferirResposta}
        onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
        onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
        fornecedorFocoId={null}
        rascunhos={makeRascunhos({ "cf-1": { preview: makePreview() } })}
        onEstadoResolucaoChange={vi.fn()}
      />,
    );

    // preview já chegou, mas a promise original (que o efeito ainda está aguardando)
    // não resolveu — se resolvesse cedo demais o bug não seria reproduzido.
    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Carregando...")).toBeTruthy();

    // Só agora a promise original assenta.
    await act(async () => {
      resolverPromise?.(true);
      await promise;
    });

    // Com o bug antigo (bool `cancelado` de closure), o cleanup disparado pelo
    // re-render acima marcava a fetch como cancelada e `carregando` travava em true
    // pra sempre, mesmo com preview disponível. Com o fix (ref comparando o
    // fornecedor ativo no momento em que a promise resolve), carregando volta a false.
    await waitFor(() => expect(screen.queryByText("Carregando...")).toBeNull());
  });

  it("chama onConferirResposta só uma vez mesmo com o re-render de preview no meio do caminho", async () => {
    const cf = makeCotacaoFornecedor();
    let resolverPromise: ((v: boolean) => void) | undefined;
    const promise = new Promise<boolean>((resolve) => {
      resolverPromise = resolve;
    });
    const onConferirResposta = vi.fn(() => promise);

    const { rerender } = renderTab({ cotacaoFornecedores: [cf], onConferirResposta, rascunhos: {} });

    rerender(
      <ConferenciaFornecedoresTab
        cotacaoId="cot-1"
        cotacaoFornecedores={[cf]}
        onConferirResposta={onConferirResposta}
        onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
        onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
        fornecedorFocoId={null}
        rascunhos={makeRascunhos({ "cf-1": { preview: makePreview() } })}
        onEstadoResolucaoChange={vi.fn()}
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
  // Este caso cobre a outra metade do fix: quando o fornecedor ativo REALMENTE muda
  // enquanto uma fetch antiga ainda está pendente, o resultado dessa fetch antiga não
  // pode mexer em `carregando` (que agora pertence ao novo fornecedor ativo).
  it("resolução tardia da fetch do fornecedor anterior não mexe em `carregando` depois que o fornecedor ativo muda", async () => {
    const cf1 = makeCotacaoFornecedor({ id: "cf-1", nomeFornecedor: "Fornecedor 1", status: "PROCESSADO" });
    const cf2 = makeCotacaoFornecedor({ id: "cf-2", nomeFornecedor: "Fornecedor 2", status: "PENDENTE" });

    let resolverCf1: ((v: boolean) => void) | undefined;
    const promiseCf1 = new Promise<boolean>((resolve) => {
      resolverCf1 = resolve;
    });
    const onConferirResposta = vi.fn(() => promiseCf1);

    const { rerender } = renderTab({ cotacaoFornecedores: [cf1, cf2], onConferirResposta, rascunhos: {} });

    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-1" }));
    const botaoFornecedor1 = screen.getByRole("button", { name: /Fornecedor 1/ }) as HTMLButtonElement;
    expect(botaoFornecedor1.disabled).toBe(true);

    // Fornecedor 2 passa a vir primeiro na lista — com `activeId` ainda no default
    // (null), `atual` recai em `sequencia[0]`, que agora é o fornecedor 2.
    rerender(
      <ConferenciaFornecedoresTab
        cotacaoId="cot-1"
        cotacaoFornecedores={[cf2, cf1]}
        onConferirResposta={onConferirResposta}
        onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
        onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
        fornecedorFocoId={null}
        rascunhos={{}}
        onEstadoResolucaoChange={vi.fn()}
      />,
    );

    // O fornecedor 2 está PENDENTE, não PROCESSADO — o efeito de auto-fetch não
    // dispara uma nova chamada para ele, e a única chamada continua sendo a do
    // fornecedor 1.
    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/ainda não respondeu/)).toBeTruthy();

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
