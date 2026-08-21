// Cobre a migração de GridProdutosSection de <table> manual + LinhaGridProdutos (estado
// local por linha, DELETADO nesta migração) para o DataGrid compartilhado
// (@tanstack/react-table), com colunas definidas inline e um rascunho compartilhado por
// linha (`rascunhos`, chave item.id) cobrindo 4 campos: quantidade/unidade/produtoId/
// nomeExibido — sempre enviados juntos a editarItemCotacao mesmo quando só um mudou.
// Mesmo padrão de teste usado em comparativo/components/__tests__/TabelaComparativa.test.tsx
// (rascunho por-campo análogo, 2 campos) e no extinto LinhaGridProdutos.test.tsx (recuperável
// via `git show` — ver histórico do arquivo deletado).
//
// Cobre também: draftProdutoId/draftNomeExibido (undefined = "sem rascunho, cai no item"
// vs. null = "rascunho explícito de produto limpo"), trava de edição pós-conferência,
// tingimento de linha (rowClassName -> classificarStatusItemGrid), sort 3-estados da
// coluna Status, excluir, filtro client-side, "+ Adicionar Produto" via extraRows, e os
// estados vazios (lista nunca teve item vs. filtro zerou a lista).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import GridProdutosSection from "@/app/cotacoes/[id]/entrada/components/GridProdutosSection";
import { ApiError } from "@/lib/api";
import { ItemListaResponse, Produto } from "@/lib/types";

const {
  editarItemCotacaoMock,
  removerItemCotacaoMock,
  buscarListaMock,
  buscarProdutosMock,
  buscarProdutosPorIdsMock,
} = vi.hoisted(() => ({
  editarItemCotacaoMock: vi.fn(),
  removerItemCotacaoMock: vi.fn(),
  buscarListaMock: vi.fn(),
  buscarProdutosMock: vi.fn(),
  buscarProdutosPorIdsMock: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    editarItemCotacao: editarItemCotacaoMock,
    removerItemCotacao: removerItemCotacaoMock,
    buscarLista: buscarListaMock,
    // buscarProdutos: usado pelo ProdutoAutocomplete (busca no servidor, dentro do
    // dropdown). buscarProdutosPorIds: usado só por GridProdutosSection.recarregar()
    // pra atualizar o catálogo local (produtoNomePorId) após adicionar/editar um item.
    buscarProdutos: buscarProdutosMock,
    buscarProdutosPorIds: buscarProdutosPorIdsMock,
  };
});

function makeItem(overrides: Partial<ItemListaResponse> = {}): ItemListaResponse {
  return {
    id: "item-1",
    textoOriginal: "3un sazon legumes 60g",
    quantidade: 3,
    unidade: "un",
    produtoIdEncontrado: "prod-1",
    scoreMatch: 0.95,
    matched: true,
    temRespostaFornecedorConfirmada: false,
    ...overrides,
  };
}

