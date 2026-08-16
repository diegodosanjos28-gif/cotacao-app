// Fase 3 (WhatsApp): FornecedoresCotacoesSection ganha um banner informativo quando a
// cotação é WHATSAPP e ainda não chegou nenhuma resposta de fornecedor (cotacaoFornecedores
// vazio). Decisão de produto documentada no comentário do componente: o banner convive
// com a UI manual de "Adicionar fornecedor" — o operador continua podendo adicionar
// manualmente mesmo numa cotação WhatsApp, o banner não a substitui/esconde.
//
// Fase 4: navegação sequencial entre fornecedores já adicionados. Diferente da versão
// original da Fase 4, o texto colado e o preview NÃO ficam mais montados por fornecedor
// dentro desta seção — foram elevados para entrada/page.tsx (texto/preview/onClosePreview
// chegam como props controladas, e onAtivoAlterado avisa o pai qual fornecedor está ativo
// para que ele possa resetar texto/preview ao trocar). Só o fornecedor ativo (`atual`) é
// renderizado por vez; o "Processar Cotação" também saiu daqui, agora é botão único no
// rodapé (EntradaFooter) — ver comentário em FornecedorRespostaBlock.tsx e EntradaFooter.tsx.
// Os testes abaixo mockam @/lib/api e next/navigation diretamente (a seção chama useRouter
// para navegar ao Comparativo quando o último fornecedor pendente é confirmado).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import FornecedoresCotacoesSection from "@/app/cotacoes/[id]/entrada/components/FornecedoresCotacoesSection";
import { Cotacao, CotacaoFornecedorResponse, Fornecedor, PreviewRespostaResponse } from "@/lib/types";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
const { confirmarRespostaMock, adicionarFornecedorNaCotacaoMock, atualizarFornecedorMock, criarFornecedorMock } =
  vi.hoisted(() => ({
    confirmarRespostaMock: vi.fn(),
    adicionarFornecedorNaCotacaoMock: vi.fn(),
    atualizarFornecedorMock: vi.fn(),
    criarFornecedorMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/api", () => ({
  confirmarResposta: confirmarRespostaMock,
  adicionarFornecedorNaCotacao: adicionarFornecedorNaCotacaoMock,
  atualizarFornecedor: atualizarFornecedorMock,
  criarFornecedor: criarFornecedorMock,
}));

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

