// Prompt 26: ConferenciaModal.tsx (overlay com role="dialog", tabela DataGrid) virou
// ConferenciaPendente.tsx (conteúdo inline do passo 3, sem open/onClose, cards em vez
// de linhas de tabela). Este arquivo substitui o antigo ConferenciaModal.test.tsx —
// mesma cobertura de contrato de resolução (REVISAR bloqueia, ATENCAO não bloqueia,
// os fluxos de ResolucaoInline/ResolucaoExtraInline, confirmar/cancelar), adaptada aos
// novos seletores (cards em vez de <tr>/<td>, sem colSpan/thead, sem "Fechar"/backdrop
// — não há mais Modal envolvendo o conteúdo principal, só o aviso de cancelamento).
//
// Cobertura NÃO portada de propósito (fora do escopo desta rodada de testes, camada
// puramente visual acrescida no Prompt 26): anel de progresso segmentado, chips de
// filtro por si só, accordion "itens OK" expandir/recolher.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import ConferenciaPendente from "@/app/entrada/components/aprovacao/ConferenciaPendente";
import type { CandidatoResposta, ConferenciaPatch, EstadoResolucao, ItemConferenciaResponse, PreviewRespostaResponse } from "@/lib/types";

const { confirmarRespostaMock } = vi.hoisted(() => ({ confirmarRespostaMock: vi.fn() }));

// importOriginal preserva ApiError de verdade (getErrorMessage faz
// `err instanceof ApiError` — um mock parcial sem essa classe faz qualquer erro
// rejeitado virar uma segunda exceção dentro do catch).
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    confirmarResposta: confirmarRespostaMock,
  };
});

const onConfirmadoMock = vi.fn();
const onCancelarConferenciaMock = vi.fn();

function makeCandidato(overrides: Partial<CandidatoResposta> = {}): CandidatoResposta {
  return {
    textoOriginal: "15un sazon legumes 60g",
    marcaOferecida: null,
    precoInformado: 12.5,
    confiancaMatch: 0.95,
    semEstoque: false,
    ...overrides,
  };
}

function makeItem(overrides: Partial<ItemConferenciaResponse> = {}): ItemConferenciaResponse {
  return {
    itemBaseId: "item-1",
    nomeItemBase: "Sazon Legumes 60g",
    status: "REVISAR",
    motivos: [],
    candidatos: [makeCandidato()],
    preservado: false,
    precoAnteriorConfirmado: null,
    ...overrides,
  };
}

function makePreview(itens: ItemConferenciaResponse[]): PreviewRespostaResponse {
  const revisar = itens.filter((i) => i.status === "REVISAR").length;
  return {
    contadores: { total: itens.length, ok: itens.length - revisar, atencao: 0, revisar },
    itens,
  };
}

function estadoVazio(): EstadoResolucao {
  return { resolucoes: {}, spinOffs: {}, excluidos: {} };
}

// Harness que possui o estado de resolução via useState, igual ao que entrada/page.tsx
// faz em produção — sem isso, o componente não teria de onde ler/gravar as props
// controladas de estadoResolucao.
function ConferenciaPendenteHarness(props: { itens: ItemConferenciaResponse[] }) {
  const [estadoResolucao, setEstadoResolucao] = useState<EstadoResolucao>(estadoVazio());

  function onEstadoResolucaoChange(patch: ConferenciaPatch) {
    setEstadoResolucao((prev) => ({ ...prev, ...patch }));
  }

  return (
    <ConferenciaPendente
      onConfirmado={onConfirmadoMock}
      onCancelarConferencia={onCancelarConferenciaMock}
      cotacaoId="cot-1"
      fornecedorId="forn-1"
      fornecedorNome="Fornecedor Teste"
      textoOriginal="15un sazon legumes 60g R$ 12,50"
      preview={makePreview(props.itens)}
      estadoResolucao={estadoResolucao}
      onEstadoResolucaoChange={onEstadoResolucaoChange}
    />
  );
}