function makeProduto(overrides: Partial<Produto> = {}): Produto {
  return {
    id: "prod-1",
    nome: "Sazon Legumes 60g",
    marca: null,
    pesoVolumeValor: null,
    pesoVolumeUnidade: null,
    unidadePadrao: "un",
    embalagemQtdSugerida: null,
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

const PRODUTOS = [
  makeProduto({ id: "prod-1", nome: "Sazon Legumes 60g" }),
  makeProduto({ id: "prod-2", nome: "Feijão Carioca 1kg" }),
];

// Formato que buscarProdutos (usado pelo ProdutoAutocomplete internamente) devolve —
// Page<Produto>, não o array cru que buscarProdutosPorIds devolve.
const PRODUTOS_PAGE = { content: PRODUTOS, totalElements: PRODUTOS.length, totalPages: 1, number: 0, size: 8 };

function renderGrid(
  itens: ItemListaResponse[],
  overrides: Partial<{
    onListaAtualizada: (i: ItemListaResponse[]) => void;
    onProdutosAtualizados: (p: Produto[]) => void;
    produtos: Produto[];
    cotacaoFinalizada: boolean;
    podeAdicionarOuColar: boolean;
    scrollProprio: boolean;
  }> = {},
) {
  const onListaAtualizada = overrides.onListaAtualizada ?? vi.fn();
  const onProdutosAtualizados = overrides.onProdutosAtualizados ?? vi.fn();
  const setErro = vi.fn();
  render(
    <GridProdutosSection
      cotacaoId="cot-1"
      itens={itens}
      produtos={overrides.produtos ?? PRODUTOS}
      onListaAtualizada={onListaAtualizada}
      onProdutosAtualizados={onProdutosAtualizados}
      setErro={setErro}
      cotacaoFinalizada={overrides.cotacaoFinalizada}
      podeAdicionarOuColar={overrides.podeAdicionarOuColar}
      scrollProprio={overrides.scrollProprio}
    />,
  );
  return { onListaAtualizada, onProdutosAtualizados, setErro };
}

function tabela() {
  return screen.getByRole("table");
}

function quantidadeInput() {
  return within(tabela()).getByRole("spinbutton") as HTMLInputElement;
}

function unidadeSelect() {
  return within(tabela()).getByRole("combobox") as HTMLSelectElement;
}

function produtoInput() {
  return within(tabela()).getByPlaceholderText("Buscar produto...") as HTMLInputElement;
}

function excluirButton() {
  return within(tabela()).getByRole("button", { name: "Excluir" }) as HTMLButtonElement;
}

function modalConfirmarExcluirButton() {
  return within(screen.getByRole("dialog")).getByRole("button", { name: "Excluir" }) as HTMLButtonElement;
}

function modalCancelarButton() {
  return within(screen.getByRole("dialog")).getByRole("button", { name: "Cancelar" }) as HTMLButtonElement;
}

function statusHeaderButton() {
  return screen.getByRole("button", { name: /Status/ });
}

beforeEach(() => {
  editarItemCotacaoMock.mockReset();
  removerItemCotacaoMock.mockReset();
  buscarListaMock.mockReset();
  buscarProdutosMock.mockReset();
  editarItemCotacaoMock.mockResolvedValue(undefined);
  removerItemCotacaoMock.mockResolvedValue(undefined);
  buscarListaMock.mockResolvedValue([]);
  buscarProdutosMock.mockResolvedValue(PRODUTOS_PAGE);
  buscarProdutosPorIdsMock.mockResolvedValue(PRODUTOS);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});

describe("GridProdutosSection — edição de quantidade (rascunho compartilhado)", () => {
  it("mostra a quantidade e o texto original recebidos", () => {
    renderGrid([makeItem({ textoOriginal: "3un sazon legumes 60g", quantidade: 3 })]);
    expect(within(tabela()).getByText("3un sazon legumes 60g")).toBeTruthy();
    expect(quantidadeInput().value).toBe("3");
  });

  it("editar a quantidade e sair do campo (blur) salva com os três campos atuais", async () => {
    renderGrid([makeItem()]);
    fireEvent.change(quantidadeInput(), { target: { value: "5" } });
    fireEvent.blur(quantidadeInput());

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
      quantidade: 5,
      unidade: "un",
      produtoId: "prod-1",
      nomeProdutoLivre: undefined,
    });
  });

  it("quantidade inválida (0) no blur não chama a API", () => {
    renderGrid([makeItem()]);
    fireEvent.change(quantidadeInput(), { target: { value: "0" } });
    fireEvent.blur(quantidadeInput());

    expect(editarItemCotacaoMock).not.toHaveBeenCalled();
  });

  it("salvar com o mesmo valor original (no-op) ainda reenvia ao backend, mas sem alterar o resultado — API é chamada normalmente no blur", async () => {
    // commitRow não faz comparação de "mudou de fato" antes de chamar a API — o
    // guard existente é só de validade (qtd > 0 e unidade não vazia). Documenta o
    // comportamento real: um blur sem mudança ainda dispara o PATCH.
    renderGrid([makeItem()]);
    fireEvent.change(quantidadeInput(), { target: { value: "3" } });
    fireEvent.blur(quantidadeInput());

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
  });

  it("regressão: digitar uma nova Qtd (sem blur) e trocar a Unidade envia a Qtd recém-digitada, não a original", async () => {
    renderGrid([makeItem({ quantidade: 3, unidade: "un" })]);
    fireEvent.change(quantidadeInput(), { target: { value: "25" } });
    // Ainda não saiu do campo Qtd (sem blur) — só o rascunho compartilhado foi atualizado.
    expect(editarItemCotacaoMock).not.toHaveBeenCalled();

    fireEvent.change(unidadeSelect(), { target: { value: "kg" } });

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
      quantidade: 25,
      unidade: "kg",
      produtoId: "prod-1",
      nomeProdutoLivre: undefined,
    });
  });

  it("erro no salvamento da quantidade mostra a mensagem na coluna Status (não em outra célula) e reverte quantidade/unidade", async () => {
    editarItemCotacaoMock.mockRejectedValue(
      new ApiError("Este produto já está associado a outra linha desta cotação.", 409),
    );
    renderGrid([makeItem({ quantidade: 3, unidade: "un" })]);

    fireEvent.change(quantidadeInput(), { target: { value: "9" } });
    fireEvent.blur(quantidadeInput());

    await waitFor(() =>
      expect(screen.getByText("Este produto já está associado a outra linha desta cotação.")).toBeTruthy(),
    );

    // A mensagem de erro aparece uma única vez em toda a tabela, e só na coluna
    // Status (mesma célula do texto original não deve conter a mensagem).
    expect(screen.getAllByText("Este produto já está associado a outra linha desta cotação.")).toHaveLength(1);
    const mensagem = screen.getByText("Este produto já está associado a outra linha desta cotação.");
    const cellStatus = mensagem.closest("td")!;
    const cellTextoOriginal = within(tabela()).getByText("3un sazon legumes 60g").closest("td")!;
    expect(cellStatus).not.toBe(cellTextoOriginal);

    expect(quantidadeInput().value).toBe("3");
    expect(unidadeSelect().value).toBe("un");
  });
});

