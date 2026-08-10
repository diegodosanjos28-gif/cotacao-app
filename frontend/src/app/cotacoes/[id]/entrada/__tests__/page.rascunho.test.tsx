// Fase 4.1: o rascunho da Conferência de um fornecedor (texto colado, preview
// processado, resoluções/spin-offs/exclusões em andamento) foi elevado para um mapa
// por cotacaoFornecedorId em page.tsx (`rascunhos`/`RascunhoFornecedor`), pra sobreviver
// à troca de fornecedor ativo e ao fechar/reabrir do modal de Conferência — antes esse
// estado vivia solto (useState local do ConferenciaModal + estado único não-particionado
// em page.tsx) e era perdido nessas duas situações. Estes testes exercitam o fluxo
// completo (EntradaPage) com fornecedores reais e mocks de rede, diferente de
// FornecedoresCotacoesSection.test.tsx (que mocka estadoResolucao com vi.fn(), sem
// estado real) e ConferenciaModal.test.tsx (que testa o modal isolado).

import { Suspense } from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import EntradaPage from "@/app/cotacoes/[id]/entrada/page";
import {
  Cotacao,
  CotacaoFornecedorResponse,
  Fornecedor,
  ItemConferenciaResponse,
  PreviewRespostaResponse,
} from "@/lib/types";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
const {
  buscarCotacaoMock,
  buscarListaMock,
  buscarProdutosMock,
  listarFornecedoresMock,
  listarFornecedoresDaCotacaoMock,
  enviarRespostaMock,
  confirmarRespostaMock,
  buscarRespostaPersistidaMock,
  cancelarRespostaFornecedorMock,
} = vi.hoisted(() => ({
  buscarCotacaoMock: vi.fn(),
  buscarListaMock: vi.fn(),
  buscarProdutosMock: vi.fn(),
  listarFornecedoresMock: vi.fn(),
  listarFornecedoresDaCotacaoMock: vi.fn(),
  enviarRespostaMock: vi.fn(),
  confirmarRespostaMock: vi.fn(),
  buscarRespostaPersistidaMock: vi.fn(),
  cancelarRespostaFornecedorMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
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
    enviarResposta: enviarRespostaMock,
    confirmarResposta: confirmarRespostaMock,
    buscarRespostaPersistida: buscarRespostaPersistidaMock,
    cancelarRespostaFornecedor: cancelarRespostaFornecedorMock,
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

function makeFornecedor(overrides: Partial<Fornecedor> = {}): Fornecedor {
  return {
    id: "forn-a",
    nome: "Fornecedor A",
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

function makeCotacaoFornecedor(overrides: Partial<CotacaoFornecedorResponse> = {}): CotacaoFornecedorResponse {
  return {
    id: "cf-a",
    fornecedorId: "forn-a",
    nomeFornecedor: "Fornecedor A",
    ordem: 0,
    status: "PENDENTE",
    ...overrides,
  };
}

function makeItemRevisar(overrides: Partial<ItemConferenciaResponse> = {}): ItemConferenciaResponse {
  return {
    itemBaseId: "item-1",
    nomeItemBase: "Item Teste",
    status: "REVISAR",
    motivos: [],
    candidatos: [
      { textoOriginal: "5un item teste", marcaOferecida: null, precoInformado: 10, confiancaMatch: 0.95, semEstoque: false },
    ],
    preservado: false,
    precoAnteriorConfirmado: null,
    ...overrides,
  };
}

function makePreviewRevisar(itens: ItemConferenciaResponse[]): PreviewRespostaResponse {
  const revisar = itens.filter((i) => i.status === "REVISAR").length;
  return {
    contadores: { total: itens.length, ok: itens.length - revisar, atencao: 0, revisar },
    itens,
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

function textareaResposta() {
  return screen.getByPlaceholderText(/Sazon Legumes 60g/) as HTMLTextAreaElement;
}

function botaoProcessar() {
  return screen.getByRole("button", { name: /Processar Cotação/ });
}

beforeEach(() => {
  pushMock.mockReset();
  buscarCotacaoMock.mockReset();
  buscarListaMock.mockReset();
  buscarProdutosMock.mockReset();
  listarFornecedoresMock.mockReset();
  listarFornecedoresDaCotacaoMock.mockReset();
  enviarRespostaMock.mockReset();
  confirmarRespostaMock.mockReset();
  buscarRespostaPersistidaMock.mockReset();
  cancelarRespostaFornecedorMock.mockReset();
  buscarProdutosMock.mockResolvedValue([]);
  localStorage.setItem("cotacao.accessToken", "fake-token");
});

describe("EntradaPage — rascunho por fornecedor sobrevive à troca de fornecedor ativo", () => {
  it("resolução em andamento no Fornecedor A permanece intacta ao ir para B e voltar", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0 });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", nomeFornecedor: "Fornecedor B", ordem: 1 });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([
      makeFornecedor({ id: "forn-a", nome: "Fornecedor A" }),
      makeFornecedor({ id: "forn-b", nome: "Fornecedor B" }),
    ]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([a, b]);
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));

    await renderPage();

    await waitFor(() => expect(screen.getByText("Fornecedor 1 de 2")).toBeTruthy());

    // Cola o texto e processa a resposta do Fornecedor A — a Conferência abre sozinha.
    fireEvent.change(textareaResposta(), { target: { value: "5un item teste - R$ 10,00" } });
    fireEvent.click(botaoProcessar());

    const dialogA = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    expect(within(dialogA).getByRole("radio")).toBeTruthy();

    // Resolve o único item (REVISAR), sem clicar em "Confirmar e Processar" — a
    // resolução fica pendente, nada é persistido no backend ainda.
    fireEvent.click(within(dialogA).getByRole("radio"));
    expect(within(dialogA).getByText("Resolvido")).toBeTruthy();
    expect(confirmarRespostaMock).not.toHaveBeenCalled();

    // Navega para o Fornecedor B — o painel dele deve estar vazio (nenhum rascunho).
    fireEvent.click(screen.getByRole("button", { name: "Fornecedor B" }));
    await waitFor(() => expect(screen.getByText("Fornecedor 2 de 2")).toBeTruthy());
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(textareaResposta().value).toBe("");

    // Volta para o Fornecedor A — texto, preview e resolução continuam exatamente
    // como foram deixados (o modal reabre sozinho porque modalAberto ficou true).
    fireEvent.click(screen.getByRole("button", { name: "Fornecedor A" }));
    await waitFor(() => expect(screen.getByText("Fornecedor 1 de 2")).toBeTruthy());

    const dialogANovamente = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    expect(within(dialogANovamente).getByText("Resolvido")).toBeTruthy();
    expect(textareaResposta().value).toBe("5un item teste - R$ 10,00");
    expect(enviarRespostaMock).toHaveBeenCalledTimes(1);
  });
});

describe("EntradaPage — rascunho sobrevive ao fechar e reabrir a Conferência do mesmo fornecedor", () => {
  it('clicar em "Fechar" com uma resolução pendente e reabrir via "Conferir resposta do fornecedor" preserva o estado', async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0 });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([makeFornecedor({ id: "forn-a", nome: "Fornecedor A" })]);
    // 1ª carga (antes de processar) = PENDENTE; após enviarResposta o backend real
    // marca PROCESSADO como efeito colateral de gerarPreview (ver
    // FornecedorRespostaService.java) — recarregarFornecedoresDaCotacao já busca de
    // novo depois disso, então as chamadas seguintes simulam esse status atualizado
    // (é o que faz "Conferir resposta do fornecedor" aparecer pra reabrir).
    listarFornecedoresDaCotacaoMock.mockResolvedValueOnce([a]).mockResolvedValue([{ ...a, status: "PROCESSADO" }]);
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));

    await renderPage();

    await waitFor(() => expect(textareaResposta()).toBeTruthy());
    fireEvent.change(textareaResposta(), { target: { value: "5un item teste - R$ 10,00" } });
    fireEvent.click(botaoProcessar());

    const dialog = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    fireEvent.click(within(dialog).getByRole("radio"));
    expect(within(dialog).getByText("Resolvido")).toBeTruthy();

    // Fechar não deve limpar nada — só esconder o modal (decisão explícita da Fase 4.1).
    fireEvent.click(within(dialog).getByRole("button", { name: "Fechar" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(confirmarRespostaMock).not.toHaveBeenCalled();

    // Reabre via o botão unificado — atua sobre o fornecedor aberto (A) e reusa o
    // preview em memória (branch 1 de onConferirResposta), sem chamada de rede.
    const conferir = screen.getByRole("button", { name: /Conferir resposta do fornecedor/ });
    fireEvent.click(conferir);

    const dialogReaberto = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    expect(within(dialogReaberto).getByText("Resolvido")).toBeTruthy();
    expect(within(dialogReaberto).getByRole("radio")).toHaveProperty("checked", true);
    // O processamento não é refeito ao reabrir — o mesmo preview do rascunho é reusado.
    expect(enviarRespostaMock).toHaveBeenCalledTimes(1);
  });
});

