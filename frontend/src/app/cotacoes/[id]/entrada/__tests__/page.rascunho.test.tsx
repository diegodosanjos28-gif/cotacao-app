// Fase 4.1: o rascunho da Conferência de um fornecedor (texto colado, preview
// processado, resoluções/spin-offs/exclusões em andamento) vive num mapa por
// cotacaoFornecedorId em page.tsx (`rascunhos`/`RascunhoFornecedor`), pra sobreviver à
// troca de fornecedor ativo dentro do painel de Conferência.
//
// Prompt 26 (painel dedicado) + feedback pós-review 2026-08-16 mudaram bastante o
// fluxo end-to-end coberto por este arquivo em relação à versão anterior (que ainda
// testava o antigo ConferenciaModal via role="dialog"):
//   - Não existe mais Modal envolvendo a Conferência — ConferenciaPendente é conteúdo
//     inline do passo 3 (EntradaStepper), sem "Fechar"/backdrop.
//   - O botão "Conferir resposta do fornecedor" foi removido: "Processar Resposta
//     Cotação" (passo 2) já entrega direto no passo 3 (setPassoAtivo(3) em
//     onProcessar), pousando no fornecedor recém-processado via
//     ConferenciaPanel.fornecedorFocoId.
//   - Passo 2 (FornecedoresCotacoesSection) e passo 3 (ConferenciaPanel) ficam SEMPRE
//     montados ao mesmo tempo (só escondidos por classe CSS, que o ambiente de teste
//     não aplica) — os dois têm sua própria lista de pills com o mesmo nome de
//     fornecedor; a prop `ativo` (guarda de qual dos dois pode escrever em
//     fornecedorAtivo) evita o ping-pong, mas as consultas de texto/role precisam
//     desambiguar entre as duas listas quando ambas têm um fornecedor com o mesmo nome
//     (ver `pillDoPainelConferencia` abaixo).

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

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return {
    ...actual,
    useAuth: () => ({ ready: true, authenticated: true, papel: "OPERADOR_CLIENTE", tenantId: "t-1" }),
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
  return screen.getByRole("button", { name: /Processar Resposta Cotação/ });
}

function botaoConfirmar() {
  return screen.getByRole("button", { name: "Confirmar e Processar" });
}

// FornecedoresCotacoesSection (passo 2) e ConferenciaPanel (passo 3) ficam sempre
// montados ao mesmo tempo (só escondidos por classe CSS que o jsdom não aplica) — os
// dois têm sua própria lista de pills de navegação com o mesmo texto de fornecedor. A
// lista do ConferenciaPanel é sempre a que aparece por último no documento (passo 3 é
// renderizado depois do passo 2 em page.tsx), então pega a última ocorrência.
function pillDoPainelConferencia(nome: string): HTMLElement {
  const pills = screen.getAllByRole("button", { name: nome });
  return pills[pills.length - 1];
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
});

describe("EntradaPage — rascunho por fornecedor sobrevive à troca de fornecedor dentro do painel de Conferência", () => {
  it("resolução em andamento no Fornecedor A permanece intacta ao ir para B e voltar", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0, status: "PROCESSADO" });
    const b = makeCotacaoFornecedor({ id: "cf-b", fornecedorId: "forn-b", nomeFornecedor: "Fornecedor B", ordem: 1, status: "PROCESSADO" });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([
      makeFornecedor({ id: "forn-a", nome: "Fornecedor A" }),
      makeFornecedor({ id: "forn-b", nome: "Fornecedor B" }),
    ]);
    listarFornecedoresDaCotacaoMock.mockResolvedValue([a, b]);
    // Nenhum rascunho local ainda para A/B (ex.: página recarregada) — o painel
    // reconstrói via texto persistido (branch 3 de onConferirResposta).
    buscarRespostaPersistidaMock.mockResolvedValue({ texto: "5un item teste - R$ 10,00" });
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));

    await renderPage();

    // cf com algum PROCESSADO faz calcularPassoInicial pousar direto no passo 3, e o
    // painel seleciona o primeiro pendente (A) sozinho.
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-a"));

    fireEvent.click(screen.getByRole("radio"));
    expect(screen.getByText("Resolvido")).toBeTruthy();
    expect(confirmarRespostaMock).not.toHaveBeenCalled();

    // Navega para o Fornecedor B pela lista de pills do próprio painel de Conferência.
    fireEvent.click(pillDoPainelConferencia("Fornecedor B"));
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor B")).toBeTruthy());
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-b"));
    // B nunca foi resolvido — nenhum "Resolvido" na tela dele.
    expect(screen.queryByText("Resolvido")).toBeNull();

    // Volta para o Fornecedor A — a resolução continua exatamente como foi deixada, e
    // o preview não é refeito (reusa o rascunho já em memória).
    fireEvent.click(pillDoPainelConferencia("Fornecedor A"));
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    expect(screen.getByText("Resolvido")).toBeTruthy();
    expect(enviarRespostaMock).toHaveBeenCalledTimes(2); // uma vez por fornecedor, nunca refeito.
  });
});

