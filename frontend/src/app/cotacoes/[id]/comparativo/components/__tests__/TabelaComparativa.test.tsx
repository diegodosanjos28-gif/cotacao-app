// Cobre a migração de TabelaComparativa de <table> manual para o DataGrid compartilhado
// (@tanstack/react-table). Foco principal: o rascunho compartilhado de Qtd+Unidade
// (rascunhos/setRascunhos, chave cotacaoProdutoId) que substituiu o antigo estado local
// por linha (LinhaComparativo) — Qtd e Unidade têm que ser enviadas juntas pro
// editarItemCotacao mesmo quando só um campo mudou, e o cenário de regressão clássico
// desse padrão é digitar uma nova Qtd (sem sair do campo) e trocar a Unidade em seguida:
// o commit da Unidade tem que carregar o valor de Qtd recém-digitado, não o original.
// Cobre também colunas somente-leitura (preço, Menor, Rec., Economia, badges), filtros
// client-side e colunas dinâmicas por fornecedor. Mesmo padrão de mock de @/lib/api usado
// em entrada/components/__tests__/LinhaGridProdutos.test.tsx (rascunho por-campo análogo
// em outra tela).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import TabelaComparativa from "@/app/cotacoes/[id]/comparativo/components/TabelaComparativa";
import { ApiError } from "@/lib/api";
import { formatarMoeda } from "@/lib/format";
import { ComparativoItemResponse, PrecoFornecedor } from "@/lib/types";

const { editarItemCotacaoMock } = vi.hoisted(() => ({ editarItemCotacaoMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    editarItemCotacao: editarItemCotacaoMock,
  };
});

function makePreco(overrides: Partial<PrecoFornecedor> = {}): PrecoFornecedor {
  return {
    fornecedorId: "forn-1",
    nomeFornecedor: "Fornecedor Alfa",
    precoInformado: 20,
    precoUnitarioCalculado: 20,
    semEstoque: false,
    status: "OK",
    divergenciaComparativa: false,
    ...overrides,
  };
}

function makeItem(overrides: Partial<ComparativoItemResponse> = {}): ComparativoItemResponse {
  return {
    cotacaoProdutoId: "cp-1",
    produtoId: "prod-1",
    nomeProduto: "Arroz 5kg",
    quantidade: 10,
    unidade: "un",
    precosPorFornecedor: [makePreco()],
    ...overrides,
  };
}

function renderTabela(itens: ComparativoItemResponse[], onItemEditado = vi.fn()) {
  render(<TabelaComparativa itens={itens} cotacaoId="cot-1" onItemEditado={onItemEditado} />);
  return { onItemEditado };
}

function tabela() {
  return screen.getByRole("table");
}

/** Células (<td>) da linha de um produto, na ordem real das colunas do componente. Escopado
 * à <table> porque "Maior variação de preço: {nome}" no rodapé pode repetir o mesmo nome de
 * produto fora da tabela. */
function cellsOfRow(nomeProduto: string) {
  const row = within(tabela()).getByText(nomeProduto).closest("tr")!;
  return within(row).getAllByRole("cell");
}

beforeEach(() => {
  editarItemCotacaoMock.mockReset();
  editarItemCotacaoMock.mockResolvedValue(undefined);
});

