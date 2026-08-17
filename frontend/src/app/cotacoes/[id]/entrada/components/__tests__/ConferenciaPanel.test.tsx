// Teste de regressão para o bug de "loading eterno" achado pelo usuário em
// 2026-08-17 (ver comentário em ConferenciaPanel.tsx, useEffect de auto-fetch
// nas linhas ~108-124).
//
// Causa raiz: o efeito de auto-fetch (dispara onConferirResposta quando o
// fornecedor ativo está PROCESSADO e ainda não tem preview em memória) tinha
// `preview` nas próprias deps. `onConferirResposta` (fornecido pelo pai,
// entrada/page.tsx) grava o preview resultante no rascunho do pai *antes* de sua
// própria promise assentar — o que muda o prop `preview` de null pro objeto e
// re-dispara o próprio efeito no meio do caminho, rodando o cleanup ANTES da
// promise original terminar. Com um bool `cancelado` capturado por closure no
// cleanup, esse re-disparo (que na verdade é sucesso, não troca de fornecedor)
// era mal-interpretado como cancelamento, e `carregando` nunca voltava a false.
//
// O fix trocou o bool de closure por um ref (`atualIdRef`) atualizado só quando
// o fornecedor ativo realmente muda — o `.finally()` da fetch original só ignora
// o resultado se o fornecedor ativo *de fato* mudou nesse meio-tempo.

import { describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import ConferenciaPanel from "@/app/cotacoes/[id]/entrada/components/ConferenciaPanel";
import { CotacaoFornecedorResponse, EstadoResolucao, PreviewRespostaResponse } from "@/lib/types";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

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

function makeEstadoResolucao(): EstadoResolucao {
  return { resolucoes: {}, spinOffs: {}, excluidos: {} };
}

function renderPanel(overrides: {
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  onConferirResposta: (cf: CotacaoFornecedorResponse) => Promise<boolean>;
  preview?: PreviewRespostaResponse | null;
  ativo?: boolean;
  fornecedorFocoId?: string | null;
}) {
  return render(
    <ConferenciaPanel
      cotacaoId="cot-1"
      cotacaoFornecedores={overrides.cotacaoFornecedores}
      onConferirResposta={overrides.onConferirResposta}
      onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
      onAtivoAlterado={vi.fn()}
      onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
      ativo={overrides.ativo ?? true}
      fornecedorFocoId={overrides.fornecedorFocoId ?? null}
      texto=""
      preview={overrides.preview ?? null}
      estadoResolucao={makeEstadoResolucao()}
      onEstadoResolucaoChange={vi.fn()}
    />,
  );
}

describe("ConferenciaPanel — auto-fetch de fornecedor PROCESSADO sem preview", () => {
  it("dispara onConferirResposta e mostra 'Carregando...' enquanto a promise está pendente", () => {
    const cf = makeCotacaoFornecedor();
    const onConferirResposta = vi.fn(() => new Promise<boolean>(() => {}));

    renderPanel({ cotacaoFornecedores: [cf], onConferirResposta });

    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-1" }));
    expect(screen.getByText("Carregando...")).toBeTruthy();
  });

  // Reproduz a ordem exata do bug: o prop `preview` muda (via rerender, simulando o
  // pai gravando o rascunho) ANTES da promise de onConferirResposta resolver — não
  // depois. Isso re-dispara o efeito (preview está nas deps) e roda o cleanup antes
  // da promise original assentar.
  it("não trava em 'Carregando...' quando o preview chega (via re-render) antes da promise de onConferirResposta resolver", async () => {
    const cf = makeCotacaoFornecedor();
    let resolverPromise: ((v: boolean) => void) | undefined;
    const promise = new Promise<boolean>((resolve) => {
      resolverPromise = resolve;
    });
    const onConferirResposta = vi.fn(() => promise);

    const { rerender } = renderPanel({ cotacaoFornecedores: [cf], onConferirResposta, preview: null });

    expect(onConferirResposta).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Carregando...")).toBeTruthy();

    // O pai (entrada/page.tsx) grava o preview no rascunho antes da promise original
    // assentar — simulado aqui via re-render com preview não-nulo, promise ainda
    // pendente. Isso re-dispara o efeito de auto-fetch (preview está nas deps), mas
    // como preview já não é mais null, o corpo do efeito retorna cedo sem uma nova
    // chamada a onConferirResposta.
    rerender(
      <ConferenciaPanel
        cotacaoId="cot-1"
        cotacaoFornecedores={[cf]}
        onConferirResposta={onConferirResposta}
        onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
        onAtivoAlterado={vi.fn()}
        onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
        ativo
        fornecedorFocoId={null}
        texto=""
        preview={makePreview()}
        estadoResolucao={makeEstadoResolucao()}
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

    const { rerender } = renderPanel({ cotacaoFornecedores: [cf], onConferirResposta, preview: null });

    rerender(
      <ConferenciaPanel
        cotacaoId="cot-1"
        cotacaoFornecedores={[cf]}
        onConferirResposta={onConferirResposta}
        onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
        onAtivoAlterado={vi.fn()}
        onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
        ativo
        fornecedorFocoId={null}
        texto=""
        preview={makePreview()}
        estadoResolucao={makeEstadoResolucao()}
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

describe("ConferenciaPanel — proteção do ref contra cancelamento real", () => {
  // Este caso cobre a outra metade do fix: quando o fornecedor ativo REALMENTE muda
  // enquanto uma fetch antiga ainda está pendente, o resultado dessa fetch antiga não
  // pode mexer em `carregando` (que agora pertence ao novo fornecedor ativo).
  //
  // A troca de fornecedor aqui é feita reordenando `cotacaoFornecedores` (com
  // `activeId` interno ainda no default `null`, `atual` recai em `sequencia[0]`) em
  // vez de clicar num chip de navegação — os chips ficam `disabled` enquanto
  // `carregando=true`, então clicar não é uma forma alcançável de disparar essa troca
  // enquanto a fetch anterior está pendente; a troca "por fora" (ex.: o pai
  // recalculando a ordem/lista após um evento em outro passo) é o caminho realista.
  it("resolução tardia da fetch do fornecedor anterior não mexe em `carregando` depois que o fornecedor ativo muda", async () => {
    const cf1 = makeCotacaoFornecedor({ id: "cf-1", nomeFornecedor: "Fornecedor 1", status: "PROCESSADO" });
    const cf2 = makeCotacaoFornecedor({ id: "cf-2", nomeFornecedor: "Fornecedor 2", status: "PENDENTE" });

    let resolverCf1: ((v: boolean) => void) | undefined;
    const promiseCf1 = new Promise<boolean>((resolve) => {
      resolverCf1 = resolve;
    });
    const onConferirResposta = vi.fn(() => promiseCf1);

    const { rerender } = renderPanel({
      cotacaoFornecedores: [cf1, cf2],
      onConferirResposta,
      preview: null,
    });

    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-1" }));
    const botaoFornecedor1 = screen.getByRole("button", { name: "Fornecedor 1" }) as HTMLButtonElement;
    expect(botaoFornecedor1.disabled).toBe(true);

    // Fornecedor 2 passa a vir primeiro na lista — com `activeId` ainda no default
    // (null), `atual` recai em `sequencia[0]`, que agora é o fornecedor 2. Isso muda
    // o fornecedor ativo sem passar por clique nem por `ativo`.
    rerender(
      <ConferenciaPanel
        cotacaoId="cot-1"
        cotacaoFornecedores={[cf2, cf1]}
        onConferirResposta={onConferirResposta}
        onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
        onAtivoAlterado={vi.fn()}
        onCotacaoFornecedoresAtualizados={vi.fn().mockResolvedValue(undefined)}
        ativo
        fornecedorFocoId={null}
        texto=""
        preview={null}
        estadoResolucao={makeEstadoResolucao()}
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
      const botao = screen.getByRole("button", { name: "Fornecedor 1" }) as HTMLButtonElement;
      expect(botao.disabled).toBe(true);
    });
  });
});