describe("GridProdutosSection — edição de unidade", () => {
  it("trocar a unidade no select salva imediatamente (on change, sem precisar de blur)", async () => {
    renderGrid([makeItem()]);
    fireEvent.change(unidadeSelect(), { target: { value: "kg" } });

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
      quantidade: 3,
      unidade: "kg",
      produtoId: "prod-1",
      nomeProdutoLivre: undefined,
    });
  });
});

describe("GridProdutosSection — edição de produto (draftProdutoId/draftNomeExibido)", () => {
  it("selecionar um produto no autocomplete salva com o novo produtoId", async () => {
    renderGrid([makeItem()]);
    fireEvent.focus(produtoInput());
    // ProdutoAutocomplete busca no servidor (buscarProdutos) — a sugestão só aparece
    // depois que a promise mockada resolve.
    fireEvent.click(await within(tabela()).findByText("Feijão Carioca 1kg"));

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
      quantidade: 3,
      unidade: "un",
      produtoId: "prod-2",
      nomeProdutoLivre: undefined,
    });
  });

  it("item sem produto identificado mostra o aviso 'Sem produto identificado — selecione um.'", () => {
    renderGrid([makeItem({ produtoIdEncontrado: null })]);
    expect(screen.getByText("Sem produto identificado — selecione um.")).toBeTruthy();
  });

  it("digitar um nome sem match e usar '+ usar como novo produto' salva via nomeProdutoLivre", async () => {
    renderGrid([makeItem({ produtoIdEncontrado: null })]);
    fireEvent.focus(produtoInput());
    fireEvent.change(produtoInput(), { target: { value: "Produto Nunca Visto" } });
    fireEvent.click(await screen.findByText('+ usar "Produto Nunca Visto" como novo produto'));

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-1", {
      quantidade: 3,
      unidade: "un",
      produtoId: undefined,
      nomeProdutoLivre: "Produto Nunca Visto",
    });
  });

  it("selecionar um produto do catálogo APÓS ter usado nome livre limpa o rascunho de nomeExibido (produtoId ganha prioridade no envio)", async () => {
    renderGrid([makeItem({ produtoIdEncontrado: null })]);

    // 1) usa nome livre primeiro — cria rascunho produtoId=null, nomeExibido="Zap".
    fireEvent.focus(produtoInput());
    fireEvent.change(produtoInput(), { target: { value: "Zap" } });
    fireEvent.click(await screen.findByText('+ usar "Zap" como novo produto'));

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));
    expect(editarItemCotacaoMock).toHaveBeenLastCalledWith("cot-1", "item-1", {
      quantidade: 3,
      unidade: "un",
      produtoId: undefined,
      nomeProdutoLivre: "Zap",
    });

    // 2) em seguida seleciona um produto real do catálogo — o handler onSelecionar
    // chama onCellEdit(produtoId) e onCellEdit(nomeExibido, null) antes de commitRow
    // com overrides explícitos, então o envio deve carregar produtoId (não a sobra
    // do nomeProdutoLivre anterior).
    fireEvent.focus(produtoInput());
    fireEvent.click(await within(tabela()).findByText("Feijão Carioca 1kg"));

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(2));
    expect(editarItemCotacaoMock).toHaveBeenLastCalledWith("cot-1", "item-1", {
      quantidade: 3,
      unidade: "un",
      produtoId: "prod-2",
      nomeProdutoLivre: undefined,
    });
  });

  it("usar nome livre APÓS ter selecionado um produto do catálogo limpa o rascunho de produtoId (nomeProdutoLivre é enviado, não o produtoId antigo)", async () => {
    renderGrid([makeItem({ produtoIdEncontrado: null })]);

    // 1) seleciona um produto do catálogo primeiro.
    fireEvent.focus(produtoInput());
    fireEvent.click(await within(tabela()).findByText("Feijão Carioca 1kg"));
    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(1));

    // 2) troca de ideia e usa nome livre — onUsarNomeLivre limpa produtoId (null) e
    // seta nomeExibido antes de commitar com overrides.
    fireEvent.focus(produtoInput());
    fireEvent.change(produtoInput(), { target: { value: "Produto Totalmente Novo" } });
    fireEvent.click(await screen.findByText('+ usar "Produto Totalmente Novo" como novo produto'));

    await waitFor(() => expect(editarItemCotacaoMock).toHaveBeenCalledTimes(2));
    expect(editarItemCotacaoMock).toHaveBeenLastCalledWith("cot-1", "item-1", {
      quantidade: 3,
      unidade: "un",
      produtoId: undefined,
      nomeProdutoLivre: "Produto Totalmente Novo",
    });
  });
});