describe("TabelaComparativa — colunas somente leitura", () => {
  // Ordem de colunas: Produto, Qtd, Unidade, [fornecedores na ordem de 1ª aparição], Menor, Rec., Economia.
  const ITEM_ARROZ = makeItem({
    cotacaoProdutoId: "cp-1",
    nomeProduto: "Arroz 5kg",
    quantidade: 10,
    unidade: "un",
    precosPorFornecedor: [
      makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa", precoInformado: 20, precoUnitarioCalculado: 20 }),
      makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", precoInformado: 23, precoUnitarioCalculado: 23 }),
    ],
  });
  const ITEM_FEIJAO = makeItem({
    cotacaoProdutoId: "cp-2",
    nomeProduto: "Feijão 1kg",
    quantidade: 5,
    unidade: "kg",
    precosPorFornecedor: [
      makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa", semEstoque: true }),
      makePreco({
        fornecedorId: "forn-2",
        nomeFornecedor: "Fornecedor Beta",
        precoInformado: 12.5,
        precoUnitarioCalculado: 12.5,
        divergenciaComparativa: true,
      }),
    ],
  });
  const ITEM_SAL = makeItem({
    cotacaoProdutoId: "cp-3",
    nomeProduto: "Sal Grosso 1kg",
    quantidade: 3,
    unidade: "kg",
    precosPorFornecedor: [
      makePreco({
        fornecedorId: "forn-1",
        nomeFornecedor: "Fornecedor Alfa",
        precoInformado: 8,
        precoUnitarioCalculado: 8,
        status: "NAO_IDENTIFICADO",
      }),
      makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", precoInformado: 9, precoUnitarioCalculado: 9 }),
    ],
  });

  function renderLeitura() {
    return renderTabela([ITEM_ARROZ, ITEM_FEIJAO, ITEM_SAL]);
  }

  it("renderiza o nome do produto de cada linha", () => {
    renderLeitura();
    expect(within(tabela()).getByText("Arroz 5kg")).toBeTruthy();
    expect(within(tabela()).getByText("Feijão 1kg")).toBeTruthy();
    expect(within(tabela()).getByText("Sal Grosso 1kg")).toBeTruthy();
  });

  it("mostra 'sem estoque' na célula do fornecedor sem estoque, sem renderizar preço", () => {
    renderLeitura();
    const cells = cellsOfRow("Feijão 1kg");
    expect(cells[3].textContent).toBe("sem estoque");
  });

  it("destaca em verde (font-semibold text-ok) toda oferta válida da linha — bug pré-existente, não introduzido por esta migração", () => {
    renderLeitura();
    const cells = cellsOfRow("Arroz 5kg");
    // getByText normaliza espaços em branco do texto recebido (inclusive NBSP) para
    // comparar contra o literal abaixo — diferente do textContent.toBe(formatarMoeda(...))
    // usado nos outros testes desta suíte, que precisa do NBSP exato.
    const precoAlfa = within(cells[3]).getByText("R$ 20,00");
    const precoBeta = within(cells[4]).getByText("R$ 23,00");
    // BUG PRÉ-EXISTENTE (confirmado via `git show HEAD` na versão hand-rolled anterior à
    // migração para DataGrid — não foi introduzido aqui): em TabelaComparativa.tsx, `ehMenor`
    // compara `preco.fornecedorId === fid`, que é sempre true (preco já foi encontrado
    // filtrando por fid) em vez de comparar contra `menor.fornecedorId`. Resultado: toda oferta
    // válida (status OK, sem estoque=false) é destacada em verde, não só a de menor preço —
    // este teste documenta o comportamento ATUAL (Alfa e Beta ambos com text-ok), não o
    // comportamento correto, porque a migração preserva comportamento exato e não corrige bugs
    // não solicitados.
    expect(precoAlfa.className).toContain("text-ok");
    expect(precoBeta.className).toContain("text-ok");
  });

  it("mostra o badge DIVERGENCIA_COMPARATIVA com o tooltip explicativo quando o item tem divergência", () => {
    renderLeitura();
    const cells = cellsOfRow("Feijão 1kg");
    const tooltip = within(cells[4]).getByTitle(/possível preço de caixa\/fardo lançado como unitário/i);
    expect(tooltip).toBeTruthy();
    expect(within(cells[4]).getByText("Possível preço de caixa/fardo")).toBeTruthy();
  });

  it("mostra o badge de status quando o status do fornecedor não é OK", () => {
    renderLeitura();
    const cells = cellsOfRow("Sal Grosso 1kg");
    expect(within(cells[3]).getByText("Não identificado")).toBeTruthy();
  });

  it("coluna Menor mostra o menor preço válido entre os fornecedores", () => {
    renderLeitura();
    // formatarMoeda usa toLocaleString, que separa "R$" do valor com um espaço não-quebrável
    // (NBSP) — comparar contra formatarMoeda(...) em vez de um literal com espaço normal.
    expect(cellsOfRow("Arroz 5kg")[5].textContent).toBe(formatarMoeda(20));
    expect(cellsOfRow("Feijão 1kg")[5].textContent).toBe(formatarMoeda(12.5));
    expect(cellsOfRow("Sal Grosso 1kg")[5].textContent).toBe(formatarMoeda(9));
  });

  it("coluna Rec. mostra o ponto colorido e o nome do fornecedor recomendado (menor preço)", () => {
    renderLeitura();
    // Sal Grosso: a oferta do Alfa tem status NAO_IDENTIFICADO (inválida), então o
    // recomendado é o Beta mesmo ele não tendo o menor preço "bruto" na linha.
    expect(cellsOfRow("Sal Grosso 1kg")[6].textContent).toBe("Fornecedor Beta");
    expect(cellsOfRow("Arroz 5kg")[6].textContent).toBe("Fornecedor Alfa");
  });

  it("coluna Economia mostra a economia potencial formatada, ou '—' quando não há economia", () => {
    renderLeitura();
    // (23 - 20) * 10 = 30
    expect(cellsOfRow("Arroz 5kg")[7].textContent).toBe(formatarMoeda(30));
    // Só uma oferta válida em cada linha (Feijão/Sal) => menor === maior => economia 0.
    expect(cellsOfRow("Feijão 1kg")[7].textContent).toBe("—");
    expect(cellsOfRow("Sal Grosso 1kg")[7].textContent).toBe("—");
  });

  it("colunas somente leitura (Produto, preços, Menor, Rec., Economia) não têm input nem select", () => {
    renderLeitura();
    const cells = [
      ...cellsOfRow("Arroz 5kg").filter((_, i) => i !== 1 && i !== 2),
      ...cellsOfRow("Feijão 1kg").filter((_, i) => i !== 1 && i !== 2),
      ...cellsOfRow("Sal Grosso 1kg").filter((_, i) => i !== 1 && i !== 2),
    ];
    cells.forEach((cell) => {
      expect(within(cell).queryByRole("textbox")).toBeNull();
      expect(within(cell).queryByRole("spinbutton")).toBeNull();
      expect(within(cell).queryByRole("combobox")).toBeNull();
    });
  });

  it("coluna Menor mostra '—' e Rec. mostra '—' quando nenhum fornecedor tem oferta válida", () => {
    const semOferta = makeItem({
      cotacaoProdutoId: "cp-9",
      nomeProduto: "Detergente 500ml",
      precosPorFornecedor: [
        makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa", semEstoque: true }),
        makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", status: "PENDENTE_CONFIRMACAO" }),
      ],
    });
    renderTabela([semOferta]);
    const cells = cellsOfRow("Detergente 500ml");
    expect(cells[5].textContent).toBe("—");
    expect(cells[6].textContent).toBe("—");
    expect(cells[7].textContent).toBe("—");
  });
});

