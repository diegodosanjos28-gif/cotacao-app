// Cobre CotacaoResumoExpandido, extraído de CotacaoLinha ao migrar o Dashboard para o
// DataGrid compartilhado: agora é usado como renderRowDetail da tabela "Todas as
// cotações", e não mais dono do seu próprio <tr> expansível (isso vira responsabilidade
// do DataGrid). Aqui só o conteúdo: estado de carregamento, estado vazio, e o resumo
// (economia/produtos cotados/fornecedor competitivo) com o link pra abrir a cotação.

import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import CotacaoResumoExpandido from "@/app/components/CotacaoResumoExpandido";
import { Cotacao, ComparativoItemResponse, PrecoFornecedor } from "@/lib/types";

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

function makeOferta(overrides: Partial<PrecoFornecedor> = {}): PrecoFornecedor {
  return {
    fornecedorId: "forn-1",
    nomeFornecedor: "Distribuidora Alfa",
    precoInformado: 10,
    precoUnitarioCalculado: 10,
    semEstoque: false,
    status: "OK",
    divergenciaComparativa: false,
    ...overrides,
  };
}

function makeItem(overrides: Partial<ComparativoItemResponse> = {}): ComparativoItemResponse {
  return {
    cotacaoProdutoId: "item-1",
    produtoId: "prod-1",
    nomeProduto: "Arroz 5kg",
    quantidade: 2,
    unidade: "un",
    precosPorFornecedor: [makeOferta()],
    ...overrides,
  };
}

describe("CotacaoResumoExpandido — carregamento", () => {
  it("mostra 'Carregando resumo...' quando itens ainda é undefined", () => {
    render(<CotacaoResumoExpandido cotacao={makeCotacao()} itens={undefined} />);

    expect(screen.getByText("Carregando resumo...")).toBeTruthy();
  });
});

describe("CotacaoResumoExpandido — cotação sem produtos", () => {
  it("mostra 'Nenhum produto adicionado a esta cotação ainda.' quando itens é []", () => {
    render(<CotacaoResumoExpandido cotacao={makeCotacao()} itens={[]} />);

    expect(screen.getByText("Nenhum produto adicionado a esta cotação ainda.")).toBeTruthy();
  });
});

describe("CotacaoResumoExpandido — resumo populado", () => {
  it("mostra economia potencial, produtos cotados e fornecedor mais competitivo", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        quantidade: 2,
        precosPorFornecedor: [
          makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Distribuidora Alfa", precoUnitarioCalculado: 10 }),
          makeOferta({ fornecedorId: "forn-2", nomeFornecedor: "Beta", precoUnitarioCalculado: 15 }),
        ],
      }),
    ];
    render(<CotacaoResumoExpandido cotacao={makeCotacao()} itens={itens} />);

    // Economia = (15 - 10) * 2 = R$ 10,00; único item cotado (1/1); vencedor é o menor preço.
    expect(screen.getByText("R$ 10,00")).toBeTruthy();
    expect(screen.getByText("1/1")).toBeTruthy();
    expect(screen.getByText("Distribuidora Alfa")).toBeTruthy();
  });

  it("mostra '—' como fornecedor mais competitivo quando nenhum item tem oferta válida", () => {
    const itens = [
      makeItem({
        precosPorFornecedor: [makeOferta({ semEstoque: true })],
      }),
    ];
    render(<CotacaoResumoExpandido cotacao={makeCotacao()} itens={itens} />);

    expect(screen.getByText("—")).toBeTruthy();
  });
});

describe("CotacaoResumoExpandido — navegação", () => {
  it("é um link para /cotacoes/{id}/entrada, independente do estado (carregando/vazio/populado)", () => {
    const { rerender } = render(<CotacaoResumoExpandido cotacao={makeCotacao({ id: "cot-42" })} itens={undefined} />);
    expect(screen.getByRole("link").getAttribute("href")).toBe("/cotacoes/cot-42/entrada");

    rerender(<CotacaoResumoExpandido cotacao={makeCotacao({ id: "cot-42" })} itens={[]} />);
    expect(screen.getByRole("link").getAttribute("href")).toBe("/cotacoes/cot-42/entrada");

    rerender(<CotacaoResumoExpandido cotacao={makeCotacao({ id: "cot-42" })} itens={[makeItem()]} />);
    expect(screen.getByRole("link").getAttribute("href")).toBe("/cotacoes/cot-42/entrada");
  });
});