describe("EntradaPage — fluxo de fornecedor único permanece sem regressão", () => {
  it("processar, resolver e confirmar o único fornecedor pendente navega para o comparativo", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0 });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([makeFornecedor({ id: "forn-a", nome: "Fornecedor A" })]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([a]);
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));
    confirmarRespostaMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() => expect(textareaResposta()).toBeTruthy());
    fireEvent.change(textareaResposta(), { target: { value: "5un item teste - R$ 10,00" } });
    fireEvent.click(botaoProcessar());

    const dialog = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    fireEvent.click(within(dialog).getByRole("radio"));
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirmar e Processar" }));

    await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/cotacoes/cot-1/comparativo"));
  });
});

// Achado do usuário, 2026-08-04: confirmar um fornecedor agora encadeia
// automaticamente pro próximo PROCESSADO, abrindo a Conferência dele sozinho — sem
// precisar clicar em nada. Cenário aqui simula 2 fornecedores que já chegaram
// PROCESSADO do servidor sem preview local (ex.: página recarregada, ou resposta
// persistida direto via WhatsApp) — exercita o branch 3 de onConferirResposta
// (reconstrução via GET .../resposta-persistida) pros dois, em sequência.
describe("EntradaPage — encadeamento automático entre fornecedores PROCESSADO", () => {
  it("confirmar o Fornecedor A abre a Conferência do Fornecedor B sozinha, sem clique adicional", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0, status: "PROCESSADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", nomeFornecedor: "Fornecedor B", ordem: 1, status: "PROCESSADO" });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([
      makeFornecedor({ id: "forn-a", nome: "Fornecedor A" }),
      makeFornecedor({ id: "forn-b", nome: "Fornecedor B" }),
    ]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([a, b]);
    buscarRespostaPersistidaMock.mockResolvedValue({ texto: "5un item teste - R$ 10,00" });
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));
    confirmarRespostaMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() => expect(screen.getByText("Fornecedor 1 de 2")).toBeTruthy());

    // Nenhum preview local ainda pra A — o botão unificado reconstrói do persistido.
    fireEvent.click(screen.getByRole("button", { name: /Conferir resposta do fornecedor/ }));
    const dialogA = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-a"));

    fireEvent.click(within(dialogA).getByRole("radio"));
    fireEvent.click(within(dialogA).getByRole("button", { name: "Confirmar e Processar" }));
    await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));

    // Sem nenhum clique a mais: o encadeamento automático já reconstrói e abre B.
    const dialogB = await screen.findByRole("dialog", { name: /Fornecedor B/ });
    expect(within(dialogB).getByRole("radio")).toBeTruthy();
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-b"));
    expect(pushMock).not.toHaveBeenCalled();
  });
});