function renderPainel(itens: ItemConferenciaResponse[]) {
  return render(<ConferenciaPendenteHarness itens={itens} />);
}

function descricaoInput() {
  return screen.getByPlaceholderText("Descrição do produto") as HTMLInputElement;
}

function precoInput() {
  return screen.getByPlaceholderText("Preço") as HTMLInputElement;
}

function botaoConfirmar() {
  return screen.getByRole("button", { name: "Confirmar e Processar" }) as HTMLButtonElement;
}

// Localiza o card de um item pelo texto visível dentro dele (nome do item base, ou o
// texto original da resposta do fornecedor para itens extras) — o card é a div
// ".rounded-md.border.border-bdr.bg-card.p-3" mais próxima ancestral desse texto.
function cardDoTexto(texto: string): HTMLElement {
  // O mesmo texto pode aparecer duas vezes dentro do card (a linha de resumo do item
  // E, quando há um único candidato, também o rótulo do radio de ResolucaoInline) —
  // qualquer ocorrência resolve para o mesmo card ancestral.
  const [el] = screen.getAllByText(texto);
  const card = el.closest(".rounded-md.border.border-bdr.bg-card.p-3");
  if (!card) throw new Error(`Card não encontrado para o texto "${texto}"`);
  return card as HTMLElement;
}

// Contagem "ao vivo" do cabeçalho (span com ícone + "N Rótulo") — distinta dos chips
// de filtro, que usam a contagem bruta por status (ver comentário na describe de
// contadores mais abaixo).
function contagemCabecalho(rotulo: "OK" | "Atenção" | "Revisar"): number {
  const el = screen.getByText(new RegExp(`^\\d+ ${rotulo}$`));
  return Number(el.textContent?.trim().match(/^(\d+)/)?.[1]);
}

// jsdom não implementa scrollIntoView — o efeito que rola até o erro quebra com
// "not a function" assim que um teste dispara um erro visível.
Element.prototype.scrollIntoView = vi.fn();

beforeEach(() => {
  onConfirmadoMock.mockReset();
  onCancelarConferenciaMock.mockReset();
  confirmarRespostaMock.mockReset();
  confirmarRespostaMock.mockResolvedValue([]);
});

async function confirmarEObterPayload() {
  fireEvent.click(botaoConfirmar());
  await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));
  return confirmarRespostaMock.mock.calls[0][2] as { resolucoes: Array<Record<string, unknown>> };
}

describe("ConferenciaPendente — cabeçalho", () => {
  it("mostra o nome do fornecedor no título", () => {
    renderPainel([makeItem()]);
    expect(screen.getByText("Conferência do Fornecedor — Fornecedor Teste")).toBeTruthy();
  });
});

