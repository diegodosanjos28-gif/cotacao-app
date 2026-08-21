// FornecedorCapturaCard — card de captura do fluxo Web manual (fornecedor PENDENTE
// dentro da aba "Conferência das Cotações"). Redesenho do antigo FornecedorRespostaBlock
// (removido), 2026-08-20.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import FornecedorCapturaCard from "../FornecedorCapturaCard";
import { CotacaoFornecedorResponse, Fornecedor } from "@/lib/types";

const { atualizarFornecedorMock } = vi.hoisted(() => ({ atualizarFornecedorMock: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, atualizarFornecedor: atualizarFornecedorMock };
});

function makeCf(overrides: Partial<CotacaoFornecedorResponse> = {}): CotacaoFornecedorResponse {
  return { id: "cf-1", fornecedorId: "forn-1", nomeFornecedor: "Fornecedor Teste", ordem: 0, status: "PENDENTE", ...overrides };
}

function makeFornecedor(overrides: Partial<Fornecedor> = {}): Fornecedor {
  return {
    id: "forn-1",
    nome: "Fornecedor Teste",
    prazoEntregaPadrao: "2 dias",
    condicaoPagamentoPadrao: "Boleto 14d",
    pedidoMinimoPadrao: 100,
    observacoesPadrao: null,
    status: "ATIVO",
    origemCadastro: "MANUAL",
    criadoEm: "2026-08-01T00:00:00Z",
    atualizadoEm: null,
    ...overrides,
  };
}

beforeEach(() => {
  atualizarFornecedorMock.mockReset();
});

describe("FornecedorCapturaCard — renderização", () => {
  it("mostra o nome do fornecedor, avatar por iniciais e a pill 'Aguardando resposta'", () => {
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect(screen.getByText("Fornecedor Teste")).toBeTruthy();
    expect(screen.getByText("FT")).toBeTruthy();
    expect(screen.getByText("Aguardando resposta")).toBeTruthy();
  });

  it("pré-preenche os campos comerciais a partir do fornecedor", () => {
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor({ prazoEntregaPadrao: "3 dias úteis" })}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect((screen.getByDisplayValue("3 dias úteis") as HTMLInputElement).value).toBe("3 dias úteis");
  });

  it("cadastro incompleto (PENDENTE_DADOS) mostra o aviso", () => {
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor({ status: "PENDENTE_DADOS" })}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect(screen.getByText(/Cadastro incompleto/)).toBeTruthy();
  });
});

describe("FornecedorCapturaCard — edição de campos comerciais", () => {
  it("salva um campo comercial ao perder o foco (onBlur), só quando o valor muda", async () => {
    atualizarFornecedorMock.mockResolvedValue(makeFornecedor({ prazoEntregaPadrao: "5 dias" }));
    const onFornecedorAtualizado = vi.fn();
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={onFornecedorAtualizado}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    const input = screen.getByDisplayValue("2 dias");
    fireEvent.change(input, { target: { value: "5 dias" } });
    fireEvent.blur(input);

    await waitFor(() => expect(atualizarFornecedorMock).toHaveBeenCalledWith("forn-1", expect.objectContaining({ prazoEntregaPadrao: "5 dias" })));
    await waitFor(() => expect(onFornecedorAtualizado).toHaveBeenCalled());
  });

  it("perder o foco sem alterar o valor não chama a API", () => {
    const input = () => screen.getByDisplayValue("2 dias");
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    fireEvent.blur(input());

    expect(atualizarFornecedorMock).not.toHaveBeenCalled();
  });

  it("erro ao salvar mostra mensagem e reverte o campo", async () => {
    atualizarFornecedorMock.mockRejectedValue(new Error("falhou"));
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    const input = screen.getByDisplayValue("2 dias") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "5 dias" } });
    fireEvent.blur(input);

    await waitFor(() => expect(screen.getByText("Não foi possível salvar a alteração.")).toBeTruthy());
    expect(input.value).toBe("2 dias");
  });
});

describe("FornecedorCapturaCard — captura da resposta", () => {
  it("digitar no textarea chama onTextoChange", () => {
    const onTextoChange = vi.fn();
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={onTextoChange}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText(/Sazon Legumes/), { target: { value: "5un item - R$ 10,00" } });

    expect(onTextoChange).toHaveBeenCalledWith("5un item - R$ 10,00");
  });

  it("botão 'Processar Resposta Cotação' fica desabilitado com texto vazio", () => {
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={vi.fn()}
        texto=""
        onTextoChange={vi.fn()}
        onProcessar={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect((screen.getByRole("button", { name: "Processar Resposta Cotação" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("clicar em 'Processar Resposta Cotação' chama onProcessar e mostra estado de carregamento", async () => {
    let resolver: (() => void) | undefined;
    const onProcessar = vi.fn(() => new Promise<void>((resolve) => { resolver = resolve; }));
    render(
      <FornecedorCapturaCard
        cotacaoFornecedor={makeCf()}
        fornecedor={makeFornecedor()}
        onFornecedorAtualizado={vi.fn()}
        texto="5un item - R$ 10,00"
        onTextoChange={vi.fn()}
        onProcessar={onProcessar}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Processar Resposta Cotação" }));

    expect(onProcessar).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Processando...")).toBeTruthy();

    resolver?.();
    await waitFor(() => expect(screen.getByText("Processar Resposta Cotação")).toBeTruthy());
  });
});