describe("EntradaPage — fluxo de fornecedor único permanece sem regressão", () => {
  it("processar entrega direto no passo 3, e resolver + confirmar o único fornecedor pendente navega para o comparativo", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0 });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([makeFornecedor({ id: "forn-a", nome: "Fornecedor A" })]);
    // 1ª carga = PENDENTE; após enviarResposta o backend real marca PROCESSADO como
    // efeito colateral (recarregarFornecedoresDaCotacao já busca de novo); após
    // confirmarResposta, CONFIRMADO — o real ConfirmacaoRespostaService.confirmar
    // sempre deixa esse rastro, e ConferenciaPanel.onFornecedorConfirmado aguarda
    // exatamente este refetch antes de decidir/navegar (ver comentário lá) — um mock
    // que nunca retorna CONFIRMADO não conseguiria validar essa espera.
    listarFornecedoresDaCotacaoMock
      .mockResolvedValueOnce([a])
      .mockResolvedValueOnce([{ ...a, status: "PROCESSADO" }])
      .mockResolvedValue([{ ...a, status: "CONFIRMADO" }]);
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));
    confirmarRespostaMock.mockResolvedValue([]);

    await renderPage();

    // cf único PENDENTE (nenhum PROCESSADO ainda) pousa no passo 2.
    await waitFor(() => expect(textareaResposta()).toBeTruthy());
    fireEvent.change(textareaResposta(), { target: { value: "5un item teste - R$ 10,00" } });
    fireEvent.click(botaoProcessar());

    // "Processar Resposta Cotação" já entrega direto no passo 3, no fornecedor
    // recém-processado — sem precisar de nenhum botão de "Conferir resposta".
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    fireEvent.click(screen.getByRole("radio"));
    fireEvent.click(botaoConfirmar());

    await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/cotacoes/cot-1/comparativo"));
    // Achado do usuário 2026-08-16, corrigido: ConferenciaPanel.onFornecedorConfirmado
    // aguarda o refetch de cotacaoFornecedores ANTES de navegar, e
    // ConferenciaPendente.confirmar() só limpa o preview em memória depois disso — sem
    // essa ordem, o efeito de auto-fetch via um instante onde o fornecedor recém-
    // confirmado ainda aparecia como PROCESSADO (refetch não tinha voltado) com preview
    // já nulo, e reprocessava a mesma resposta à toa. Exatamente 1 chamada agora.
    expect(enviarRespostaMock).toHaveBeenCalledTimes(1);
  });
});

// Achado do usuário, 2026-08-04, comportamento preservado no Prompt 26: confirmar um
// fornecedor encadeia automaticamente pro próximo PROCESSADO, abrindo a Conferência
// dele sozinho — sem precisar de clique adicional. Cenário aqui simula 2 fornecedores
// que já chegaram PROCESSADO do servidor sem preview local (ex.: página recarregada, ou
// resposta persistida direto via WhatsApp) — exercita o branch 3 de onConferirResposta
// pros dois, em sequência, sem nenhum botão de "Conferir resposta" (removido).
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

    // Landing automático no passo 3, no primeiro pendente (A) — sem clique nenhum.
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-a"));

    fireEvent.click(screen.getByRole("radio"));
    fireEvent.click(botaoConfirmar());
    await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));

    // Sem nenhum clique a mais: o encadeamento automático já reconstrói e abre B.
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor B")).toBeTruthy());
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-b"));
    expect(pushMock).not.toHaveBeenCalled();
  });
});

// Achado do usuário, 2026-08-04: "Cancelar Conferência" não descarta só as resoluções
// — limpa a resposta do fornecedor inteira (texto colado + preview), voltando o
// status pra PENDENTE.
describe("EntradaPage — Cancelar Conferência limpa a resposta do fornecedor", () => {
  it("após confirmar o cancelamento no aviso, apaga a resposta no backend, o textarea volta vazio e a Conferência mostra 'ainda não respondeu'", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0, status: "PENDENTE" });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([makeFornecedor({ id: "forn-a", nome: "Fornecedor A" })]);
    // 1ª carga (antes de processar) = PENDENTE; após enviarResposta o backend real
    // marca PROCESSADO como efeito colateral; depois de cancelar, volta a PENDENTE.
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

    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    fireEvent.click(screen.getByRole("radio"));
    expect(screen.getByText("Resolvido")).toBeTruthy();

    // 1º clique só abre o aviso — não é um window.confirm nativo.
    fireEvent.click(screen.getByRole("button", { name: "Cancelar Conferência" }));
    expect(cancelarRespostaFornecedorMock).not.toHaveBeenCalled();
    const aviso = await screen.findByRole("dialog", { name: "Cancelar conferência deste fornecedor?" });
    fireEvent.click(within(aviso).getByRole("button", { name: "Cancelar conferência" }));

    await waitFor(() => expect(cancelarRespostaFornecedorMock).toHaveBeenCalledWith("cot-1", "forn-a"));
    await waitFor(() =>
      expect(
        screen.getByText("Este fornecedor ainda não respondeu — volte ao passo 2 pra colar a resposta dele."),
      ).toBeTruthy(),
    );
    expect(textareaResposta().value).toBe("");
    expect(confirmarRespostaMock).not.toHaveBeenCalled();
  });
});