describe("ConferenciaPendente — ResolucaoInline", () => {
  it('"Editar manualmente" abre o formulário já preenchido com a opção atual', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "Editar manualmente" }));

    expect(descricaoInput().value).toBe("15un sazon legumes 60g");
    expect(precoInput().value).toBe("12.5");
  });

  it("opção do fornecedor sem preço não fica desabilitada no radio", () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    const radio = screen.getByRole("radio") as HTMLInputElement;
    expect(radio.disabled).toBe(false);
  });

  it("selecionar opção sem preço abre o formulário manual com o texto da opção e preço vazio", () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    expect(descricaoInput().value).toBe("1 fardo sal realta");
    expect(precoInput().value).toBe("");
  });

  it('botão "Salvar" começa desabilitado e habilita após digitar um preço válido', () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));
    const salvar = screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement;
    expect(salvar.disabled).toBe(true);

    fireEvent.change(precoInput(), { target: { value: "9,90" } });
    expect(salvar.disabled).toBe(false);
  });

  it("salvar a opção sem preço produz uma resolução EDITAR_MANUAL com o texto da opção e o preço digitado", async () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));
    fireEvent.change(precoInput(), { target: { value: "9,90" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "EDITAR_MANUAL",
        textoOriginalSelecionado: "1 fardo sal realta",
        precoInformado: 9.9,
      }),
    ]);
  });

  it("com múltiplas opções com preço, clicar em uma resolve direto (sem abrir formulário) como SELECIONAR_CANDIDATO", async () => {
    const candidato1 = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 10 });
    const candidato2 = makeCandidato({ textoOriginal: "15un sazon legumes 60g (outra marca)", precoInformado: 15.5 });
    renderPainel([makeItem({ candidatos: [candidato1, candidato2] })]);

    const radios = screen.getAllByRole("radio");
    fireEvent.click(radios[1]);

    expect(screen.queryByPlaceholderText("Descrição do produto")).toBeNull();

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "SELECIONAR_CANDIDATO",
        textoOriginalSelecionado: "15un sazon legumes 60g (outra marca)",
        precoInformado: 15.5,
      }),
    ]);
  });

  it("spin-off (ícone + de MULTIPLE_OPTIONS) com texto sem quantidade + unidade não habilita Salvar até corrigir", () => {
    const candidato1 = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 10 });
    const candidato2 = makeCandidato({ textoOriginal: "Sazon Legumes 60g Light", precoInformado: 15.5 });
    renderPainel([makeItem({ candidatos: [candidato1, candidato2] })]);

    fireEvent.click(screen.getAllByRole("radio")[0]);
    fireEvent.click(screen.getByRole("button", { name: "Adicionar como novo item na lista base" }));

    expect(descricaoInput().value).toBe("Sazon Legumes 60g Light");
    expect(screen.getByText(/Precisa começar com quantidade \+ unidade/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("candidato virado 'Novo item' fica com o radio desabilitado até o operador clicar em desfazer", () => {
    const candidato1 = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 10 });
    const candidato2 = makeCandidato({ textoOriginal: "2un sazon legumes 60g light", precoInformado: 15.5 });
    renderPainel([makeItem({ candidatos: [candidato1, candidato2] })]);

    fireEvent.click(screen.getAllByRole("radio")[0]);
    fireEvent.click(screen.getByRole("button", { name: "Adicionar como novo item na lista base" }));
    fireEvent.change(screen.getByPlaceholderText("Preço"), { target: { value: "15,50" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    const radios = screen.getAllByRole("radio") as HTMLInputElement[];
    expect(radios[1].disabled).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "Desfazer" }));
    expect((screen.getAllByRole("radio") as HTMLInputElement[])[1].disabled).toBe(false);
  });

  it("com uma única opção com preço, clicar nela resolve como ACEITAR_SUGESTAO", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "ACEITAR_SUGESTAO",
        textoOriginalSelecionado: "15un sazon legumes 60g",
        precoInformado: 12.5,
      }),
    ]);
  });

  it("preço digitado com vírgula (4,99) sai como 4.99 no payload", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "Editar manualmente" }));
    fireEvent.change(precoInput(), { target: { value: "4,99" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([expect.objectContaining({ precoInformado: 4.99 })]);
  });

  it('item REVISAR não resolvido mantém "Confirmar e Processar" desabilitado', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(true);
  });

  it('resolver o único item REVISAR habilita "Confirmar e Processar"', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(true);
    fireEvent.click(screen.getByRole("radio"));

    expect(botaoConfirmar().disabled).toBe(false);
  });

  it("confirmar com sucesso chama onConfirmado (quem decide navegação é o ConferenciaPanel, não este componente)", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));
    await confirmarEObterPayload();

    expect(onConfirmadoMock).toHaveBeenCalledTimes(1);
  });
});

