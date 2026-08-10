// Cobre o painel de detalhe da tela de Histórico de Preços: mensagem dedicada para
// produto nunca cotado, e os 3 cards de referência (Cotação Atual / Última Cotação /
// Penúltima Cotação) sempre aparecem — mesmo quando o produto tem menos de 3 pontos de
// histórico — preenchidos com "—" em vez de omitidos.

import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import HistoricoPrecoDetalhe from "@/app/components/HistoricoPrecoDetalhe";
import { HistoricoPrecoProduto, PontoReferenciaPreco } from "@/lib/types";

function makePonto(overrides: Partial<PontoReferenciaPreco> = {}): PontoReferenciaPreco {
  return {
    cotacaoId: "cot-1",
    finalizadaEm: "2026-08-01T10:00:00Z",
    preco: 15,
    fornecedorId: "forn-1",
    nomeFornecedor: "Distribuidora Alfa",
    ...overrides,
  };
}

function makeProduto(overrides: Partial<HistoricoPrecoProduto> = {}): HistoricoPrecoProduto {
  return {
    produtoId: "prod-1",
    nomeProduto: "Arroz Especial 1kg",
    quantidadeReferencia: null,
    unidadeReferencia: null,
    pontos: [],
    ...overrides,
  };
}

describe("HistoricoPrecoDetalhe", () => {
  it("produto com 0 pontos mostra a mensagem de 'sem histórico' e não renderiza cards nem tabela", () => {
    render(<HistoricoPrecoDetalhe produto={makeProduto({ pontos: [] })} />);

    expect(screen.getByText("Sem histórico de preços para este produto.")).toBeTruthy();
    expect(screen.queryByText("Cotação Atual")).toBeNull();
    expect(screen.queryByText("Histórico de Referências")).toBeNull();
  });

  it("produto com 3+ pontos mostra os 3 cards de referência e a tabela de histórico", () => {
    const produto = makeProduto({
      pontos: [
        makePonto({ cotacaoId: "cot-1", preco: 20, nomeFornecedor: "Fornecedor Atual" }),
        makePonto({ cotacaoId: "cot-2", preco: 18, nomeFornecedor: "Fornecedor Anterior" }),
        makePonto({ cotacaoId: "cot-3", preco: 15, nomeFornecedor: "Fornecedor Mais Antigo" }),
      ],
    });
    render(<HistoricoPrecoDetalhe produto={produto} />);

    expect(screen.getByText("Cotação Atual")).toBeTruthy();
    expect(screen.getByText("Última Cotação")).toBeTruthy();
    expect(screen.getByText("Penúltima Cotação")).toBeTruthy();
    expect(screen.getByText("Histórico de Referências")).toBeTruthy();
  });

  it("produto com 1 ponto só mostra o badge 'Novo' (sem referência anterior pra classificar tendência)", () => {
    const produto = makeProduto({
      quantidadeReferencia: 10,
      unidadeReferencia: "un",
      pontos: [makePonto({ cotacaoId: "cot-1", preco: 15, nomeFornecedor: "Fornecedor Único" })],
    });
    render(<HistoricoPrecoDetalhe produto={produto} />);

    expect(screen.getByText("Novo")).toBeTruthy();

    // Os 3 cards existem mesmo havendo só 1 ponto — nenhum é omitido.
    expect(screen.getByText("Cotação Atual")).toBeTruthy();
    expect(screen.getByText("Última Cotação")).toBeTruthy();
    expect(screen.getByText("Penúltima Cotação")).toBeTruthy();

    // Card com dado real mostra o fornecedor (aparece no card e na linha da tabela).
    expect(screen.getAllByText("Fornecedor Único").length).toBeGreaterThanOrEqual(2);

    // 2 cards sem ponto (Última/Penúltima) mostram "—" no valor, mais a célula de
    // variação da única linha da tabela (sem referência anterior pra comparar) — 3
    // ocorrências exatas de "—" no total.
    expect(screen.getAllByText("—")).toHaveLength(3);
  });
});