// Achado do usuário, 2026-08-04: "Cancelar Conferência" não descarta só as resoluções
// — limpa a resposta do fornecedor inteira (texto colado + preview). Só é observável
// de ponta a ponta (via EntradaPage) porque ConferenciaModal.test.tsx testa o modal
// isolado com um harness que não reflete texto/preview reativamente.
describe("EntradaPage — Cancelar Conferência limpa a resposta do fornecedor", () => {
  it("após confirmar o cancelamento no aviso, apaga a resposta no backend, o textarea volta vazio e o modal fecha", async () => {
    // status muda pra PENDENTE depois de cancelarResposta (mesmo efeito colateral do
    // backend real — CotacaoFornecedorStatus volta a "ainda não respondeu") — sem
    // isso, o botão "Conferir resposta do fornecedor" (visível só quando PROCESSADO)
    // ficaria com estado desatualizado depois do recarregarFornecedoresDaCotacao.
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0, status: "PENDENTE" });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([makeFornecedor({ id: "forn-a", nome: "Fornecedor A" })]);
    listarFornecedoresDaCotacaoMock
      .mockResolvedValueOnce([a])
      .mockResolvedValueOnce([{ ...a, status: "PROCESSADO" }])
      .mockResolvedValue([{ ...a, status: "PENDENTE" }]);
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));
    cancelarRespostaFornecedorMock.mockResolvedValue(undefined);

    await renderPage();

    await waitFor(() => expect(textareaResposta()).toBeTruthy());
    fireEvent.change(textareaResposta(), { target: { value: "5un item teste - R$ 10,00" } });
    fireEvent.click(botaoProcessar());

    const dialog = await screen.findByRole("dialog", { name: /Fornecedor A/ });
    fireEvent.click(within(dialog).getByRole("radio"));
    expect(within(dialog).getByText("Resolvido")).toBeTruthy();

    // 1º clique só abre o aviso — não é mais um window.confirm nativo.
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancelar Conferência" }));
    expect(cancelarRespostaFornecedorMock).not.toHaveBeenCalled();
    const aviso = await screen.findByRole("dialog", { name: "Cancelar conferência deste fornecedor?" });
    fireEvent.click(within(aviso).getByRole("button", { name: "Cancelar conferência" }));

    await waitFor(() => expect(cancelarRespostaFornecedorMock).toHaveBeenCalledWith("cot-1", "forn-a"));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(textareaResposta().value).toBe("");
    expect(confirmarRespostaMock).not.toHaveBeenCalled();
    // Botão "Conferir resposta do fornecedor" some — status voltou pra PENDENTE, nada
    // pendente de conferir (achado do usuário: a resposta cancelada não reaparece).
    await waitFor(() => expect(screen.queryByText(/Conferir resposta do fornecedor/)).toBeNull());
  });
});