describe("ConferenciaPendente — PACKAGE_PRICE_SUSPECTED (Unid./embalagem)", () => {
  it("informar a quantidade resolve o item direto (sem precisar clicar no candidato primeiro) e habilita Confirmar e Processar", async () => {
    const candidato = makeCandidato({ textoOriginal: "Feijão Carioca Tipo 1 - R$ 13,00", precoInformado: 13 });
    renderPainel([makeItem({ status: "REVISAR", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(true);

    const embalagemInput = screen.getByRole("spinbutton") as HTMLInputElement;
    fireEvent.change(embalagemInput, { target: { value: "12" } });

    expect(botaoConfirmar().disabled).toBe(false);

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "ACEITAR_SUGESTAO",
        textoOriginalSelecionado: "Feijão Carioca Tipo 1 - R$ 13,00",
        precoInformado: 13,
        embalagemQtd: 12,
      }),
    ]);
  });

  it("motivo 'Possível preço de caixa/fardo' some da tela assim que o item é resolvido", () => {
    const candidato = makeCandidato({ precoInformado: 13 });
    renderPainel([makeItem({ status: "REVISAR", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] })]);

    expect(screen.getByText("Possível preço de caixa/fardo")).toBeTruthy();

    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "12" } });

    expect(screen.queryByText("Possível preço de caixa/fardo")).toBeNull();
  });

  it("motivo de item REVISAR (ainda não resolvido) usa a cor de erro, não um cinza neutro", () => {
    const candidato = makeCandidato({ precoInformado: 13 });
    renderPainel([makeItem({ status: "REVISAR", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] })]);

    const motivo = screen.getByText("Possível preço de caixa/fardo");
    expect(motivo.className).toContain("text-er");
  });
});

describe("ConferenciaPendente — status ATENCAO ganha ação (não bloqueia, mas resolve)", () => {
  it("item ATENCAO com preço mostra só o atalho 'Ciente, manter preço' (não a UI pesada de REVISAR)", () => {
    // Achado do frontend-ux-designer (revisão de fidelidade ao protótipo): ATENCAO não
    // pode ter o mesmo peso visual/cognitivo de REVISAR — nada de lista de candidatos
    // com rádio nem "Editar manualmente"/"Sem oferta" por padrão quando já há preço.
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    expect(screen.getByRole("button", { name: "✓ Ciente, manter preço" })).toBeTruthy();
    expect(screen.queryByRole("radio")).toBeNull();
    expect(screen.queryByRole("button", { name: "Editar manualmente" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Sem oferta deste fornecedor" })).toBeNull();
  });

  it("clicar 'Ciente, manter preço' num item ATENCAO produz resolução no payload de confirmarResposta", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "✓ Ciente, manter preço" }));

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "ACEITAR_SUGESTAO",
        textoOriginalSelecionado: "15un sazon legumes 60g (marca X)",
        precoInformado: 10,
      }),
    ]);
  });

  it('item ATENCAO NÃO resolvido não bloqueia "Confirmar e Processar" (distinção central com REVISAR)', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(false);
  });

  it('"Ciente, manter preço" (atalho de 1 clique) resolve com a mesma resolução que clicar no único candidato produziria', async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "✓ Ciente, manter preço" }));

    const card = cardDoTexto("15un sazon legumes 60g (marca X)");
    expect(card.textContent).toContain("Resolvido");

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "ACEITAR_SUGESTAO",
        textoOriginalSelecionado: "15un sazon legumes 60g (marca X)",
        precoInformado: 10,
      }),
    ]);
  });

  it('"Ciente, manter preço" não aparece para item REVISAR (só ATENCAO)', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ status: "REVISAR", candidatos: [candidato] })]);

    expect(screen.queryByRole("button", { name: "✓ Ciente, manter preço" })).toBeNull();
  });

  // ResolucaoAtencaoInline (não ResolucaoInline) — item ATENCAO com
  // PACKAGE_PRICE_SUSPECTED ganha só o input de embalagem ao lado do atalho "Ciente,
  // manter preço", sem a lista de candidatos com rádio que REVISAR usa.
  it("item ATENCAO com PACKAGE_PRICE_SUSPECTED mostra o input de embalagem, e informar a quantidade resolve o item", async () => {
    const candidato = makeCandidato({ textoOriginal: "Feijão Carioca Tipo 1 - R$ 13,00", precoInformado: 13 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] })]);

    expect(screen.getByRole("button", { name: "✓ Ciente, manter preço" })).toBeTruthy();
    expect(screen.queryByRole("radio")).toBeNull();

    const embalagemInput = screen.getByRole("spinbutton") as HTMLInputElement;
    fireEvent.change(embalagemInput, { target: { value: "12" } });

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({
        itemBaseId: "item-1",
        tipo: "ACEITAR_SUGESTAO",
        textoOriginalSelecionado: "Feijão Carioca Tipo 1 - R$ 13,00",
        precoInformado: 13,
        embalagemQtd: 12,
      }),
    ]);
  });
});

