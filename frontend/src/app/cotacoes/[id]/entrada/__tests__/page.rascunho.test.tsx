// Fase C/D do refactor da Entrada de Dados (2026-08-20): a Conferência deixou de ser
// um 3º passo da timeline (EntradaStepper) — virou o AprovacaoModal (aba "Conferência
// das Cotações"), acionado pelo botão "Revisar e aprovar" do rodapé (ou
// automaticamente, já na aba certa e com o preview semeado, quando "Processar
// Resposta Cotação" tem sucesso). O rascunho por fornecedor (texto colado, preview
// processado, resoluções/spin-offs/exclusões em andamento) migrou de page.tsx
// (`rascunhos`/`RascunhoFornecedor`) pro próprio AprovacaoModal — page.tsx só guarda
// mais o texto digitado no Passo 2 (`textosResposta`).
//
// Diferenças relevantes desta versão em relação à era do painel inline
// (ConferenciaPanel, removido nesta mesma leva):
//   - Não existe mais navegação por "passo" pra chegar na Conferência — é preciso
//     abrir o modal ("Revisar e aprovar") e, se necessário, clicar na aba 2.
//   - FornecedoresCotacoesSection (Passo 2, sempre montado) e o
//     ConferenciaFornecedoresTab (dentro do modal, quando aberto) podem ter pills com
//     o mesmo nome de fornecedor ao mesmo tempo — a lista do modal é sempre a que
//     aparece por último no documento (o modal é renderizado depois de `<main>`), daí
//     `pillDoModal` pegar a última ocorrência.

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
  finalizarCotacaoMock,
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
  finalizarCotacaoMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
  usePathname: () => "/cotacoes/cot-1/entrada",
  useSearchParams: () => new URLSearchParams(),
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
    finalizarCotacao: finalizarCotacaoMock,
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

// Abre o AprovacaoModal pelo rodapé e vai direto pra aba "Conferência das Cotações" —
// caminho usado pelos cenários em que o fornecedor já chega PROCESSADO/CONFIRMADO do
// servidor (ex.: página recarregada), sem passar por "Processar Resposta Cotação"
// nesta sessão (que abriria o modal direto nessa aba sozinho).
function abrirAbaFornecedoresDoModal() {
  fireEvent.click(screen.getByRole("button", { name: "Revisar e aprovar" }));
  fireEvent.click(screen.getByRole("button", { name: "Conferência das Cotações" }));
}

// FornecedoresCotacoesSection (Passo 2, sempre montado) e ConferenciaFornecedoresTab
// (dentro do modal, quando aberto) podem ter pills com o mesmo nome de fornecedor ao
// mesmo tempo — o modal é renderizado depois de `<main>` no documento, então pega
// sempre a última ocorrência.
function pillDoModal(nome: string): HTMLElement {
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
  finalizarCotacaoMock.mockReset();
  buscarProdutosMock.mockResolvedValue([]);
});

describe("EntradaPage — rascunho por fornecedor sobrevive à troca de fornecedor dentro do AprovacaoModal", () => {
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
    // Nenhum rascunho local ainda para A/B (ex.: página recarregada) — a aba
    // reconstrói via texto persistido (branch 3 de onConferirResposta).
    buscarRespostaPersistidaMock.mockResolvedValue({ texto: "5un item teste - R$ 10,00" });
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));

    await renderPage();
    await waitFor(() => expect(screen.getByText("Cotação teste")).toBeTruthy());
    abrirAbaFornecedoresDoModal();

    // A aba seleciona o primeiro pendente (A) sozinha.
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-a"));

    fireEvent.click(screen.getByRole("radio"));
    expect(screen.getByText("Resolvido")).toBeTruthy();
    expect(confirmarRespostaMock).not.toHaveBeenCalled();

    // Navega para o Fornecedor B pela lista de pills da própria aba.
    fireEvent.click(pillDoModal("Fornecedor B"));
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor B")).toBeTruthy());
    await waitFor(() => expect(buscarRespostaPersistidaMock).toHaveBeenCalledWith("cot-1", "forn-b"));
    // B nunca foi resolvido — nenhum "Resolvido" na tela dele.
    expect(screen.queryByText("Resolvido")).toBeNull();

    // Volta para o Fornecedor A — a resolução continua exatamente como foi deixada, e
    // o preview não é refeito (reusa o rascunho já em memória do modal).
    fireEvent.click(pillDoModal("Fornecedor A"));
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    expect(screen.getByText("Resolvido")).toBeTruthy();
    expect(enviarRespostaMock).toHaveBeenCalledTimes(2); // uma vez por fornecedor, nunca refeito.
  });
});

