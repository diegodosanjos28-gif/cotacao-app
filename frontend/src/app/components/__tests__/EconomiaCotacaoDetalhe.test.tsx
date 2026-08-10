// Cobre EconomiaCotacaoDetalhe, extraído de EconomiaCotacaoLinha (Prompt 11) ao migrar
// o Dashboard para o DataGrid compartilhado: agora é usado como renderRowDetail da
// tabela de Economia de Cotações, e não mais dono do seu próprio <tr> expansível (isso
// vira responsabilidade do DataGrid — coberto em components/grid/__tests__/DataGrid.test.tsx).
// Aqui só o conteúdo: detalhamento por fornecedor + exportação em PDF.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import EconomiaCotacaoDetalhe from "@/app/components/EconomiaCotacaoDetalhe";
import { Cotacao, ComparativoItemResponse, PrecoFornecedor } from "@/lib/types";

const { exportarConferenciaNotaMock } = vi.hoisted(() => ({ exportarConferenciaNotaMock: vi.fn() }));

vi.mock("@/lib/conferenciaNotaPdf", () => ({
  exportarConferenciaNota: exportarConferenciaNotaMock,
}));

function makeCotacao(overrides: Partial<Cotacao> = {}): Cotacao {
  return {
    id: "cot-1",
    criadoPor: null,
    titulo: "Cotação teste",
    status: "FINALIZADA",
    canalOrigem: "WEB",
    listaRevisada: true,
    ultimaAtividadeEm: null,
    cenarioSelecionado: null,
    finalizadaEm: "2026-08-01T10:00:00Z",
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

beforeEach(() => {
  exportarConferenciaNotaMock.mockReset();
});

describe("EconomiaCotacaoDetalhe — sem fornecedor com oferta válida", () => {
  it("mostra a mensagem de estado vazio", () => {
    const itens = [
      makeItem({
        precosPorFornecedor: [makeOferta({ semEstoque: true })],
      }),
    ];
    render(<EconomiaCotacaoDetalhe cotacao={makeCotacao()} itens={itens} />);

    expect(screen.getByText("Nenhum fornecedor com oferta válida nesta cotação.")).toBeTruthy();
    expect(screen.queryByText("Exportar PDF")).toBeNull();
  });
});

describe("EconomiaCotacaoDetalhe — detalhamento por fornecedor", () => {
  it("renderiza um bloco por fornecedor com oferta válida; fornecedor sem oferta válida fica de fora", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        precosPorFornecedor: [
          makeOferta({ fornecedorId: "forn-1", nomeFornecedor: "Distribuidora Alfa" }),
          makeOferta({ fornecedorId: "forn-2", nomeFornecedor: "Beta Sem Oferta", semEstoque: true }),
        ],
      }),
    ];
    render(<EconomiaCotacaoDetalhe cotacao={makeCotacao()} itens={itens} />);

    expect(screen.getByText("Distribuidora Alfa")).toBeTruthy();
    expect(screen.queryByText("Beta Sem Oferta")).toBeNull();
  });

  it("renderiza a tabela de itens do fornecedor com produto, quantidade/unidade, preço unitário e total", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        nomeProduto: "Arroz 5kg",
        quantidade: 3,
        unidade: "un",
        precosPorFornecedor: [makeOferta({ precoUnitarioCalculado: 12.5 })],
      }),
    ];
    render(<EconomiaCotacaoDetalhe cotacao={makeCotacao()} itens={itens} />);

    const tabela = screen.getByRole("table");
    expect(within(tabela).getByText("Arroz 5kg")).toBeTruthy();
    expect(within(tabela).getByText("3 un")).toBeTruthy();
    // Preço unitário (R$ 12,50) e total da linha (3 × 12,50 = R$ 37,50) — ambos formatados em moeda.
    expect(within(tabela).getByText("R$ 12,50")).toBeTruthy();
    expect(within(tabela).getByText("R$ 37,50")).toBeTruthy();
  });

  it("mostra a contagem de itens e o total do fornecedor no cabeçalho do bloco", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "item-1",
        quantidade: 2,
        precosPorFornecedor: [makeOferta({ precoUnitarioCalculado: 10 })],
      }),
    ];
    render(<EconomiaCotacaoDetalhe cotacao={makeCotacao()} itens={itens} />);

    // O texto "1 item · R$ 20,00" fica quebrado em múltiplos nós de texto no mesmo
    // cabeçalho — verifica pelo textContent do bloco em vez de um match exato de nó.
    // formatarMoeda usa toLocaleString, cujo separador entre "R$" e o valor pode ser um
    // espaço não separável (NBSP) — compara por regex em vez de um literal exato.
    const cabecalho = screen.getByText("Distribuidora Alfa").closest("div")!;
    expect(cabecalho.textContent).toContain("1 item");
    expect(cabecalho.textContent).toMatch(/R\$\s*20,00/);
  });
});

describe("EconomiaCotacaoDetalhe — exportação em PDF", () => {
  it("chama exportarConferenciaNota com a cotação e os itens ao clicar em Exportar PDF", () => {
    const cotacao = makeCotacao();
    const itens = [makeItem()];
    render(<EconomiaCotacaoDetalhe cotacao={cotacao} itens={itens} />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar PDF" }));

    expect(exportarConferenciaNotaMock).toHaveBeenCalledWith(cotacao, itens);
  });
});