describe("ConferenciaPendente — badge Resolvido", () => {
  it("item REVISAR resolvido exibe badge 'Resolvido' em vez de 'Revisar'", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    const card = cardDoTexto("15un sazon legumes 60g");
    expect(card.textContent).toContain("Resolvido");
    expect(card.textContent).not.toContain("Revisar");
  });

  it("item ATENCAO resolvido exibe badge 'Resolvido' em vez de 'Atenção'", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "✓ Ciente, manter preço" }));

    const card = cardDoTexto("15un sazon legumes 60g (marca X)");
    expect(card.textContent).toContain("Resolvido");
  });

  it("botão 'desfazer' aparece para item resolvido e clicar nele volta o badge para o status original", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "✓ Ciente, manter preço" }));
    expect(cardDoTexto("15un sazon legumes 60g (marca X)").textContent).toContain("Resolvido");

    // A ItemCard é recriada a cada mudança de estado (definida dentro do componente
    // pai) — reconsulta o card depois do clique em vez de reusar a referência antiga.
    fireEvent.click(screen.getByRole("button", { name: "desfazer" }));
    const card = cardDoTexto("15un sazon legumes 60g (marca X)");

    expect(card.textContent).toContain("Atenção");
    expect(card.textContent).not.toContain("Resolvido");
  });
});

describe("ConferenciaPendente — contadores ao vivo do cabeçalho", () => {
  it("ATENCAO resolvido conta como OK no cabeçalho (mas o chip de filtro 'Atenção' mantém a contagem bruta por severidade)", () => {
    const okItem = makeItem({ itemBaseId: "item-ok", status: "OK", candidatos: [makeCandidato({ textoOriginal: "item ok" })] });
    const atencaoItem = makeItem({
      itemBaseId: "item-atencao",
      status: "ATENCAO",
      motivos: ["BRAND_CHANGED"],
      candidatos: [makeCandidato({ textoOriginal: "item atencao", precoInformado: 8 })],
    });
    const revisarItem = makeItem({
      itemBaseId: "item-revisar",
      status: "REVISAR",
      candidatos: [makeCandidato({ textoOriginal: "item revisar", precoInformado: 5 })],
    });
    renderPainel([okItem, atencaoItem, revisarItem]);

    expect(contagemCabecalho("OK")).toBe(1);
    expect(contagemCabecalho("Atenção")).toBe(1);
    expect(contagemCabecalho("Revisar")).toBe(1);
    expect(screen.getByText("🟡 Atenção — não bloqueia (1)")).toBeTruthy();

    const cardAtencao = cardDoTexto("item atencao");
    fireEvent.click(within(cardAtencao).getByRole("button", { name: "✓ Ciente, manter preço" }));

    expect(contagemCabecalho("OK")).toBe(2);
    expect(contagemCabecalho("Atenção")).toBe(0);
    expect(contagemCabecalho("Revisar")).toBe(1);
    // O chip de filtro segue mostrando o item na categoria "Atenção" original — só o
    // resumo do cabeçalho é "ao vivo" pós-resolução.
    expect(screen.getByText("🟡 Atenção — não bloqueia (1)")).toBeTruthy();
  });
});

describe("ConferenciaPendente — SEM_OFERTA não exibe preço", () => {
  it('marcar "Sem oferta deste fornecedor" faz o card mostrar "—" em vez do preço do candidato recusado', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderPainel([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "Sem oferta deste fornecedor" }));

    const card = cardDoTexto("15un sazon legumes 60g");
    expect(within(card).getByText("—")).toBeTruthy();
  });
});

