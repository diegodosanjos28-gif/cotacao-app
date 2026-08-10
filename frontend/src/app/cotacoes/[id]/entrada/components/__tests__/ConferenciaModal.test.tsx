// Cobre duas correções na Conferência (ResolucaoInline em ConferenciaModal.tsx):
// A) "Editar manualmente" agora abre o formulário já preenchido com a opção atual
//    (antes abria vazio); B) uma opção sem preço (parser deixa precoInformado null
//    quando a linha do fornecedor não traz valor, ex.: "1 fardo sal realta") agora é
//    selecionável e leva ao formulário manual em vez de ser um beco sem saída.
// Cobre também a regra de bloqueio do "Confirmar e Processar" (regressão) e o parsing
// de preço digitado com vírgula (precoDigitado).

import { describe, expect, it, vi, beforeEach } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import ConferenciaModal from "@/app/cotacoes/[id]/entrada/components/ConferenciaModal";
import type { CandidatoResposta, ConferenciaPatch, EstadoResolucao, ItemConferenciaResponse, PreviewRespostaResponse } from "@/lib/types";

const { confirmarRespostaMock } = vi.hoisted(() => ({ confirmarRespostaMock: vi.fn() }));

// importOriginal preserva ApiError de verdade (getErrorMessage faz
// `err instanceof ApiError` — um mock parcial sem essa classe faz qualquer erro
// rejeitado virar uma segunda exceção dentro do catch, nunca testado até o achado
// do usuário sobre Cancelar Conferência precisar de tratamento de erro de verdade).
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    confirmarResposta: confirmarRespostaMock,
  };
});

const onConfirmadoMock = vi.fn();
const onCloseMock = vi.fn();
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