describe("GridProdutosSection — trava de edição pós-conferência", () => {
  it("item com resposta de fornecedor confirmada desabilita quantidade/unidade/produto", () => {
    renderGrid([makeItem({ temRespostaFornecedorConfirmada: true })]);
    expect(quantidadeInput().disabled).toBe(true);
    expect(unidadeSelect().disabled).toBe(true);
    expect(produtoInput().disabled).toBe(true);
  });

  it("mostra um indicador de cadeado com tooltip explicando o motivo, na coluna Status", () => {
    renderGrid([makeItem({ temRespostaFornecedorConfirmada: true })]);
    expect(screen.getByTitle(/já foi conferido por um fornecedor/i)).toBeTruthy();
  });

  it("item travado ainda permite excluir", () => {
    renderGrid([makeItem({ temRespostaFornecedorConfirmada: true })]);
    expect(excluirButton().disabled).toBe(false);
  });
});

describe("GridProdutosSection — cotação finalizada desabilita edição por padrão (sem tentar e falhar)", () => {
  it("desabilita quantidade/unidade/produto/excluir de todo item, mesmo sem resposta de fornecedor confirmada", () => {
    renderGrid([makeItem({ temRespostaFornecedorConfirmada: false })], { cotacaoFinalizada: true });
    expect(quantidadeInput().disabled).toBe(true);
    expect(unidadeSelect().disabled).toBe(true);
    expect(produtoInput().disabled).toBe(true);
    expect(excluirButton().disabled).toBe(true);
  });

  it("mostra um indicador de cadeado com tooltip explicando que a cotação finalizada não aceita alteração", () => {
    renderGrid([makeItem({ temRespostaFornecedorConfirmada: false })], { cotacaoFinalizada: true });
    expect(screen.getAllByTitle("Cotação finalizada não aceita alteração de itens.").length).toBeGreaterThan(0);
  });

  it("desabilita 'Colar do WhatsApp' e '+ Adicionar Produto', que também são bloqueados pelo backend", () => {
    renderGrid([makeItem()], { cotacaoFinalizada: true });
    expect((screen.getByRole("button", { name: "Colar do WhatsApp" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "+ Adicionar Produto" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("não chama a API ao tentar editar (a UI já bloqueia antes do request)", async () => {
    renderGrid([makeItem({ temRespostaFornecedorConfirmada: false })], { cotacaoFinalizada: true });
    fireEvent.change(quantidadeInput(), { target: { value: "9" } });
    fireEvent.blur(quantidadeInput());
    await Promise.resolve();
    expect(editarItemCotacaoMock).not.toHaveBeenCalled();
  });
});

describe("GridProdutosSection — 3 tipos de erro de linha e tingimento (rowClassName)", () => {
  function linhaDoItem(textoBusca: string) {
    return within(tabela()).getByText(textoBusca).closest("tr")!;
  }

  it("sem produto identificado: tingimento vermelho + mensagem específica", () => {
    renderGrid([makeItem({ produtoIdEncontrado: null, textoOriginal: "3un algo sem produto" })]);
    expect(screen.getByText("Sem produto identificado — selecione um.")).toBeTruthy();
    const linha = linhaDoItem("3un algo sem produto");
    expect(linha.className).toContain("border-l-er");
  });

  it("formato de texto não reconhecido: linha colada com dígito que não bate no heurístico mostra aviso e tingimento âmbar", () => {
    renderGrid([makeItem({ textoOriginal: "42 xyzzy plugh algo estranho", produtoIdEncontrado: "prod-1" })]);
    expect(screen.getByText(/Formato de texto não reconhecido/)).toBeTruthy();
    const linha = linhaDoItem("42 xyzzy plugh algo estranho");
    expect(linha.className).toContain("border-l-wa");
  });

  it("item adicionado manualmente (textoOriginal vazio) não é marcado como formato inválido", () => {
    renderGrid([makeItem({ textoOriginal: "", produtoIdEncontrado: "prod-1" })]);
    expect(screen.queryByText(/Formato de texto não reconhecido/)).toBeNull();
    expect(screen.getByText("OK")).toBeTruthy();
  });

  it("unidade fora do padrão conhecido mostra aviso específico e tingimento âmbar", () => {
    renderGrid([makeItem({ unidade: "caixote", produtoIdEncontrado: "prod-1", textoOriginal: "" })]);
    expect(screen.getByText(/fora do padrão conhecido/)).toBeTruthy();
    const linha = linhaDoItem("Adicionado manualmente");
    expect(linha.className).toContain("border-l-wa");
  });

  it("item OK sem nenhum problema não recebe classe extra de tingimento", () => {
    renderGrid([makeItem({ textoOriginal: "", produtoIdEncontrado: "prod-1", unidade: "un" })]);
    const linha = linhaDoItem("Adicionado manualmente");
    expect(linha.className).not.toContain("border-l-er");
    expect(linha.className).not.toContain("border-l-wa");
  });
});

describe("GridProdutosSection — ordenação por Status (3 estados)", () => {
  const OK = makeItem({ id: "item-ok", textoOriginal: "", produtoIdEncontrado: "prod-1", unidade: "un" });
  const SEM_PRODUTO = makeItem({ id: "item-sem-produto", textoOriginal: "", produtoIdEncontrado: null, unidade: "un" });
  const FORMATO_INVALIDO = makeItem({
    id: "item-formato-invalido",
    textoOriginal: "42 xyzzy plugh estranho",
    produtoIdEncontrado: "prod-1",
    unidade: "un",
  });

  it("cicla null -> asc -> desc -> null no ícone do header e reordena por urgência (rank)", () => {
    renderGrid([OK, SEM_PRODUTO, FORMATO_INVALIDO]);
    const header = statusHeaderButton();
    expect(header.textContent).toContain("⇅");

    fireEvent.click(header);
    expect(statusHeaderButton().textContent).toContain("▲");
    // asc = mais urgente primeiro: sem produto (rank 0), formato inválido (rank 1), ok (rank 3).
    const linhasAsc = within(tabela()).getAllByRole("row").slice(1);
    expect(within(linhasAsc[0]).queryByText("Sem produto identificado — selecione um.")).toBeTruthy();
    expect(within(linhasAsc[2]).queryByText("OK")).toBeTruthy();

    fireEvent.click(statusHeaderButton());
    expect(statusHeaderButton().textContent).toContain("▼");
    const linhasDesc = within(tabela()).getAllByRole("row").slice(1);
    expect(within(linhasDesc[0]).queryByText("OK")).toBeTruthy();

    fireEvent.click(statusHeaderButton());
    expect(statusHeaderButton().textContent).toContain("⇅");
  });
});

describe("GridProdutosSection — excluir", () => {
  it("clicar em Excluir abre um modal de confirmação (não window.confirm)", () => {
    const confirmSpy = vi.spyOn(window, "confirm");
    renderGrid([makeItem({ id: "item-42" })]);
    fireEvent.click(excluirButton());

    expect(screen.getByRole("dialog")).toBeTruthy();
    expect(confirmSpy).not.toHaveBeenCalled();
  });

  it("confirmar no modal chama removerItemCotacao com o id do item, recarregando a lista e fechando o modal", async () => {
    renderGrid([makeItem({ id: "item-42" })]);
    fireEvent.click(excluirButton());
    fireEvent.click(modalConfirmarExcluirButton());

    await waitFor(() => expect(removerItemCotacaoMock).toHaveBeenCalledWith("cot-1", "item-42"));
    await waitFor(() => expect(buscarListaMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("cancelar no modal fecha sem chamar removerItemCotacao", () => {
    renderGrid([makeItem()]);
    fireEvent.click(excluirButton());
    fireEvent.click(modalCancelarButton());

    expect(removerItemCotacaoMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("erro ao excluir mostra mensagem de fallback na coluna Status", async () => {
    removerItemCotacaoMock.mockRejectedValue(new Error("network down"));
    renderGrid([makeItem()]);
    fireEvent.click(excluirButton());
    fireEvent.click(modalConfirmarExcluirButton());

    await waitFor(() => expect(screen.getByText("Não foi possível excluir o item.")).toBeTruthy());
  });
});

describe("GridProdutosSection — filtro", () => {
  const ITEM_ARROZ = makeItem({
    id: "item-arroz",
    textoOriginal: "5kg arroz tipo 1",
    unidade: "kg",
    produtoIdEncontrado: "prod-1",
  });
  const ITEM_FEIJAO = makeItem({
    id: "item-feijao",
    textoOriginal: "2un feijao carioca",
    unidade: "un",
    produtoIdEncontrado: "prod-2",
  });

  it("filtra por texto original, case-insensitive e sem acento", () => {
    renderGrid([ITEM_ARROZ, ITEM_FEIJAO]);
    fireEvent.change(screen.getByPlaceholderText("Buscar por produto, unidade ou texto original..."), {
      target: { value: "FEIJAO" },
    });

    expect(within(tabela()).getByText("2un feijao carioca")).toBeTruthy();
    expect(within(tabela()).queryByText("5kg arroz tipo 1")).toBeNull();
  });

  it("filtra por nome de produto identificado", () => {
    renderGrid([ITEM_ARROZ, ITEM_FEIJAO]);
    fireEvent.change(screen.getByPlaceholderText("Buscar por produto, unidade ou texto original..."), {
      target: { value: "Feijão Carioca" },
    });

    expect(within(tabela()).getByText("2un feijao carioca")).toBeTruthy();
    expect(within(tabela()).queryByText("5kg arroz tipo 1")).toBeNull();
  });

  it("filtra por unidade", () => {
    // produtoIdEncontrado: null nos dois pra isolar o teste — "Feijão Carioca 1kg"
    // (nome do produto do item feijão) também contém "kg" e poluiria a asserção se
    // o nome do produto entrasse na busca.
    const arroz = { ...ITEM_ARROZ, produtoIdEncontrado: null };
    const feijao = { ...ITEM_FEIJAO, produtoIdEncontrado: null };
    renderGrid([arroz, feijao]);
    fireEvent.change(screen.getByPlaceholderText("Buscar por produto, unidade ou texto original..."), {
      target: { value: "kg" },
    });

    expect(within(tabela()).getByText("5kg arroz tipo 1")).toBeTruthy();
    expect(within(tabela()).queryByText("2un feijao carioca")).toBeNull();
  });

  it("campo de filtro não aparece quando a lista está vazia desde o início", () => {
    renderGrid([]);
    expect(screen.queryByPlaceholderText("Buscar por produto, unidade ou texto original...")).toBeNull();
  });
});

describe("GridProdutosSection — nova linha (NovaLinhaGridProdutos via extraRows)", () => {
  it("clicar em '+ Adicionar Produto' monta a nova linha como a primeira linha do corpo da tabela", () => {
    renderGrid([makeItem({ textoOriginal: "3un sazon legumes 60g" })]);
    expect(screen.queryByRole("button", { name: "Salvar" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar Produto" }));

    expect(screen.getByRole("button", { name: "Salvar" })).toBeTruthy();
    const linhas = within(tabela()).getAllByRole("row").slice(1); // pula header
    // A nova linha (extraRows) é renderizada antes das linhas mapeadas de itens.
    expect(within(linhas[0]).getByRole("button", { name: "Salvar" })).toBeTruthy();
    expect(within(linhas[1]).getByText("3un sazon legumes 60g")).toBeTruthy();
  });

  it("clicar em 'Cancelar' desmonta a nova linha", () => {
    renderGrid([makeItem()]);
    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar Produto" }));
    expect(screen.getByRole("button", { name: "Salvar" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.queryByRole("button", { name: "Salvar" })).toBeNull();
  });

  it("botão '+ Adicionar Produto' fica desabilitado enquanto a nova linha está aberta", () => {
    renderGrid([makeItem()]);
    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar Produto" }));
    expect((screen.getByRole("button", { name: "+ Adicionar Produto" }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("GridProdutosSection — estados vazios", () => {
  it("lista nunca teve item: mostra a mensagem de onboarding SEM montar o grid", () => {
    renderGrid([]);
    expect(
      screen.getByText('Nenhum produto adicionado ainda. Use "+ Adicionar Produto" ou "Colar do WhatsApp".'),
    ).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("clicar em '+ Adicionar Produto' com a lista vazia monta o grid pra mostrar a linha de cadastro", () => {
    renderGrid([]);
    expect(screen.queryByRole("table")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar Produto" }));

    expect(screen.getByRole("table")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Salvar" })).toBeTruthy();
    expect(
      screen.queryByText('Nenhum produto adicionado ainda. Use "+ Adicionar Produto" ou "Colar do WhatsApp".'),
    ).toBeNull();
  });

  it("filtro zera a lista: mostra a mensagem de 'nenhum item bate com o filtro'", () => {
    renderGrid([makeItem({ textoOriginal: "5kg arroz tipo 1" })]);
    fireEvent.change(screen.getByPlaceholderText("Buscar por produto, unidade ou texto original..."), {
      target: { value: "produto que não existe" },
    });

    expect(screen.getByText("Nenhum item bate com o filtro.")).toBeTruthy();
    expect(screen.queryByText('Nenhum produto adicionado ainda. Use "+ Adicionar Produto" ou "Colar do WhatsApp".')).toBeNull();
  });

  it("conteúdo vazio não aparece enquanto o modo 'adicionar' está ativo, mesmo com a lista filtrada vazia", () => {
    renderGrid([makeItem({ textoOriginal: "5kg arroz tipo 1" })]);
    fireEvent.change(screen.getByPlaceholderText("Buscar por produto, unidade ou texto original..."), {
      target: { value: "produto que não existe" },
    });
    expect(screen.getByText("Nenhum item bate com o filtro.")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar Produto" }));

    expect(screen.queryByText("Nenhum item bate com o filtro.")).toBeNull();
    expect(screen.getByRole("button", { name: "Salvar" })).toBeTruthy();
  });
});

// podeAdicionarOuColar (refactor 2026-08-20, 2ª leva): este grid passou a ser
// reaproveitado dentro da aba "Conferência da Lista Base" do AprovacaoModal, pros dois
// canais — cotações WHATSAPP não devem oferecer adicionar/colar manualmente (a AI já
// populou a lista), só corrigir uma linha.
describe("GridProdutosSection — podeAdicionarOuColar (gate de canal)", () => {
  it("por padrão (sem a prop), os botões de adicionar/colar aparecem", () => {
    renderGrid([makeItem()]);

    expect(screen.getByRole("button", { name: "+ Adicionar Produto" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Colar do WhatsApp" })).toBeTruthy();
  });

  it("podeAdicionarOuColar=false esconde os dois botões", () => {
    renderGrid([makeItem()], { podeAdicionarOuColar: false });

    expect(screen.queryByRole("button", { name: "+ Adicionar Produto" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Colar do WhatsApp" })).toBeNull();
  });

  it("lista vazia com podeAdicionarOuColar=false mostra uma mensagem sem mencionar os botões escondidos", () => {
    renderGrid([], { podeAdicionarOuColar: false });

    expect(screen.getByText("Nenhum produto identificado ainda nesta lista.")).toBeTruthy();
    expect(screen.queryByText(/Adicionar Produto/)).toBeNull();
  });

  it("exclusão de item continua disponível mesmo com podeAdicionarOuColar=false", () => {
    renderGrid([makeItem()], { podeAdicionarOuColar: false });

    expect(screen.getByRole("button", { name: "Excluir" })).toBeTruthy();
  });
});
