// Fase 4: regressão para o bug encontrado em revisão de código. FornecedorRespostaBlock
// ficou permanentemente montado (mount-all em FornecedoresCotacoesSection), então o
// `useState(() => dadosDe(fornecedor))` que seedava `dados` só rodava uma vez, no mount.
// Se o MESMO fornecedor fosse editado em outro lugar da tela (modal "Editar" da
// FornecedoresSidebar → atualizarFornecedor → onFornecedorAtualizado troca a referência
// do objeto `fornecedor` no array do pai), o bloco continuava com `dados` obsoleto, e o
// próximo `salvarCampo` (onBlur) fazia um PUT do objeto inteiro com os campos antigos,
// revertendo silenciosamente a edição feita fora do bloco.
//
// O fix ressincroniza `dados` a partir da prop `fornecedor` quando a referência muda,
// durante a renderização (não em useEffect). Os testes abaixo usam `rerender` da mesma
// instância (nunca um novo `render()`) para provar que a ressincronização acontece sem
// remount — um novo render trivialmente "passaria" sem exercitar o fix.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import FornecedorRespostaBlock from "@/app/cotacoes/[id]/entrada/components/FornecedorRespostaBlock";
import { CotacaoFornecedorResponse, Fornecedor, PreviewRespostaResponse } from "@/lib/types";

const { atualizarFornecedorMock } = vi.hoisted(() => ({ atualizarFornecedorMock: vi.fn() }));

vi.mock("@/lib/api", () => ({
  atualizarFornecedor: atualizarFornecedorMock,
  enviarResposta: vi.fn(),
}));

const PLACEHOLDER_PRAZO = "Ex: 2 dias úteis";
const PLACEHOLDER_OBSERVACOES = "Desconto PIX, entrega grátis, etc.";

function makeFornecedor(overrides: Partial<Fornecedor> = {}): Fornecedor {
  return {
    id: "forn-1",
    nome: "Fornecedor 1",
    prazoEntregaPadrao: "2 dias",
    condicaoPagamentoPadrao: null,
    pedidoMinimoPadrao: null,
    observacoesPadrao: null,
    status: "ATIVO",
    origemCadastro: "MANUAL",
    criadoEm: "2026-07-30T10:00:00Z",
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
    status: "PENDENTE",
    ...overrides,
  };
}

function makePreview(): PreviewRespostaResponse {
  return {
    contadores: { total: 1, ok: 1, atencao: 0, revisar: 0 },
    itens: [
      {
        itemBaseId: "item-1",
        nomeItemBase: "Item Teste",
        status: "OK",
        motivos: [],
        candidatos: [
          { textoOriginal: "5un item teste", marcaOferecida: null, precoInformado: 10, confiancaMatch: 0.95, semEstoque: false },
        ],
        preservado: false,
        precoAnteriorConfirmado: null,
      },
    ],
  };
}

function bloco(
  cotacaoFornecedor: CotacaoFornecedorResponse,
  fornecedor: Fornecedor | undefined,
  overrides: { preview?: PreviewRespostaResponse | null; modalAberto?: boolean } = {},
) {
  return (
    <FornecedorRespostaBlock
      cotacaoId="cot-1"
      cotacaoFornecedor={cotacaoFornecedor}
      fornecedor={fornecedor}
      onFornecedorAtualizado={vi.fn()}
      onConfirmado={vi.fn()}
      texto=""
      setTexto={vi.fn()}
      preview={overrides.preview ?? null}
      modalAberto={overrides.modalAberto ?? false}
      onClosePreview={vi.fn()}
      onCancelarConferencia={vi.fn().mockResolvedValue(undefined)}
      estadoResolucao={{ resolucoes: {}, spinOffs: {}, excluidos: {} }}
      onEstadoResolucaoChange={vi.fn()}
    />
  );
}

beforeEach(() => {
  atualizarFornecedorMock.mockReset();
});