describe("ConferenciaPendente — item extra resolvido conta como OK", () => {
  it("item extra resolvido via '+ Adicionar à lista' conta como OK no cabeçalho", () => {
    const candidatoExtra = makeCandidato({ textoOriginal: "3un item extra nao identificado", precoInformado: 20 });
    const itemExtra = makeItem({
      itemBaseId: null,
      nomeItemBase: null,
      status: "REVISAR",
      motivos: ["EXTRA_ITEM"],
      candidatos: [candidatoExtra],
    });
    renderPainel([itemExtra]);

    expect(contagemCabecalho("OK")).toBe(0);
    expect(contagemCabecalho("Revisar")).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar à lista" }));

    expect(contagemCabecalho("OK")).toBe(1);
    expect(contagemCabecalho("Revisar")).toBe(0);
  });

  it("texto sem quantidade + unidade não resolve direto — abre o formulário manual pré-preenchido em vez disso", () => {
    const candidatoExtra = makeCandidato({ textoOriginal: "Maionese salada 500g", precoInformado: 4.99 });
    const itemExtra = makeItem({
      itemBaseId: null,
      nomeItemBase: null,
      status: "REVISAR",
      motivos: ["EXTRA_ITEM"],
      candidatos: [candidatoExtra],
    });
    renderPainel([itemExtra]);

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar à lista" }));

    expect(contagemCabecalho("OK")).toBe(0);
    expect(contagemCabecalho("Revisar")).toBe(1);
    expect(descricaoInput().value).toBe("Maionese salada 500g");
    expect(screen.getByText(/Precisa começar com quantidade \+ unidade/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("dois itens extras com textos diferentes viram cards distintos, cada um com seu próprio texto e ação independente", async () => {
    const itemA = makeItem({
      itemBaseId: null,
      nomeItemBase: null,
      status: "REVISAR",
      motivos: ["EXTRA_ITEM"],
      candidatos: [makeCandidato({ textoOriginal: "3un item extra A", precoInformado: 20 })],
    });
    const itemB = makeItem({
      itemBaseId: null,
      nomeItemBase: null,
      status: "REVISAR",
      motivos: ["EXTRA_ITEM"],
      candidatos: [makeCandidato({ textoOriginal: "5cx item extra B", precoInformado: 30 })],
    });
    renderPainel([itemA, itemB]);

    const cardA = cardDoTexto("3un item extra A");
    fireEvent.click(within(cardA).getByRole("button", { name: "+ Adicionar à lista" }));

    await waitFor(() => {
      expect(cardDoTexto("3un item extra A").textContent).toContain("Será adicionado à lista como novo produto");
    });

    // Item B continua intacto — seu texto e botão de ação não foram afetados.
    const cardB = cardDoTexto("5cx item extra B");
    expect(within(cardB).getByRole("button", { name: "+ Adicionar à lista" })).toBeTruthy();
    expect(cardB.textContent).not.toContain("Será adicionado à lista como novo produto");

    expect(contagemCabecalho("OK")).toBe(1);
    expect(contagemCabecalho("Revisar")).toBe(1);
  });
});

describe("ConferenciaPendente — Cancelar Conferência", () => {
  beforeEach(() => {
    onCancelarConferenciaMock.mockReset();
    onCancelarConferenciaMock.mockResolvedValue(undefined);
  });

  function botaoCancelar() {
    return screen.getByRole("button", { name: "Cancelar Conferência" }) as HTMLButtonElement;
  }

  function tituloAviso() {
    return screen.queryByText("Cancelar conferência deste fornecedor?");
  }

  it("clicar em 'Cancelar Conferência' abre um modal de aviso (não um window.confirm nativo)", () => {
    renderPainel([makeItem()]);
    expect(tituloAviso()).toBeNull();

    fireEvent.click(botaoCancelar());

    expect(tituloAviso()).toBeTruthy();
    expect(onCancelarConferenciaMock).not.toHaveBeenCalled();
  });

  it("clicar em 'Voltar' fecha o aviso sem cancelar nada — o rascunho não muda", () => {
    renderPainel([makeItem()]);
    fireEvent.click(screen.getByRole("radio"));
    expect(cardDoTexto("15un sazon legumes 60g").textContent).toContain("Resolvido");

    fireEvent.click(botaoCancelar());
    fireEvent.click(screen.getByRole("button", { name: "Voltar" }));

    expect(tituloAviso()).toBeNull();
    expect(onCancelarConferenciaMock).not.toHaveBeenCalled();
    expect(cardDoTexto("15un sazon legumes 60g").textContent).toContain("Resolvido");
  });

  it("confirmar no aviso chama onCancelarConferencia (é o pai quem apaga a resposta no backend, não este componente)", async () => {
    renderPainel([makeItem()]);

    fireEvent.click(botaoCancelar());
    fireEvent.click(screen.getByRole("button", { name: "Cancelar conferência" }));

    await waitFor(() => expect(onCancelarConferenciaMock).toHaveBeenCalledTimes(1));
    expect(onConfirmadoMock).not.toHaveBeenCalled();
    expect(confirmarRespostaMock).not.toHaveBeenCalled();
    await waitFor(() => expect(tituloAviso()).toBeNull());
  });

  it("se onCancelarConferencia falhar, mostra o erro dentro do painel e fecha o aviso", async () => {
    onCancelarConferenciaMock.mockRejectedValue(new Error("falhou"));
    renderPainel([makeItem()]);

    fireEvent.click(botaoCancelar());
    fireEvent.click(screen.getByRole("button", { name: "Cancelar conferência" }));

    await waitFor(() => expect(tituloAviso()).toBeNull());
    expect(screen.getByRole("alert")).toBeTruthy();
  });
});

describe("ConferenciaPendente — estado vazio (nenhum item casou com a lista base)", () => {
  it("mostra a mensagem de nenhum item casado", () => {
    renderPainel([]);

    expect(
      screen.getByText("Nenhum item da resposta casou com a lista base desta cotação."),
    ).toBeTruthy();
  });
});

describe("ConferenciaPendente — destaque de card por severidade", () => {
  it("item REVISAR não resolvido tem borda+fundo de erro no card", () => {
    renderPainel([makeItem({ itemBaseId: "item-revisar", status: "REVISAR" })]);

    const card = cardDoTexto("Sazon Legumes 60g");
    expect(card.className).toContain("border-l-er");
    expect(card.className).toContain("bg-er/5");
  });

  it("item REVISAR resolvido perde o destaque de erro no card", () => {
    renderPainel([makeItem({ itemBaseId: "item-revisar", status: "REVISAR" })]);

    fireEvent.click(screen.getByRole("radio"));

    const card = cardDoTexto("Sazon Legumes 60g");
    expect(card.className).not.toContain("border-l-er");
    expect(card.className).not.toContain("border-l-wa");
  });

  it("item ATENCAO não resolvido tem borda+fundo de atenção no card", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderPainel([makeItem({ itemBaseId: "item-atencao", status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    const card = cardDoTexto("15un sazon legumes 60g (marca X)");
    expect(card.className).toContain("border-l-wa");
    expect(card.className).toContain("bg-wa/6");
  });

  it("item preservado (já confirmado antes) usa o status original do item pro destaque, e mostra badge 'Confirmado'", () => {
    renderPainel([
      makeItem({ itemBaseId: "item-preservado", status: "ATENCAO", motivos: ["BRAND_CHANGED"], preservado: true }),
    ]);

    // Itens preservados entram no grupo "OK" (recolhido por padrão) — expande antes
    // de procurar o card.
    fireEvent.click(screen.getByRole("button", { name: /item\(ns\) conferidos automaticamente/ }));
    const card = cardDoTexto("Sazon Legumes 60g");
    expect(card.className).toContain("border-l-wa");
    expect(card.textContent).toContain("Confirmado");
  });
});