describe("TabelaComparativa — edição compartilhada de Qtd + Unidade (rascunho por linha)", () => {
  const ITEM = makeItem({
    cotacaoProdutoId: "cp-1",
    nomeProduto: "Arroz 5kg",
    quantidade: 10,
    unidade: "un",
    precosPorFornecedor: [],
  });

  function quantidadeInput() {
    return screen.getByRole("spinbutton") as HTMLInputElement;
  }

  // Escopado à <table>: os selects de filtro (status/fornecedor) também são "combobox".
  function unidadeSelect() {
    return within(tabela()).getByRole("combobox") as HTMLSelectElement;
  }

  it("editar a Qtd e sair do campo (blur) salva quantidade + unidade juntas", async () => {
    const { onItemEditado } = renderTabela([ITEM]);
    fireEvent.change(quantidadeInput(), { target: { value: "15" } });
    fireEvent.blur(quantidadeInput());

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "cp-1", { quantidade: 15, unidade: "un" });
    await waitFor(() => expect(onItemEditado).toHaveBeenCalledTimes(1));
  });

  it("trocar a Unidade no select salva imediatamente (on change, sem precisar de blur)", async () => {
    renderTabela([ITEM]);
    fireEvent.change(unidadeSelect(), { target: { value: "kg" } });

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "cp-1", { quantidade: 10, unidade: "kg" });
  });

  it("regressão: digitar uma nova Qtd (sem blur) e trocar a Unidade envia a Qtd recém-digitada, não a original", async () => {
    renderTabela([ITEM]);
    fireEvent.change(quantidadeInput(), { target: { value: "25" } });
    // Ainda não saiu do campo Qtd (sem blur) — só o rascunho compartilhado foi atualizado.
    expect(editarItemCotacaoMock).not.toHaveBeenCalled();

    fireEvent.change(unidadeSelect(), { target: { value: "kg" } });

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "cp-1", { quantidade: 25, unidade: "kg" });
  });

  it("salvar com os mesmos valores originais (no-op) não chama a API", () => {
    renderTabela([ITEM]);
    fireEvent.change(quantidadeInput(), { target: { value: "10" } });
    fireEvent.blur(quantidadeInput());

    expect(editarItemCotacaoMock).not.toHaveBeenCalled();
  });

  it("erro da API: mensagem aparece só embaixo do input de Qtd e o rascunho reverte pros valores originais", async () => {
    editarItemCotacaoMock.mockRejectedValue(new ApiError("Não foi possível salvar a alteração.", 500));
    renderTabela([ITEM]);

    fireEvent.change(quantidadeInput(), { target: { value: "40" } });
    fireEvent.blur(quantidadeInput());

    await waitFor(() => expect(screen.getByText("Não foi possível salvar a alteração.")).toBeTruthy());
    expect(screen.getAllByText("Não foi possível salvar a alteração.")).toHaveLength(1);

    const tdQuantidade = quantidadeInput().closest("td")!;
    expect(within(tdQuantidade).getByText("Não foi possível salvar a alteração.")).toBeTruthy();

    const tdUnidade = unidadeSelect().closest("td")!;
    expect(within(tdUnidade).queryByText("Não foi possível salvar a alteração.")).toBeNull();

    expect(quantidadeInput().value).toBe("10");
    expect(unidadeSelect().value).toBe("un");
  });
});