// Harness que possui o estado de resolução via useState, igual ao que page.tsx faz em
// produção (a Fase 4.1 elevou resolucoes/spinOffs/excluidos pra fora do modal) — sem
// isso, o modal não teria de onde ler/gravar as props controladas.
function ConferenciaModalHarness(props: { itens: ItemConferenciaResponse[] }) {
  const [estadoResolucao, setEstadoResolucao] = useState<EstadoResolucao>(estadoVazio());

  function onEstadoResolucaoChange(patch: ConferenciaPatch) {
    setEstadoResolucao((prev) => ({ ...prev, ...patch }));
  }

  return (
    <ConferenciaModal
      open
      onClose={onCloseMock}
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

function renderModal(itens: ItemConferenciaResponse[]) {
  return render(<ConferenciaModalHarness itens={itens} />);
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

// jsdom não implementa scrollIntoView — o efeito que rola até o erro (ConferenciaModal.tsx,
// erroRef) quebra com "not a function" assim que algum teste realmente dispara um erro
// visível (nenhum fazia isso até o achado do usuário sobre Cancelar Conferência exigir
// tratamento de erro de verdade).
Element.prototype.scrollIntoView = vi.fn();

beforeEach(() => {
  onConfirmadoMock.mockReset();
  confirmarRespostaMock.mockReset();
  confirmarRespostaMock.mockResolvedValue([]);
});

async function confirmarEObterPayload() {
  fireEvent.click(botaoConfirmar());
  await waitFor(() => expect(confirmarRespostaMock).toHaveBeenCalledTimes(1));
  return confirmarRespostaMock.mock.calls[0][2] as { resolucoes: Array<Record<string, unknown>> };
}

describe("ConferenciaModal — ResolucaoInline", () => {
  // Correção A
  it('"Editar manualmente" abre o formulário já preenchido com a opção atual', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "Editar manualmente" }));

    expect(descricaoInput().value).toBe("15un sazon legumes 60g");
    expect(precoInput().value).toBe("12.5");
  });

  // Correção B — parte 1
  it("opção do fornecedor sem preço não fica desabilitada no radio", () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderModal([makeItem({ candidatos: [candidato] })]);

    const radio = screen.getByRole("radio") as HTMLInputElement;
    expect(radio.disabled).toBe(false);
  });

  // Correção B — parte 2
  it("selecionar opção sem preço abre o formulário manual com o texto da opção e preço vazio", () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    expect(descricaoInput().value).toBe("1 fardo sal realta");
    expect(precoInput().value).toBe("");
  });

  // Correção B — parte 3
  it('botão "Salvar" começa desabilitado e habilita após digitar um preço válido', () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));
    const salvar = screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement;
    expect(salvar.disabled).toBe(true);

    fireEvent.change(precoInput(), { target: { value: "9,90" } });
    expect(salvar.disabled).toBe(false);
  });

  // Correção B — parte 4 (saída observável: payload de confirmarResposta)
  it("salvar a opção sem preço produz uma resolução EDITAR_MANUAL com o texto da opção e o preço digitado", async () => {
    const candidato = makeCandidato({ textoOriginal: "1 fardo sal realta", precoInformado: null });
    renderModal([makeItem({ candidatos: [candidato] })]);

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

  // Correção C — múltiplas opções com preço
  it("com múltiplas opções com preço, clicar em uma resolve direto (sem abrir formulário) como SELECIONAR_CANDIDATO", async () => {
    const candidato1 = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 10 });
    const candidato2 = makeCandidato({ textoOriginal: "15un sazon legumes 60g (outra marca)", precoInformado: 15.5 });
    renderModal([makeItem({ candidatos: [candidato1, candidato2] })]);

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
    renderModal([makeItem({ candidatos: [candidato1, candidato2] })]);

    // Resolve o item original selecionando o candidato1 como vencedor.
    fireEvent.click(screen.getAllByRole("radio")[0]);

    // Abre o spin-off do candidato2 (não selecionado) — texto bruto do fornecedor
    // não começa com quantidade + unidade.
    fireEvent.click(screen.getByRole("button", { name: "Adicionar como novo item na lista base" }));

    expect(descricaoInput().value).toBe("Sazon Legumes 60g Light");
    expect(screen.getByText(/Precisa começar com quantidade \+ unidade/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("candidato virado 'Novo item' fica com o radio desabilitado até o operador clicar em desfazer", () => {
    const candidato1 = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 10 });
    const candidato2 = makeCandidato({ textoOriginal: "2un sazon legumes 60g light", precoInformado: 15.5 });
    renderModal([makeItem({ candidatos: [candidato1, candidato2] })]);

    fireEvent.click(screen.getAllByRole("radio")[0]);
    fireEvent.click(screen.getByRole("button", { name: "Adicionar como novo item na lista base" }));
    // Texto já começa com quantidade + unidade — Salvar não fica bloqueado por formato.
    fireEvent.change(screen.getByPlaceholderText("Preço"), { target: { value: "15,50" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    const radios = screen.getAllByRole("radio") as HTMLInputElement[];
    // radios[0] é o candidato já selecionado (continua habilitado); radios[1] é o
    // candidato que virou "Novo item" — precisa estar desabilitado agora.
    expect(radios[1].disabled).toBe(true);

    // Clicar em "desfazer" (ícone de voltar) libera o radio de novo.
    fireEvent.click(screen.getByRole("button", { name: "Desfazer" }));
    expect((screen.getAllByRole("radio") as HTMLInputElement[])[1].disabled).toBe(false);
  });

  // Correção C — única opção com preço
  it("com uma única opção com preço, clicar nela resolve como ACEITAR_SUGESTAO", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

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

  // Correção D — preço aceita vírgula
  it("preço digitado com vírgula (4,99) sai como 4.99 no payload", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "Editar manualmente" }));
    fireEvent.change(precoInput(), { target: { value: "4,99" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar" }));

    const payload = await confirmarEObterPayload();
    expect(payload.resolucoes).toEqual([
      expect.objectContaining({ precoInformado: 4.99 }),
    ]);
  });

  // Regressão da regra de bloqueio do modal
  it('item REVISAR não resolvido mantém "Confirmar e Processar" desabilitado', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(true);
  });

  it('resolver o único item REVISAR habilita "Confirmar e Processar"', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(true);
    fireEvent.click(screen.getByRole("radio"));

    expect(botaoConfirmar().disabled).toBe(false);
  });

  // Fase 4: o modal não decide mais navegação sozinho (isso depende de quantos outros
  // fornecedores da cotação ainda estão pendentes, que só o componente pai conhece) —
  // ele só reporta o evento via onConfirmado.
  it("confirmar com sucesso chama onConfirmado em vez de navegar", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));
    await confirmarEObterPayload();

    expect(onConfirmadoMock).toHaveBeenCalledTimes(1);
  });
});

