// CotacaoAtualCard — estado vazio (sem cotação em andamento) vs card com contagem
// de fornecedores respondidos/total e os callbacks de ação.

import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import CotacaoAtualCard from "../CotacaoAtualCard";
import { CotacaoAtualResponse, FornecedorRespostaResumo } from "@/lib/types";

function makeFornecedor(overrides: Partial<FornecedorRespostaResumo> = {}): FornecedorRespostaResumo {
  return {
    fornecedorId: "forn-1",
    nome: "Atacadão Silva",
    status: "PENDENTE",
    respondeuEm: null,
    itensCotados: 0,
    ...overrides,
  };
}

function makeCotacaoAtual(overrides: Partial<CotacaoAtualResponse> = {}): CotacaoAtualResponse {
  return {
    id: "cot-1",
    titulo: "Cotação Agosto",
    canalOrigem: "WHATSAPP",
    ultimaAtividadeEm: "2026-08-20T10:00:00Z",
    criadoEm: "2026-08-20T09:00:00Z",
    itensListaBase: 20,
    itensCotados: 15,
    fornecedores: [],
    ...overrides,
  };
}

describe("CotacaoAtualCard — estado vazio (cotacaoAtual === null)", () => {
  it("mostra o texto de estado vazio e o botão 'Criar cotação pela web'", () => {
    render(
      <CotacaoAtualCard cotacaoAtual={null} onRevisarEAprovar={vi.fn()} onDetalhes={vi.fn()} onCriarCotacao={vi.fn()} />,
    );

    expect(screen.getByText("Sem cotações no momento")).toBeTruthy();
    expect(screen.getByText("Criar cotação pela web")).toBeTruthy();
  });

  it("clicar em 'Criar cotação pela web' chama onCriarCotacao", () => {
    const onCriarCotacao = vi.fn();
    render(
      <CotacaoAtualCard
        cotacaoAtual={null}
        onRevisarEAprovar={vi.fn()}
        onDetalhes={vi.fn()}
        onCriarCotacao={onCriarCotacao}
      />,
    );

    fireEvent.click(screen.getByText("Criar cotação pela web"));

    expect(onCriarCotacao).toHaveBeenCalledTimes(1);
  });
});

describe("CotacaoAtualCard — com cotação em andamento", () => {
  it("conta fornecedores respondidos (status !== PENDENTE) sobre o total", () => {
    const cotacaoAtual = makeCotacaoAtual({
      fornecedores: [
        makeFornecedor({ fornecedorId: "f-1", nome: "Atacadão Silva", status: "PROCESSADO" }),
        makeFornecedor({ fornecedorId: "f-2", nome: "Distribuidora Norte", status: "CONFIRMADO" }),
        makeFornecedor({ fornecedorId: "f-3", nome: "Comercial Boa Vista", status: "PENDENTE" }),
      ],
    });

    render(
      <CotacaoAtualCard cotacaoAtual={cotacaoAtual} onRevisarEAprovar={vi.fn()} onDetalhes={vi.fn()} onCriarCotacao={vi.fn()} />,
    );

    // "2" respondidos de "/ 3" total.
    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("/ 3")).toBeTruthy();
    expect(screen.getByText("falta 1 fornecedor responder", { exact: false })).toBeTruthy();
  });

  it("nenhum fornecedor respondeu ainda — sem faixa de avatares, sem menção a 'faltam'", () => {
    const cotacaoAtual = makeCotacaoAtual({
      fornecedores: [makeFornecedor({ fornecedorId: "f-1", status: "PENDENTE" })],
    });

    render(
      <CotacaoAtualCard cotacaoAtual={cotacaoAtual} onRevisarEAprovar={vi.fn()} onDetalhes={vi.fn()} onCriarCotacao={vi.fn()} />,
    );

    expect(screen.getByText("0")).toBeTruthy();
    expect(screen.queryByText("responderam esta cotação")).toBeNull();
  });

  it("renderiza os pills de itensListaBase e itensCotados", () => {
    const cotacaoAtual = makeCotacaoAtual({ itensListaBase: 30, itensCotados: 22, fornecedores: [] });

    render(
      <CotacaoAtualCard cotacaoAtual={cotacaoAtual} onRevisarEAprovar={vi.fn()} onDetalhes={vi.fn()} onCriarCotacao={vi.fn()} />,
    );

    expect(screen.getByText("30 itens na lista base")).toBeTruthy();
    expect(screen.getByText("22 itens cotados")).toBeTruthy();
  });

  it("clicar em 'Revisar e aprovar' chama onRevisarEAprovar", () => {
    const onRevisarEAprovar = vi.fn();
    render(
      <CotacaoAtualCard
        cotacaoAtual={makeCotacaoAtual()}
        onRevisarEAprovar={onRevisarEAprovar}
        onDetalhes={vi.fn()}
        onCriarCotacao={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText("Revisar e aprovar"));

    expect(onRevisarEAprovar).toHaveBeenCalledTimes(1);
  });

  it("clicar em 'Detalhes' chama onDetalhes, sem disparar onRevisarEAprovar", () => {
    const onDetalhes = vi.fn();
    const onRevisarEAprovar = vi.fn();
    render(
      <CotacaoAtualCard
        cotacaoAtual={makeCotacaoAtual()}
        onRevisarEAprovar={onRevisarEAprovar}
        onDetalhes={onDetalhes}
        onCriarCotacao={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText("Detalhes"));

    expect(onDetalhes).toHaveBeenCalledTimes(1);
    expect(onRevisarEAprovar).not.toHaveBeenCalled();
  });

  it("mostra o canal de origem no título (WhatsApp AI vs Web)", () => {
    const { rerender } = render(
      <CotacaoAtualCard
        cotacaoAtual={makeCotacaoAtual({ canalOrigem: "WHATSAPP" })}
        onRevisarEAprovar={vi.fn()}
        onDetalhes={vi.fn()}
        onCriarCotacao={vi.fn()}
      />,
    );
    expect(screen.getByText("Nova cotação · WhatsApp AI", { exact: false })).toBeTruthy();

    rerender(
      <CotacaoAtualCard
        cotacaoAtual={makeCotacaoAtual({ canalOrigem: "WEB" })}
        onRevisarEAprovar={vi.fn()}
        onDetalhes={vi.fn()}
        onCriarCotacao={vi.fn()}
      />,
    );
    expect(screen.getByText("Nova cotação · Web", { exact: false })).toBeTruthy();
  });
});