describe("FornecedorRespostaBlock — Fase 4: ressincronização de dados quando fornecedor é editado fora do bloco", () => {
  it("reflete o novo valor de um campo quando a prop `fornecedor` muda de referência (rerender, sem remount)", () => {
    const cf = makeCotacaoFornecedor();
    const original = makeFornecedor({ prazoEntregaPadrao: "2 dias" });
    const { rerender } = render(bloco(cf, original));

    expect((screen.getByPlaceholderText(PLACEHOLDER_PRAZO) as HTMLInputElement).value).toBe("2 dias");

    const editadoExternamente = { ...original, prazoEntregaPadrao: "5 dias" };
    rerender(bloco(cf, editadoExternamente));

    expect((screen.getByPlaceholderText(PLACEHOLDER_PRAZO) as HTMLInputElement).value).toBe("5 dias");
  });

  it("não reverte silenciosamente uma edição externa: salvar outro campo após a ressincronização envia o valor novo, não o obsoleto", async () => {
    const cf = makeCotacaoFornecedor();
    const original = makeFornecedor({ prazoEntregaPadrao: "2 dias" });
    const editadoExternamente = { ...original, prazoEntregaPadrao: "5 dias" };
    atualizarFornecedorMock.mockResolvedValue({ ...editadoExternamente, observacoesPadrao: "Desconto PIX" });

    const { rerender } = render(bloco(cf, original));

    // simula a edição feita em outro lugar da tela (FornecedoresSidebar → modal "Editar")
    // chegando via prop, com nova referência de objeto — como faria entrada/page.tsx.
    rerender(bloco(cf, editadoExternamente));

    // opera num campo DIFERENTE do que mudou externamente — dispara o PUT do objeto
    // `dados` inteiro (salvarCampo faz PUT full-object, não patch parcial).
    const observacoes = screen.getByPlaceholderText(PLACEHOLDER_OBSERVACOES) as HTMLTextAreaElement;
    fireEvent.change(observacoes, { target: { value: "Desconto PIX" } });
    fireEvent.blur(observacoes);

    await waitFor(() => expect(atualizarFornecedorMock).toHaveBeenCalledTimes(1));
    expect(atualizarFornecedorMock).toHaveBeenCalledWith(
      "forn-1",
      expect.objectContaining({ prazoEntregaPadrao: "5 dias", observacoesPadrao: "Desconto PIX" }),
    );
  });

  it("um bloco de outro fornecedor não muda seus dados quando renderizado de novo com a mesma referência de prop", () => {
    const cfB = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", nomeFornecedor: "Fornecedor B" });
    const fornecedorB = makeFornecedor({ id: "forn-b", nome: "Fornecedor B", prazoEntregaPadrao: "3 dias" });
    const { rerender } = render(bloco(cfB, fornecedorB));

    expect((screen.getByPlaceholderText(PLACEHOLDER_PRAZO) as HTMLInputElement).value).toBe("3 dias");

    // rerender com a MESMA referência de fornecedorB (nenhuma edição real aconteceu) —
    // não deve alterar o valor exibido nem disparar ressincronização espúria.
    rerender(bloco(cfB, fornecedorB));

    expect((screen.getByPlaceholderText(PLACEHOLDER_PRAZO) as HTMLInputElement).value).toBe("3 dias");
  });
});

// Achado do usuário, 2026-08-04: "Continuar Conferência" foi removido daqui — a
// unificação dos dois fluxos de Conferência (reusar preview em memória vs.
// reconstruir de texto persistido) virou um único botão em
// FornecedoresCotacoesSection ("Conferir resposta do fornecedor"), que age sobre o
// fornecedor aberto no carrossel em vez de exigir um botão por bloco.
describe("FornecedorRespostaBlock — sem botão de retomada de Conferência", () => {
  it("com preview em memória e modal fechado, não renderiza 'Continuar Conferência' e mostra o rótulo de status completo", () => {
    const cf = makeCotacaoFornecedor({ status: "PROCESSADO" });
    render(bloco(cf, makeFornecedor(), { preview: makePreview(), modalAberto: false }));

    expect(screen.queryByText(/Continuar Conferência/)).toBeNull();
    expect(
      screen.getByText("Processado — confirme na Conferência para liberar o próximo fornecedor"),
    ).toBeTruthy();
  });
});
