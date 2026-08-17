// Prompt 26: visão somente-leitura de um fornecedor CONFIRMADO no passo 3 da Entrada
// de Dados. Diferente de ConferenciaPendente, não tem nenhuma prop de mutação — busca
// os itens já confirmados via GET .../conferencia-confirmada e só renderiza uma
// tabela simples (sem severidade, sem ação nenhuma).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import ConferenciaConfirmadaReadOnly from "@/app/cotacoes/[id]/entrada/components/ConferenciaConfirmadaReadOnly";
import { ItemRespostaResponse } from "@/lib/types";

const { buscarConferenciaConfirmadaMock } = vi.hoisted(() => ({
  buscarConferenciaConfirmadaMock: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    buscarConferenciaConfirmada: buscarConferenciaConfirmadaMock,
  };
});

function makeItemResposta(overrides: Partial<ItemRespostaResponse> = {}): ItemRespostaResponse {
  return {
    id: "item-resp-1",
    textoOriginal: "15un sazon legumes 60g - R$ 12,50",
    nomeProduto: "Sazon Legumes 60g",
    precoInformado: 12.5,
    precoUnitarioCalculado: 12.5,
    semEstoque: false,
    confiancaMatch: 0.95,
    status: "OK",
    ...overrides,
  };
}

beforeEach(() => {
  buscarConferenciaConfirmadaMock.mockReset();
});

describe("ConferenciaConfirmadaReadOnly — carregamento", () => {
  it("busca a conferência confirmada do fornecedor ao montar", () => {
    buscarConferenciaConfirmadaMock.mockResolvedValue([]);
    render(<ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-1" fornecedorNome="Fornecedor Teste" />);

    expect(buscarConferenciaConfirmadaMock).toHaveBeenCalledWith("cot-1", "forn-1");
    expect(screen.getByText("Carregando...")).toBeTruthy();
  });

  it("refaz a busca quando cotacaoId/fornecedorId mudam (troca de fornecedor no painel)", async () => {
    buscarConferenciaConfirmadaMock.mockResolvedValue([]);
    const { rerender } = render(
      <ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-1" fornecedorNome="Fornecedor A" />,
    );
    await waitFor(() => expect(buscarConferenciaConfirmadaMock).toHaveBeenCalledTimes(1));

    rerender(<ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-2" fornecedorNome="Fornecedor B" />);

    await waitFor(() => expect(buscarConferenciaConfirmadaMock).toHaveBeenCalledTimes(2));
    expect(buscarConferenciaConfirmadaMock).toHaveBeenLastCalledWith("cot-1", "forn-2");
  });
});

describe("ConferenciaConfirmadaReadOnly — tabela de itens confirmados", () => {
  it("renderiza uma linha por item confirmado com nome, resposta, preço e status", async () => {
    buscarConferenciaConfirmadaMock.mockResolvedValue([
      makeItemResposta({ nomeProduto: "Sazon Legumes 60g", textoOriginal: "15un sazon legumes 60g - R$ 12,50", precoUnitarioCalculado: 12.5, status: "OK" }),
    ]);

    render(<ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-1" fornecedorNome="Fornecedor Teste" />);

    await waitFor(() => expect(screen.getByText("Sazon Legumes 60g")).toBeTruthy());
    expect(screen.getByText("15un sazon legumes 60g - R$ 12,50")).toBeTruthy();
    expect(screen.getByText("R$ 12,50")).toBeTruthy();
    expect(screen.getByText("1 item(ns) confirmados nesta cotação.")).toBeTruthy();
  });

  it("item sem estoque mostra 'Sem estoque' em vez do preço", async () => {
    buscarConferenciaConfirmadaMock.mockResolvedValue([makeItemResposta({ semEstoque: true })]);

    render(<ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-1" fornecedorNome="Fornecedor Teste" />);

    await waitFor(() => expect(screen.getByText("Sem estoque")).toBeTruthy());
    expect(screen.queryByText("R$ 12,50")).toBeNull();
  });

  it("lista vazia mostra mensagem de nenhum item confirmado", async () => {
    buscarConferenciaConfirmadaMock.mockResolvedValue([]);

    render(<ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-1" fornecedorNome="Fornecedor Teste" />);

    await waitFor(() => expect(screen.getByText("Nenhum item confirmado para este fornecedor.")).toBeTruthy());
    expect(screen.getByText("0 item(ns) confirmados nesta cotação.")).toBeTruthy();
  });
});

describe("ConferenciaConfirmadaReadOnly — erro", () => {
  it("mostra a mensagem de erro quando a busca falha, sem quebrar a tela", async () => {
    buscarConferenciaConfirmadaMock.mockRejectedValue(new Error("falhou"));

    render(<ConferenciaConfirmadaReadOnly cotacaoId="cot-1" fornecedorId="forn-1" fornecedorNome="Fornecedor Teste" />);

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect(screen.getByRole("alert").textContent).toContain("Não foi possível carregar a conferência confirmada.");
  });
});