function makeFornecedor(overrides: Partial<Fornecedor> = {}): Fornecedor {
  return {
    id: "forn-1",
    nome: "Fornecedor 1",
    prazoEntregaPadrao: null,
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

// Item OK (não REVISAR) — não bloqueia "Confirmar e Processar", então os testes desta
// seção não precisam resolver divergência nenhuma pra chegar em confirmar() (isso já é
// coberto em detalhe por ConferenciaModal.test.tsx).
function makePreviewOk(): PreviewRespostaResponse {
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

const BANNER = "Nenhuma resposta de fornecedor via WhatsApp recebida ainda.";

function renderSection(overrides: {
  cotacao?: Cotacao;
  cotacaoId?: string;
  cotacaoFornecedores?: CotacaoFornecedorResponse[];
  todosFornecedores?: Fornecedor[];
  onCotacaoFornecedoresAtualizados?: () => void;
  onAtivoAlterado?: (cf: CotacaoFornecedorResponse | null) => void;
  onConferirResposta?: (cf: CotacaoFornecedorResponse) => Promise<boolean>;
  onCancelarConferencia?: (cf: CotacaoFornecedorResponse) => Promise<void>;
  texto?: string;
  preview?: PreviewRespostaResponse | null;
  modalAberto?: boolean;
} = {}) {
  return render(
    <FornecedoresCotacoesSection
      cotacao={overrides.cotacao ?? makeCotacao()}
      cotacaoId={overrides.cotacaoId ?? "cot-1"}
      cotacaoFornecedores={overrides.cotacaoFornecedores ?? []}
      todosFornecedores={overrides.todosFornecedores ?? []}
      onCotacaoFornecedoresAtualizados={overrides.onCotacaoFornecedoresAtualizados ?? vi.fn()}
      onFornecedorAtualizado={vi.fn()}
      onFornecedorInativado={vi.fn()}
      onAtivoAlterado={overrides.onAtivoAlterado ?? vi.fn()}
      onConferirResposta={overrides.onConferirResposta ?? vi.fn().mockResolvedValue(true)}
      onCancelarConferencia={overrides.onCancelarConferencia ?? vi.fn().mockResolvedValue(undefined)}
      texto={overrides.texto ?? ""}
      setTexto={vi.fn()}
      preview={overrides.preview ?? null}
      // Nos testes existentes (pré-Fase 4.1), um preview não nulo sempre significava
      // modal aberto — o padrão aqui preserva esse comportamento para não reescrever
      // os casos que já esperam o dialog visível assim que `preview` é passado.
      modalAberto={overrides.modalAberto ?? overrides.preview != null}
      onClosePreview={vi.fn()}
      estadoResolucao={{ resolucoes: {}, spinOffs: {}, excluidos: {} }}
      onEstadoResolucaoChange={vi.fn()}
      setErro={vi.fn()}
    />,
  );
}

beforeEach(() => {
  pushMock.mockReset();
  confirmarRespostaMock.mockReset();
  adicionarFornecedorNaCotacaoMock.mockReset();
});

describe("FornecedoresCotacoesSection — banner WhatsApp sem fornecedores", () => {
  it("aparece quando canalOrigem=WHATSAPP e nenhum fornecedor foi adicionado à cotação", () => {
    renderSection({ cotacao: makeCotacao({ canalOrigem: "WHATSAPP" }), cotacaoFornecedores: [] });
    expect(screen.getByText(new RegExp(BANNER))).toBeTruthy();
  });

  it("não aparece para cotação WEB, mesmo sem fornecedores", () => {
    renderSection({ cotacao: makeCotacao({ canalOrigem: "WEB" }), cotacaoFornecedores: [] });
    expect(screen.queryByText(new RegExp(BANNER))).toBeNull();
  });

  it("não aparece para cotação WhatsApp assim que já existe ao menos um fornecedor na cotação", () => {
    renderSection({
      cotacao: makeCotacao({ canalOrigem: "WHATSAPP" }),
      cotacaoFornecedores: [makeCotacaoFornecedor()],
    });
    expect(screen.queryByText(new RegExp(BANNER))).toBeNull();
  });

  it("o painel de fornecedores continua presente ao lado do banner (não é escondido) — abre sozinho quando a cotação ainda não tem nenhum", () => {
    renderSection({ cotacao: makeCotacao({ canalOrigem: "WHATSAPP" }), cotacaoFornecedores: [] });
    expect(screen.getByText(new RegExp(BANNER))).toBeTruthy();
    expect(screen.getByRole("button", { name: "+ Novo" })).toBeTruthy();
  });
});

describe("FornecedoresCotacoesSection — Fase 4: navegação sequencial", () => {
  it("indicador 'X de Y' e chip de pendentes refletem a sequência (pendentes primeiro, ordem preservada)", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A", status: "CONFIRMADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "B", status: "PENDENTE" });
    const c = makeCotacaoFornecedor({ id: "cf-c", fornecedorId: "forn-c", ordem: 2, nomeFornecedor: "C", status: "PROCESSADO" });
    renderSection({ cotacaoFornecedores: [a, b, c] });

    expect(screen.getByText("2 para conferir")).toBeTruthy();
    // sequência: [B, C, A] (pendentes por ordem, depois confirmados) — ativo padrão é B.
    expect(screen.getByText("Fornecedor 1 de 3")).toBeTruthy();
  });

  it("navegação anterior/próximo percorre a sequência com wrap-around", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "B" });
    renderSection({ cotacaoFornecedores: [a, b] });

    // Com mais de um fornecedor, existem dois pares de controles equivalentes: a
    // paginação "‹ Fornecedor X de Y ›" e as setas flutuantes sobre o card — ambos
    // chamam irParaAdjacente, então qualquer um serve para exercitar a navegação.
    expect(screen.getByText("Fornecedor 1 de 2")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Próximo fornecedor" })[0]);
    expect(screen.getByText("Fornecedor 2 de 2")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Próximo fornecedor" })[0]);
    expect(screen.getByText("Fornecedor 1 de 2")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Fornecedor anterior" })[0]);
    expect(screen.getByText("Fornecedor 2 de 2")).toBeTruthy();
  });

  it("notifica onAtivoAlterado com o fornecedor ativo assim que montado", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A" });
    const onAtivoAlterado = vi.fn();
    renderSection({ cotacaoFornecedores: [a], onAtivoAlterado });

    expect(onAtivoAlterado).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-a" }));
  });

  it("notifica onAtivoAlterado com null quando não há fornecedores (modo adicionar)", () => {
    const onAtivoAlterado = vi.fn();
    renderSection({ cotacaoFornecedores: [], onAtivoAlterado });

    expect(onAtivoAlterado).toHaveBeenCalledWith(null);
  });

  it("repassa texto (controlado pelo pai) para o painel do fornecedor ativo", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A" });
    renderSection({ cotacaoFornecedores: [a], texto: "5un item teste - R$ 10,00" });

    expect((screen.getByPlaceholderText(/Sazon Legumes 60g/) as HTMLTextAreaElement).value).toBe(
      "5un item teste - R$ 10,00",
    );
  });

  it("confirmar um fornecedor com outro pendente não navega e avança para o próximo pendente", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Fornecedor A" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "Fornecedor B" });
    confirmarRespostaMock.mockResolvedValue([]);
    const onAtualizado = vi.fn();

    // preview controlado, como se onProcessar (page.tsx) já tivesse processado a
    // resposta do fornecedor ativo (A, primeiro pendente da sequência).
    renderSection({
      cotacaoFornecedores: [a, b],
      onCotacaoFornecedoresAtualizados: onAtualizado,
      preview: makePreviewOk(),
    });

    expect(screen.getByRole("dialog", { name: /Fornecedor A/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar e Processar" }));
    await screen.findByText("Fornecedor 2 de 2");

    expect(confirmarRespostaMock).toHaveBeenCalledTimes(1);
    expect(pushMock).not.toHaveBeenCalled();
    expect(onAtualizado).toHaveBeenCalled();
  });

  it("confirmar o único fornecedor pendente navega para o comparativo (regressão do caso mais comum)", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Fornecedor A" });
    confirmarRespostaMock.mockResolvedValue([]);

    renderSection({ cotacaoId: "cot-xyz", cotacaoFornecedores: [a], preview: makePreviewOk() });

    fireEvent.click(screen.getByRole("button", { name: "Confirmar e Processar" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/cotacoes/cot-xyz/comparativo"));
  });
});

// Achado do usuário, 2026-08-04: uma resposta de fornecedor recebida via WhatsApp já
// chega persistida (StatusItem.PENDENTE_CONFIRMACAO), sem preview em memória — sem
// este botão, o operador não tinha como reabrir a Conferência pra ela. Unificado com
// o antigo "Continuar Conferência" (preview já em memória): o botão age sempre sobre o
// fornecedor ABERTO no carrossel, não mais "o primeiro pendente da sequência" — ver
// onConferirResposta (entrada/page.tsx) e GET .../resposta-persistida
// (FornecedorRespostaService.textoPersistido).
describe("FornecedoresCotacoesSection — botão 'Conferir resposta do fornecedor'", () => {
  it("não aparece quando não há nenhum fornecedor pendente (lista vazia)", () => {
    renderSection({ cotacaoFornecedores: [] });
    expect(screen.queryByText(/Conferir resposta do fornecedor/)).toBeNull();
  });

  it("não aparece quando todos os fornecedores já estão CONFIRMADO", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, status: "CONFIRMADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, status: "CONFIRMADO" });
    renderSection({ cotacaoFornecedores: [a, b] });
    expect(screen.queryByText(/Conferir resposta do fornecedor/)).toBeNull();
  });

  it("não aparece quando o fornecedor aberto está PENDENTE (ainda não respondeu — o botão relevante ali é 'Processar Cotação')", () => {
    // sequência = [b PENDENTE, c PROCESSADO, a CONFIRMADO] — ativo padrão é b.
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, status: "CONFIRMADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, status: "PENDENTE" });
    const c = makeCotacaoFornecedor({ id: "cf-c", fornecedorId: "forn-c", ordem: 2, status: "PROCESSADO" });
    renderSection({ cotacaoFornecedores: [a, b, c] });

    expect(screen.queryByText(/Conferir resposta do fornecedor/)).toBeNull();
  });

  it("aparece com a contagem correta quando o fornecedor aberto tem resposta pendente de conferir, mesmo em cotação WEB (gate de canal removido)", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, status: "CONFIRMADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, status: "PROCESSADO" });
    renderSection({ cotacao: makeCotacao({ canalOrigem: "WEB" }), cotacaoFornecedores: [a, b] });

    expect(screen.getByRole("button", { name: "Conferir resposta do fornecedor (1)" })).toBeTruthy();
  });

  it("não aparece quando o fornecedor aberto já está CONFIRMADO (evita reverter o status silenciosamente)", () => {
    // sequência = [b PROCESSADO, a CONFIRMADO] — ativo padrão é b (PROCESSADO), então
    // navega explicitamente pra "a" antes de checar o botão.
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A", status: "CONFIRMADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "B", status: "PROCESSADO" });
    renderSection({ cotacaoFornecedores: [a, b] });

    fireEvent.click(screen.getByRole("button", { name: "A" }));

    expect(screen.queryByRole("button", { name: /Conferir resposta do fornecedor/ })).toBeNull();
  });

  it("ao clicar, chama onConferirResposta com o fornecedor padrão-ativo (primeiro da sequência 'pendentes primeiro')", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Confirmado", status: "CONFIRMADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "Primeiro Pendente", status: "PROCESSADO" });
    const c = makeCotacaoFornecedor({ id: "cf-c", fornecedorId: "forn-c", ordem: 2, nomeFornecedor: "Segundo Pendente", status: "PROCESSADO" });
    const onConferirResposta = vi.fn().mockResolvedValue(true);

    renderSection({ cotacaoFornecedores: [a, b, c], onConferirResposta });

    fireEvent.click(screen.getByRole("button", { name: "Conferir resposta do fornecedor (2)" }));

    await waitFor(() => expect(onConferirResposta).toHaveBeenCalledTimes(1));
    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-b" }));
  });

  it("ao clicar, chama onConferirResposta com o fornecedor ABERTO, não com o primeiro pendente (prova a mudança de alvo)", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Primeiro Pendente", status: "PROCESSADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "Segundo Pendente", status: "PROCESSADO" });
    const onConferirResposta = vi.fn().mockResolvedValue(true);

    renderSection({ cotacaoFornecedores: [a, b], onConferirResposta });

    // navega manualmente pro segundo pendente antes de clicar.
    fireEvent.click(screen.getByRole("button", { name: "Segundo Pendente" }));
    await screen.findByText("Fornecedor 2 de 2");
    fireEvent.click(screen.getByRole("button", { name: "Conferir resposta do fornecedor (2)" }));

    await waitFor(() => expect(onConferirResposta).toHaveBeenCalledTimes(1));
    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-b" }));
  });
});