describe("TabelaComparativa — filtros", () => {
  const ITEM_ARROZ = makeItem({
    cotacaoProdutoId: "cp-1",
    nomeProduto: "Arroz 5kg",
    precosPorFornecedor: [
      makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa", precoUnitarioCalculado: 20 }),
      makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", precoUnitarioCalculado: 23 }),
    ],
  }); // diff 15% => Melhor Compra
  const ITEM_FEIJAO = makeItem({
    cotacaoProdutoId: "cp-2",
    nomeProduto: "Feijão 1kg",
    precosPorFornecedor: [
      makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa", precoUnitarioCalculado: 10 }),
      makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", precoUnitarioCalculado: 40 }),
    ],
  }); // diff 300% => Alta Variação
  const ITEM_SAL = makeItem({
    cotacaoProdutoId: "cp-3",
    nomeProduto: "Sal Grosso 1kg",
    precosPorFornecedor: [
      makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa", semEstoque: true }),
      makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", precoUnitarioCalculado: 9 }),
    ],
  }); // só 1 oferta válida de 2 fornecedores => Cobertura Parcial
  const ITEM_SABAO = makeItem({
    cotacaoProdutoId: "cp-4",
    nomeProduto: "Sabão em Barra",
    precosPorFornecedor: [makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta", precoUnitarioCalculado: 5 })],
  }); // só cotado pelo Beta — nunca aparece no fornecedor forn-1

  function renderFiltros() {
    return renderTabela([ITEM_ARROZ, ITEM_FEIJAO, ITEM_SAL, ITEM_SABAO]);
  }

  it("busca por nome de produto filtra a lista, case-insensitive", () => {
    renderFiltros();
    fireEvent.change(screen.getByPlaceholderText("Buscar produto..."), { target: { value: "sal" } });

    expect(within(tabela()).getByText("Sal Grosso 1kg")).toBeTruthy();
    expect(within(tabela()).queryByText("Arroz 5kg")).toBeNull();
    expect(within(tabela()).queryByText("Feijão 1kg")).toBeNull();
    expect(within(tabela()).queryByText("Sabão em Barra")).toBeNull();
  });

  it("filtro de status mostra só os itens com o status de cobertura selecionado", () => {
    renderFiltros();
    fireEvent.change(screen.getByDisplayValue("Todos os status"), { target: { value: "alta_variacao" } });

    expect(within(tabela()).getByText("Feijão 1kg")).toBeTruthy();
    expect(within(tabela()).queryByText("Arroz 5kg")).toBeNull();
    expect(within(tabela()).queryByText("Sal Grosso 1kg")).toBeNull();
    expect(within(tabela()).queryByText("Sabão em Barra")).toBeNull();
  });

  it("filtro de fornecedor mostra só os itens que têm oferta (válida ou não) daquele fornecedor", () => {
    renderFiltros();
    fireEvent.change(screen.getByDisplayValue("Todos os fornecedores"), { target: { value: "forn-1" } });

    expect(within(tabela()).getByText("Arroz 5kg")).toBeTruthy();
    expect(within(tabela()).getByText("Feijão 1kg")).toBeTruthy();
    // Sal tem uma oferta do forn-1 mesmo sem estoque — o filtro olha presença, não validade.
    expect(within(tabela()).getByText("Sal Grosso 1kg")).toBeTruthy();
    expect(within(tabela()).queryByText("Sabão em Barra")).toBeNull();
  });
});

describe("TabelaComparativa — estado vazio", () => {
  it("mostra a mensagem de vazio com colSpan igual ao número real de colunas (3 estáticas + N fornecedores + 3 estáticas)", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "cp-1",
        nomeProduto: "Arroz 5kg",
        precosPorFornecedor: [
          makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa" }),
          makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta" }),
        ],
      }),
    ];
    renderTabela(itens);
    fireEvent.change(screen.getByPlaceholderText("Buscar produto..."), { target: { value: "não existe" } });

    const mensagem = screen.getByText("Nenhum produto encontrado com esses filtros.");
    const td = mensagem.closest("td") as HTMLTableCellElement;
    expect(td.colSpan).toBe(3 + 2 + 3);
  });

  it("colSpan escala com uma fixture sem nenhum fornecedor (0 colunas dinâmicas)", () => {
    renderTabela([]);

    const mensagem = screen.getByText("Nenhum produto encontrado com esses filtros.");
    const td = mensagem.closest("td") as HTMLTableCellElement;
    expect(td.colSpan).toBe(3 + 0 + 3);
  });
});