describe("EntradaPage — fluxo de fornecedor único permanece sem regressão", () => {
  it("processar abre o AprovacaoModal direto na aba de Fornecedores, e resolver + confirmar navega para o comparativo", async () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", nomeFornecedor: "Fornecedor A", ordem: 0 });
    buscarCotacaoMock.mockResolvedValue(makeCotacao());
    buscarListaMock.mockResolvedValue([]);
    listarFornecedoresMock.mockResolvedValue([makeFornecedor({ id: "forn-a", nome: "Fornecedor A" })]);
    // 1ª carga = PENDENTE; após enviarResposta o backend real marca PROCESSADO como
    // efeito colateral (recarregarFornecedoresDaCotacao já busca de novo); após
    // confirmarResposta, CONFIRMADO.
    listarFornecedoresDaCotacaoMock
      .mockResolvedValueOnce([a])
      .mockResolvedValueOnce([{ ...a, status: "PROCESSADO" }])
      .mockResolvedValue([{ ...a, status: "CONFIRMADO" }]);
    enviarRespostaMock.mockResolvedValue(makePreviewRevisar([makeItemRevisar()]));
    confirmarRespostaMock.mockResolvedValue([]);
    finalizarCotacaoMock.mockResolvedValue(makeCotacao({ status: "FINALIZADA" }));

    await renderPage();

    await waitFor(() => expect(textareaResposta()).toBeTruthy());
    fireEvent.change(textareaResposta(), { target: { value: "5un item teste - R$ 10,00" } });
    fireEvent.click(botaoProcessar());

    // "Processar Resposta Cotação" abre o modal direto na aba 2, no fornecedor
    // recém-processado, com o preview já semeado (sem refazer a chamada de rede).
    await waitFor(() => expect(screen.getByText("Conferência do Fornecedor — Fornecedor A")).toBeTruthy());
    expect(enviarRespostaMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("radio"));
    fireEvent.click(botaoConfirmar());

    await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));
    // Confirmar o único pendente NÃO navega mais sozinho (Fase C: fica na aba
    // mostrando o rodapé com "Lançar" habilitado) — a navegação só acontece depois de
    // "Lançar para Comparativo e Mapa de Compra".
    await waitFor(() =>
      expect((screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }) as HTMLButtonElement).disabled).toBe(false),
    );
    fireEvent.click(screen.getByRole("button", { name: /Lançar para Comparativo e Mapa de Compra/ }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/cotacoes/cot-1/comparativo"), { timeout: 4000 });
    expect(enviarRespostaMock).toHaveBeenCalledTimes(1);
  });
});

// Achado do usuário, 2026-08-04: confirmar um fornecedor encadeia automaticamente pro
// próximo PROCESSADO, abrindo a Conferência dele sozinho — sem precisar de clique
// adicional. Cenário aqui simula 2 fornecedores que já chegaram PROCESSADO do servidor
// sem preview local (ex.: página recarregada), exercitando o branch 3 de
// onConferirResposta pros dois, em sequência.
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
    await waitFor(() => expect(screen.getByText("Cotação teste")).toBeTruthy());
    abrirAbaFornecedoresDoModal();

    // Pousa no primeiro pendente (A) — sem clique nenhum.
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
// status pra PENDENTE. Também limpa o texto digitado do Passo 2 (agora fora do modal).
describe("EntradaPage — Cancelar Conferência limpa a resposta do fornecedor", () => {
  it("após confirmar o cancelamento no aviso, apaga a resposta no backend, o textarea volta vazio e a aba mostra 'ainda não respondeu'", async () => {
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
        screen.getByText("Este fornecedor ainda não respondeu — volte ao Passo 2 pra colar a resposta dele."),
      ).toBeTruthy(),
    );
    expect(textareaResposta().value).toBe("");
    expect(confirmarRespostaMock).not.toHaveBeenCalled();
  });
});