// Achado do usuário, 2026-08-04: antes, confirmar um fornecedor só navegava pro
// próximo pendente sem reabrir a Conferência dele — exigia clicar em "Conferir
// resposta do fornecedor" de novo pra cada um. Agora onFornecedorConfirmado encadeia
// automaticamente, abrindo (não confirmando) o próximo PROCESSADO sozinho.
describe("FornecedoresCotacoesSection — encadeamento automático após confirmar", () => {
  it("confirmar um fornecedor com outro PROCESSADO pendente abre a Conferência do próximo sozinho", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Fornecedor A", status: "PROCESSADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "Fornecedor B", status: "PROCESSADO" });
    confirmarRespostaMock.mockResolvedValue([]);
    const onConferirResposta = vi.fn().mockResolvedValue(true);

    renderSection({ cotacaoFornecedores: [a, b], preview: makePreviewOk(), onConferirResposta });

    fireEvent.click(screen.getByRole("button", { name: "Confirmar e Processar" }));
    await screen.findByText("Fornecedor 2 de 2");

    await waitFor(() => expect(onConferirResposta).toHaveBeenCalledTimes(1));
    expect(onConferirResposta).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-b" }));
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("confirmar um fornecedor com outro PENDENTE (sem resposta ainda) navega mas não tenta abrir a Conferência dele", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Fornecedor A", status: "PROCESSADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "Fornecedor B", status: "PENDENTE" });
    confirmarRespostaMock.mockResolvedValue([]);
    const onConferirResposta = vi.fn().mockResolvedValue(true);

    renderSection({ cotacaoFornecedores: [a, b], preview: makePreviewOk(), onConferirResposta });

    fireEvent.click(screen.getByRole("button", { name: "Confirmar e Processar" }));
    await screen.findByText("Fornecedor 2 de 2");

    expect(onConferirResposta).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("se onConferirResposta falhar durante o encadeamento, para ali (não pula pro próximo nem navega pro comparativo)", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "Fornecedor A", status: "PROCESSADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", ordem: 1, nomeFornecedor: "Fornecedor B", status: "PROCESSADO" });
    confirmarRespostaMock.mockResolvedValue([]);
    const onConferirResposta = vi.fn().mockResolvedValue(false);

    renderSection({ cotacaoFornecedores: [a, b], preview: makePreviewOk(), onConferirResposta });

    fireEvent.click(screen.getByRole("button", { name: "Confirmar e Processar" }));
    await screen.findByText("Fornecedor 2 de 2");

    await waitFor(() => expect(onConferirResposta).toHaveBeenCalledTimes(1));
    expect(pushMock).not.toHaveBeenCalled();
  });
});