describe("TabelaComparativa — colunas dinâmicas por fornecedor", () => {
  it("cria uma coluna por fornecedor distinto encontrado em todos os itens, com o nome como cabeçalho", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "cp-1",
        nomeProduto: "Arroz 5kg",
        precosPorFornecedor: [
          makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa" }),
          makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta" }),
        ],
      }),
      makeItem({
        cotacaoProdutoId: "cp-2",
        nomeProduto: "Óleo de Soja 900ml",
        precosPorFornecedor: [makePreco({ fornecedorId: "forn-3", nomeFornecedor: "Fornecedor Gama" })],
      }),
    ];
    renderTabela(itens);

    const cabecalhos = screen.getAllByRole("columnheader").map((th) => th.textContent);
    expect(cabecalhos).toEqual(
      expect.arrayContaining(["Fornecedor Alfa", "Fornecedor Beta", "Fornecedor Gama"]),
    );
    // Produto, Qtd, Unidade, 3 fornecedores, Menor, Rec., Economia.
    expect(cabecalhos).toHaveLength(9);
  });

  it("com apenas 2 fornecedores distintos entre os itens, a tabela tem 8 colunas (sem a 3ª coluna de fornecedor)", () => {
    const itens = [
      makeItem({
        cotacaoProdutoId: "cp-1",
        nomeProduto: "Arroz 5kg",
        precosPorFornecedor: [
          makePreco({ fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Alfa" }),
          makePreco({ fornecedorId: "forn-2", nomeFornecedor: "Fornecedor Beta" }),
        ],
      }),
    ];
    renderTabela(itens);

    expect(screen.getAllByRole("columnheader")).toHaveLength(8);
  });
});
