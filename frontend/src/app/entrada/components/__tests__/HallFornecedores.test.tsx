// HallFornecedores — os 2 modos completos (título, contagem, fetch/erro/vazio) por
// cima de HallFornecedorCard (já coberto isoladamente em HallFornecedorCard.test.tsx).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import HallFornecedores from "../HallFornecedores";
import { CotacaoAtualResponse, FornecedorHistoricoResponse, FornecedorRespostaResumo } from "@/lib/types";

const { fornecedoresHistoricoMock } = vi.hoisted(() => ({ fornecedoresHistoricoMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, fornecedoresHistorico: fornecedoresHistoricoMock };
});

function makeFornecedor(overrides: Partial<FornecedorRespostaResumo> = {}): FornecedorRespostaResumo {
  return { fornecedorId: "f-1", nome: "Atacadão Silva", status: "PENDENTE", respondeuEm: null, itensCotados: 0, ...overrides };
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

function makeHistorico(overrides: Partial<FornecedorHistoricoResponse> = {}): FornecedorHistoricoResponse {
  return {
    fornecedorId: "f-1",
    nome: "Atacadão Silva",
    cotacoesParticipadas: 5,
    coberturaMediaPct: 82,
    tempoRespostaMedioMinutos: 45,
    selo: "AGIL",
    ...overrides,
  };
}

beforeEach(() => {
  fornecedoresHistoricoMock.mockReset();
});

describe("HallFornecedores — modo ativa (cotacaoAtual preenchido)", () => {
  it("título 'Fornecedores na concorrência', contagem no badge e resumo respondidos/pendentes", () => {
    const cotacaoAtual = makeCotacaoAtual({
      fornecedores: [
        makeFornecedor({ fornecedorId: "f-1", status: "PROCESSADO" }),
        makeFornecedor({ fornecedorId: "f-2", status: "PENDENTE" }),
      ],
    });

    render(<HallFornecedores cotacaoAtual={cotacaoAtual} />);

    expect(screen.getByText("Fornecedores na concorrência")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("1 responderam · 1 pendente", { exact: false })).toBeTruthy();
    expect(fornecedoresHistoricoMock).not.toHaveBeenCalled();
  });

  it("sem nenhum fornecedor mostra a mensagem de lista vazia", () => {
    render(<HallFornecedores cotacaoAtual={makeCotacaoAtual({ fornecedores: [] })} />);

    expect(screen.getByText("Nenhum fornecedor foi adicionado a esta cotação ainda.")).toBeTruthy();
  });
});

describe("HallFornecedores — modo histórico (cotacaoAtual === null)", () => {
  it("busca fornecedoresHistorico e mostra título/contagem/cards", async () => {
    fornecedoresHistoricoMock.mockResolvedValue([makeHistorico({ fornecedorId: "f-1" }), makeHistorico({ fornecedorId: "f-2" })]);

    render(<HallFornecedores cotacaoAtual={null} />);

    expect(screen.getByText("Carregando...")).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Fornecedores cadastrados")).toBeTruthy());
    expect(screen.getByText("2")).toBeTruthy();
    expect(screen.getByText("Histórico completo · todas as cotações")).toBeTruthy();
  });

  it("lista vazia mostra mensagem dedicada", async () => {
    fornecedoresHistoricoMock.mockResolvedValue([]);

    render(<HallFornecedores cotacaoAtual={null} />);

    await waitFor(() => expect(screen.getByText("Nenhum fornecedor cadastrado ainda.")).toBeTruthy());
  });

  it("erro na busca mostra mensagem de erro em vez de travar em 'Carregando...'", async () => {
    fornecedoresHistoricoMock.mockRejectedValue(new Error("falhou"));

    render(<HallFornecedores cotacaoAtual={null} />);

    await waitFor(() => expect(screen.getByText("Não foi possível carregar o histórico de fornecedores.")).toBeTruthy());
  });
});