describe("ConferenciaModal — PACKAGE_PRICE_SUSPECTED (Unid./embalagem)", () => {
  it("informar a quantidade resolve o item direto (sem precisar clicar no candidato primeiro) e a habilita Confirmar e Processar", async () => {
    const candidato = makeCandidato({ textoOriginal: "Feijão Carioca Tipo 1 - R$ 13,00", precoInformado: 13 });
    renderModal([
      makeItem({ status: "REVISAR", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] }),
    ]);

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
    renderModal([
      makeItem({ status: "REVISAR", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] }),
    ]);

    expect(screen.getByText("Possível preço de caixa/fardo")).toBeTruthy();

    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "12" } });

    expect(screen.queryByText("Possível preço de caixa/fardo")).toBeNull();
  });

  it("motivo de item REVISAR (ainda não resolvido) usa a cor de erro, não um cinza neutro", () => {
    const candidato = makeCandidato({ precoInformado: 13 });
    renderModal([
      makeItem({ status: "REVISAR", motivos: ["PACKAGE_PRICE_SUSPECTED"], candidatos: [candidato] }),
    ]);

    const motivo = screen.getByText("Possível preço de caixa/fardo");
    expect(motivo.className).toContain("text-er");
  });
});

// Linhas da tabela (sem a linha de cabeçalho do thead), na ordem em que os itens do
// preview foram passados — usado pelos testes abaixo para escopar consultas por linha
// em vez de depender de o texto ser único no documento inteiro (o cabeçalho do modal
// repete os rótulos "OK"/"Atenção"/"Revisar" nos cards de contadores).
function linhasDados(): HTMLElement[] {
  return screen.getAllByRole("row").slice(1);
}

// Cards do cabeçalho (.sr-stat): valor (um <p> com o número) vem ANTES do rótulo (outro
// <p>) no markup — ver comentário em ConferenciaModal.tsx. O seletor "p" evita colisão
// com os badges de status das linhas (que usam o mesmo texto "OK"/"Atenção"/"Revisar",
// mas em <span>).
function valorCard(rotulo: string): string {
  const label = screen.getByText(rotulo, { selector: "p" });
  return (label.previousElementSibling as HTMLElement).textContent ?? "";
}

