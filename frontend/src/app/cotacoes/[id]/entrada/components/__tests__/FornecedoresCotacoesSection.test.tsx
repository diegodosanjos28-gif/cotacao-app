// Prompt 26: FornecedoresCotacoesSection virou passo-2-only (dados cadastrais do
// fornecedor + colar/processar texto). Perdeu por completo a renderização da
// Conferência (onConferirResposta, onCancelarConferencia, preview, modalAberto,
// onClosePreview, estadoResolucao, onEstadoResolucaoChange, confirmarResposta) — esse
// fluxo inteiro (resolver itens, confirmar, encadear pro próximo fornecedor, navegar
// pro Comparativo) migrou para ConferenciaPanel.tsx, no passo 3 dedicado. O botão
// "Conferir resposta do fornecedor" foi removido de vez (feedback pós-review,
// 2026-08-16) — "Processar Resposta Cotação" já entrega direto no passo 3 agora (ver
// entrada/page.tsx#onProcessar).
//
// Passo 2 e passo 3 ficam sempre montados ao mesmo tempo (só escondidos via classe) —
// a prop `ativo` existe justamente para que só o passo visível escreva no
// fornecedorAtivo compartilhado da página (achado do usuário, 2026-08-16: os dois
// brigando pelo mesmo estado deixava a Conferência "Carregando..." pra sempre).
//
// Os testes abaixo cobrem o que sobrou aqui: banner WhatsApp, navegação sequencial
// entre fornecedores adicionados, onAtivoAlterado (incluindo a guarda `ativo`), e
// repasse de texto controlado. A cobertura de resolver/confirmar/encadear/navegar pro
// Comparativo vive em ConferenciaPanel (e no fluxo completo via EntradaPage).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import FornecedoresCotacoesSection from "@/app/cotacoes/[id]/entrada/components/FornecedoresCotacoesSection";
import { Cotacao, CotacaoFornecedorResponse, Fornecedor } from "@/lib/types";

const { atualizarFornecedorMock, criarFornecedorMock, adicionarFornecedorNaCotacaoMock } = vi.hoisted(() => ({
  atualizarFornecedorMock: vi.fn(),
  criarFornecedorMock: vi.fn(),
  adicionarFornecedorNaCotacaoMock: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
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

const BANNER = "Nenhuma resposta de fornecedor via WhatsApp recebida ainda.";

function renderSection(overrides: {
  cotacao?: Cotacao;
  cotacaoId?: string;
  cotacaoFornecedores?: CotacaoFornecedorResponse[];
  todosFornecedores?: Fornecedor[];
  onCotacaoFornecedoresAtualizados?: () => void;
  onAtivoAlterado?: (cf: CotacaoFornecedorResponse | null) => void;
  texto?: string;
  ativo?: boolean;
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
      texto={overrides.texto ?? ""}
      setTexto={vi.fn()}
      ativo={overrides.ativo ?? true}
      setErro={vi.fn()}
    />,
  );
}

beforeEach(() => {
  atualizarFornecedorMock.mockReset();
  criarFornecedorMock.mockReset();
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

describe("FornecedoresCotacoesSection — navegação sequencial", () => {
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

  it("repassa texto (controlado pelo pai) para o painel do fornecedor ativo", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A" });
    renderSection({ cotacaoFornecedores: [a], texto: "5un item teste - R$ 10,00" });

    expect((screen.getByPlaceholderText(/Sazon Legumes 60g/) as HTMLTextAreaElement).value).toBe(
      "5un item teste - R$ 10,00",
    );
  });
});

// A prop `ativo` guarda o efeito que escreve em onAtivoAlterado — com o passo 2 e o
// passo 3 (ConferenciaPanel) sempre montados ao mesmo tempo, os dois chamariam
// onAtivoAlterado com o próprio `atual` sem essa guarda, entrando num ping-pong de
// re-renders (achado do usuário, 2026-08-16: Conferência ficava "Carregando..." pra
// sempre, com chamadas de rede duplicadas). Só o passo visível pode escrever.
describe("FornecedoresCotacoesSection — guarda `ativo` do efeito onAtivoAlterado", () => {
  it("notifica onAtivoAlterado com o fornecedor ativo quando `ativo=true`", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A" });
    const onAtivoAlterado = vi.fn();
    renderSection({ cotacaoFornecedores: [a], onAtivoAlterado, ativo: true });

    expect(onAtivoAlterado).toHaveBeenCalledWith(expect.objectContaining({ id: "cf-a" }));
  });

  it("notifica onAtivoAlterado com null quando não há fornecedores (modo adicionar), com `ativo=true`", () => {
    const onAtivoAlterado = vi.fn();
    renderSection({ cotacaoFornecedores: [], onAtivoAlterado, ativo: true });

    expect(onAtivoAlterado).toHaveBeenCalledWith(null);
  });

  it("não chama onAtivoAlterado quando `ativo=false` (este passo está escondido atrás de outro)", () => {
    const a = makeCotacaoFornecedor({ id: "cf-a", fornecedorId: "forn-a", ordem: 0, nomeFornecedor: "A" });
    const onAtivoAlterado = vi.fn();
    renderSection({ cotacaoFornecedores: [a], onAtivoAlterado, ativo: false });

    expect(onAtivoAlterado).not.toHaveBeenCalled();
  });
});