describe("ConferenciaModal — status ATENCAO ganha ação (antes só REVISAR tinha)", () => {
  it("item ATENCAO renderiza as opções do fornecedor e os botões de ação", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderModal([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    expect(screen.getByRole("radio")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Editar manualmente" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Sem oferta deste fornecedor" })).toBeTruthy();
  });

  it("selecionar a opção de um item ATENCAO produz resolução no payload de confirmarResposta", async () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderModal([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

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
    renderModal([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    expect(botaoConfirmar().disabled).toBe(false);
  });
});

describe("ConferenciaModal — badge Resolvido", () => {
  it("item REVISAR resolvido exibe badge 'Resolvido' em vez de 'Revisar'", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    const linha = linhasDados()[0];
    expect(within(linha).getByText("Resolvido")).toBeTruthy();
    expect(within(linha).queryByText("Revisar")).toBeNull();
  });

  it("item ATENCAO resolvido exibe badge 'Resolvido' em vez de 'Atenção'", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderModal([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    const linha = linhasDados()[0];
    expect(within(linha).getByText("Resolvido")).toBeTruthy();
    expect(within(linha).queryByText("Atenção")).toBeNull();
  });

  it("botão 'desfazer' aparece para item ATENCAO resolvido (antes só aparecia em REVISAR) e clicar nele volta o badge para 'Atenção'", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderModal([makeItem({ status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));
    const linha = linhasDados()[0];
    fireEvent.click(within(linha).getByText("desfazer"));

    expect(within(linha).getByText("Atenção")).toBeTruthy();
    expect(within(linha).queryByText("Resolvido")).toBeNull();
  });
});

describe("ConferenciaModal — contadores ao vivo do cabeçalho", () => {
  it("ATENCAO resolvido conta como OK (antes ficava eternamente no contador de Atenção)", () => {
    const okItem = makeItem({
      itemBaseId: "item-ok",
      status: "OK",
      candidatos: [makeCandidato({ textoOriginal: "item ok" })],
    });
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
    renderModal([okItem, atencaoItem, revisarItem]);

    expect(valorCard("Total")).toBe("3");
    expect(valorCard("OK")).toBe("1");
    expect(valorCard("Atenção")).toBe("1");
    expect(valorCard("Revisar")).toBe("1");

    const linhaAtencao = linhasDados()[1];
    fireEvent.click(within(linhaAtencao).getByRole("radio"));

    expect(valorCard("OK")).toBe("2");
    expect(valorCard("Atenção")).toBe("0");
    expect(valorCard("Revisar")).toBe("1");
  });
});

describe("ConferenciaModal — SEM_OFERTA não exibe preço", () => {
  it('marcar "Sem oferta deste fornecedor" faz a célula de Preço mostrar "—" em vez do preço do candidato recusado', () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g", precoInformado: 12.5 });
    renderModal([makeItem({ candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("button", { name: "Sem oferta deste fornecedor" }));

    const linha = linhasDados()[0];
    const celulaPreco = within(linha).getAllByRole("cell")[2];
    expect(celulaPreco.textContent).toBe("—");
  });
});

describe("ConferenciaModal — item extra resolvido conta como OK", () => {
  it("item extra resolvido via '+ Adicionar à lista' conta como OK no contador do cabeçalho", () => {
    // textoOriginal já começa com quantidade + unidade — caminho feliz, resolve direto.
    const candidatoExtra = makeCandidato({ textoOriginal: "3un item extra nao identificado", precoInformado: 20 });
    const itemExtra = makeItem({
      itemBaseId: null,
      nomeItemBase: null,
      status: "REVISAR",
      motivos: ["EXTRA_ITEM"],
      candidatos: [candidatoExtra],
    });
    renderModal([itemExtra]);

    expect(valorCard("Total")).toBe("1");
    expect(valorCard("OK")).toBe("0");
    expect(valorCard("Revisar")).toBe("1");

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar à lista" }));

    expect(valorCard("OK")).toBe("1");
    expect(valorCard("Revisar")).toBe("0");
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
    renderModal([itemExtra]);

    fireEvent.click(screen.getByRole("button", { name: "+ Adicionar à lista" }));

    // Não resolveu — ainda conta como Revisar, não OK.
    expect(valorCard("OK")).toBe("0");
    expect(valorCard("Revisar")).toBe("1");
    // Formulário manual abriu pré-preenchido com o texto original do fornecedor.
    expect(descricaoInput().value).toBe("Maionese salada 500g");
    // Hint de formato aparece, e Salvar fica desabilitado até o texto ser corrigido.
    expect(screen.getByText(/Precisa começar com quantidade \+ unidade/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Salvar" }) as HTMLButtonElement).disabled).toBe(true);
  });
});

// Achado do usuário, 2026-08-04: "Cancelar Conferência" é distinto de "Fechar" —
// Fechar só esconde o modal preservando o rascunho (decisão inalterada da Fase 4.1),
// Cancelar descarta as resoluções em andamento e pede confirmação antes (mesmo padrão
// já usado no projeto pra ações destrutivas — window.confirm).
describe("ConferenciaModal — Cancelar Conferência", () => {
  beforeEach(() => {
    onCloseMock.mockReset();
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
    renderModal([makeItem()]);
    expect(tituloAviso()).toBeNull();

    fireEvent.click(botaoCancelar());

    expect(tituloAviso()).toBeTruthy();
    expect(onCancelarConferenciaMock).not.toHaveBeenCalled();
  });

  it("clicar em 'Voltar' fecha o aviso sem cancelar nada", () => {
    renderModal([makeItem()]);
    fireEvent.click(screen.getByRole("radio"));
    expect(within(linhasDados()[0]).getByText("Resolvido")).toBeTruthy();

    fireEvent.click(botaoCancelar());
    fireEvent.click(screen.getByRole("button", { name: "Voltar" }));

    expect(tituloAviso()).toBeNull();
    expect(onCancelarConferenciaMock).not.toHaveBeenCalled();
    expect(onCloseMock).not.toHaveBeenCalled();
    // o rascunho não muda — a linha continua "Resolvido".
    expect(within(linhasDados()[0]).getByText("Resolvido")).toBeTruthy();
  });

  it("confirmar no aviso chama onCancelarConferencia (é o pai quem apaga a resposta no backend, não este modal)", async () => {
    renderModal([makeItem()]);

    fireEvent.click(botaoCancelar());
    fireEvent.click(screen.getByRole("button", { name: "Cancelar conferência" }));

    await waitFor(() => expect(onCancelarConferenciaMock).toHaveBeenCalledTimes(1));
    expect(onConfirmadoMock).not.toHaveBeenCalled();
    expect(confirmarRespostaMock).not.toHaveBeenCalled();
    await waitFor(() => expect(tituloAviso()).toBeNull());
  });

  it("se onCancelarConferencia falhar, mostra o erro dentro do modal principal e fecha o aviso", async () => {
    onCancelarConferenciaMock.mockRejectedValue(new Error("falhou"));
    renderModal([makeItem()]);

    fireEvent.click(botaoCancelar());
    fireEvent.click(screen.getByRole("button", { name: "Cancelar conferência" }));

    await waitFor(() => expect(tituloAviso()).toBeNull());
    expect(screen.getByRole("alert")).toBeTruthy();
  });

  it("'Fechar' continua existindo e com comportamento inalterado: fecha sem abrir nenhum aviso e sem cancelar nada", () => {
    renderModal([makeItem()]);
    fireEvent.click(screen.getByRole("radio"));
    expect(within(linhasDados()[0]).getByText("Resolvido")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Fechar" }));

    expect(tituloAviso()).toBeNull();
    expect(onCancelarConferenciaMock).not.toHaveBeenCalled();
    expect(onCloseMock).toHaveBeenCalledTimes(1);
    // o rascunho não muda — a linha continua "Resolvido" (mesmo estado controlado
    // pelo pai que "Conferir resposta do fornecedor" reabriam).
    expect(within(linhasDados()[0]).getByText("Resolvido")).toBeTruthy();
  });

  it("clicar fora do modal principal (backdrop) continua chamando o mesmo fechar() de sempre", () => {
    const { container } = renderModal([makeItem()]);

    fireEvent.click(container.querySelector(".fixed.inset-0") as Element);

    expect(onCloseMock).toHaveBeenCalledTimes(1);
    expect(onCancelarConferenciaMock).not.toHaveBeenCalled();
  });
});

// Migração de LinhaConferencia (dono do próprio <tr>) para DataGrid (rowClassName
// centralizado em ConferenciaModal.tsx): statusEfetivoDoItem/corLinhaDoStatus foram
// extraídos como funções puras, mas o que importa é a fiação — que o `rowClassName`
// passado ao <DataGrid> realmente chama essas funções com o item e o "resolvido"
// certos. Os 32 testes acima já cobrem o conteúdo visível das células; estes cobrem a
// className da própria <tr>, que nenhum deles inspeciona.
describe("ConferenciaModal — destaque de linha (rowClassName via statusEfetivoDoItem/corLinhaDoStatus)", () => {
  it("item REVISAR não resolvido tem borda+fundo de erro na linha", () => {
    renderModal([makeItem({ itemBaseId: "item-revisar", status: "REVISAR" })]);

    const linha = linhasDados()[0];
    expect(linha.className).toContain("border-l-er");
    expect(linha.className).toContain("bg-er/5");
  });

  it("item REVISAR resolvido perde o destaque de erro na linha (statusEfetivo vira RESOLVIDO)", () => {
    renderModal([makeItem({ itemBaseId: "item-revisar", status: "REVISAR" })]);

    fireEvent.click(screen.getByRole("radio"));

    const linha = linhasDados()[0];
    expect(linha.className).not.toContain("border-l-er");
    expect(linha.className).not.toContain("border-l-wa");
  });

  it("item ATENCAO não resolvido tem borda+fundo de atenção na linha", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderModal([makeItem({ itemBaseId: "item-atencao", status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    const linha = linhasDados()[0];
    expect(linha.className).toContain("border-l-wa");
    expect(linha.className).toContain("bg-wa/6");
  });

  it("item ATENCAO resolvido perde o destaque de atenção na linha", () => {
    const candidato = makeCandidato({ textoOriginal: "15un sazon legumes 60g (marca X)", precoInformado: 10 });
    renderModal([makeItem({ itemBaseId: "item-atencao", status: "ATENCAO", motivos: ["BRAND_CHANGED"], candidatos: [candidato] })]);

    fireEvent.click(screen.getByRole("radio"));

    const linha = linhasDados()[0];
    expect(linha.className).not.toContain("border-l-wa");
    expect(linha.className).not.toContain("border-l-er");
  });

  it("item OK não tem nenhuma borda de destaque na linha", () => {
    renderModal([makeItem({ itemBaseId: "item-ok", status: "OK", candidatos: [makeCandidato()] })]);

    const linha = linhasDados()[0];
    expect(linha.className).not.toContain("border-l-er");
    expect(linha.className).not.toContain("border-l-wa");
  });

  it("item preservado (já confirmado antes) usa o status original do item pro destaque, não o estado de resolução", () => {
    // preservado nunca é resolvível pela UI (podeResolver exige !item.preservado), mas o
    // destaque da linha ainda precisa refletir item.status — statusEfetivoDoItem tem um
    // branch dedicado pra preservado que ignora `resolvido` por completo.
    renderModal([
      makeItem({ itemBaseId: "item-preservado", status: "ATENCAO", motivos: ["BRAND_CHANGED"], preservado: true }),
    ]);

    const linha = linhasDados()[0];
    expect(linha.className).toContain("border-l-wa");
    expect(linha.className).toContain("bg-wa/6");
    // Badge da célula de Status confirma que preservado é tratado à parte de ATENCAO comum.
    expect(within(linha).getByText("Confirmado")).toBeTruthy();
  });
});

// TanStack Table agora é dono da identidade de cada linha (getRowId), não mais um `key`
// avulso em <LinhaConferencia>. Item extra (itemBaseId null) usa `extra-${index}` como
// id — o cenário realista de risco é dois itens extras coexistindo e um deles sendo
// resolvido sem contaminar o outro.
describe("ConferenciaModal — identidade de linha para itens extras (getRowId extra-${index})", () => {
  function itemExtra(textoOriginal: string, preco: number) {
    return makeItem({
      itemBaseId: null,
      nomeItemBase: null,
      status: "REVISAR",
      motivos: ["EXTRA_ITEM"],
      candidatos: [makeCandidato({ textoOriginal, precoInformado: preco })],
    });
  }

  it("dois itens extras com textos diferentes viram linhas distintas, cada uma com seu próprio texto", () => {
    renderModal([itemExtra("3un item extra A", 20), itemExtra("5cx item extra B", 30)]);

    const linhas = linhasDados();
    expect(linhas).toHaveLength(2);
    expect(within(linhas[0]).getByText("3un item extra A")).toBeTruthy();
    expect(within(linhas[1]).getByText("5cx item extra B")).toBeTruthy();
  });

  it("resolver um item extra ('+ Adicionar à lista') não afeta o texto/estado do item extra vizinho", async () => {
    renderModal([itemExtra("3un item extra A", 20), itemExtra("5cx item extra B", 30)]);

    const [linhaA] = linhasDados();
    fireEvent.click(within(linhaA).getByRole("button", { name: "+ Adicionar à lista" }));

    // Item A: resolvido, mostra a mensagem de "será adicionado" e some o botão de ação.
    await waitFor(() => {
      const [linhaAAtual] = linhasDados();
      expect(within(linhaAAtual).getByText(/Será adicionado à lista como novo produto/)).toBeTruthy();
    });

    // Item B: continua intacto — seu texto, seus botões de ação, nenhuma resolução.
    const [, linhaBAtual] = linhasDados();
    expect(within(linhaBAtual).getByText("5cx item extra B")).toBeTruthy();
    expect(within(linhaBAtual).getByRole("button", { name: "+ Adicionar à lista" })).toBeTruthy();
    expect(within(linhaBAtual).queryByText(/Será adicionado à lista como novo produto/)).toBeNull();

    expect(valorCard("OK")).toBe("1");
    expect(valorCard("Revisar")).toBe("1");
  });

  it("ordem das linhas permanece estável (A antes de B) depois de resolver a primeira", async () => {
    renderModal([itemExtra("3un item extra A", 20), itemExtra("5cx item extra B", 30)]);

    fireEvent.click(within(linhasDados()[0]).getByRole("button", { name: "+ Adicionar à lista" }));

    await waitFor(() => {
      const linhas = linhasDados();
      expect(within(linhas[0]).getByText("3un item extra A")).toBeTruthy();
      expect(within(linhas[1]).getByText("5cx item extra B")).toBeTruthy();
    });
  });
});

// preview.itens vazio (nenhum candidato do fornecedor casou com a lista base) — colSpan
// agora é derivado de colunas.length (4) em vez de um colSpan={4} hardcoded na migração
// anterior a DataGrid.
describe("ConferenciaModal — estado vazio (nenhum item casou com a lista base)", () => {
  it('mostra a mensagem de nenhum item casado com colSpan igual ao número de colunas (4)', () => {
    renderModal([]);

    const mensagem = screen.getByText("Nenhum item da resposta casou com a lista base desta cotação.");
    const celula = mensagem.closest("td");
    expect(celula).toBeTruthy();
    expect(celula?.getAttribute("colspan")).toBe("4");
  });
});

// theadRowClassName é um prop novo do DataGrid (a linha de cabeçalho, não o <thead>) —
// DataGrid.test.tsx já confirma que o componente genérico aplica a classe no lugar
// certo; este teste confirma que ConferenciaModal.tsx de fato passa esse prop, e não
// deixou a estilização do cabeçalho pra trás na migração.
describe("ConferenciaModal — estilização do cabeçalho da tabela (theadRowClassName)", () => {
  it("a <tr> do thead carrega as classes de estilo, não o <thead> em si", () => {
    const { container } = renderModal([makeItem()]);

    expect(container.querySelector("thead")?.className).toBe("");
    const linhaCabecalho = container.querySelector("thead > tr");
    expect(linhaCabecalho).toBeTruthy();
    expect(linhaCabecalho?.className).toBe("border-b border-bdr text-left text-xs uppercase tracking-wide text-t3");
  });
});
