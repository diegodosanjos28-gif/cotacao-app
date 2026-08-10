// Cobre a tela /meus-telefones migrada para o DataGrid compartilhado: colunas
// Número/Nome (com fallback "—" quando nomeContato é nulo)/Criado em/Ações, e o botão
// "Remover" — que passa por window.confirm antes de chamar a API, e mostra
// "Removendo..." desabilitado enquanto a chamada está em voo.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import MeusTelefonesPage from "@/app/meus-telefones/page";
import { UsuarioTelefone } from "@/lib/types";

const { listarMeusTelefonesMock, removerMeuTelefoneMock } = vi.hoisted(() => ({
  listarMeusTelefonesMock: vi.fn(),
  removerMeuTelefoneMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/meus-telefones",
}));

vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return {
    ...actual,
    isAuthenticated: () => true,
    getPapel: () => "OPERADOR_CLIENTE",
    getTenantId: () => "t-1",
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    listarMeusTelefones: listarMeusTelefonesMock,
    removerMeuTelefone: removerMeuTelefoneMock,
  };
});

function makeTelefone(overrides: Partial<UsuarioTelefone> = {}): UsuarioTelefone {
  return {
    id: "tel-1",
    numeroWhatsapp: "+5511987654321",
    nomeContato: "Loja Centro",
    ativo: true,
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  listarMeusTelefonesMock.mockReset();
  removerMeuTelefoneMock.mockReset();
});

describe("MeusTelefonesPage — tabela", () => {
  it("renderiza número e nome do contato de cada telefone", async () => {
    listarMeusTelefonesMock.mockResolvedValue([makeTelefone()]);

    render(<MeusTelefonesPage />);

    await waitFor(() => expect(screen.getByText("+5511987654321")).toBeTruthy());
    expect(screen.getByText("Loja Centro")).toBeTruthy();
  });

  it("mostra '—' quando nomeContato é nulo", async () => {
    listarMeusTelefonesMock.mockResolvedValue([makeTelefone({ nomeContato: null })]);

    render(<MeusTelefonesPage />);

    await waitFor(() => expect(screen.getByText("+5511987654321")).toBeTruthy());
    expect(screen.getByText("—")).toBeTruthy();
  });

  it("mostra 'Nenhum número cadastrado ainda.' quando a lista está vazia", async () => {
    listarMeusTelefonesMock.mockResolvedValue([]);

    render(<MeusTelefonesPage />);

    await waitFor(() => expect(screen.getByText("Nenhum número cadastrado ainda.")).toBeTruthy());
  });

  it("mostra 'Carregando...' enquanto os telefones ainda não chegaram", () => {
    listarMeusTelefonesMock.mockReturnValue(new Promise(() => {}));

    render(<MeusTelefonesPage />);

    expect(screen.getByText("Carregando...")).toBeTruthy();
  });
});

describe("MeusTelefonesPage — remover telefone", () => {
  it("pede confirmação via window.confirm antes de remover; cancelar não chama a API", async () => {
    listarMeusTelefonesMock.mockResolvedValue([makeTelefone()]);
    vi.spyOn(window, "confirm").mockReturnValue(false);

    render(<MeusTelefonesPage />);
    await waitFor(() => expect(screen.getByText("+5511987654321")).toBeTruthy());

    fireEvent.click(screen.getByText("Remover"));

    expect(window.confirm).toHaveBeenCalledWith(
      'Remover "+5511987654321"? Ele deixa de poder mandar mensagem em seu nome.',
    );
    expect(removerMeuTelefoneMock).not.toHaveBeenCalled();
  });

  it("confirmando, chama a API, mostra 'Removendo...' desabilitado, e remove a linha da lista ao concluir", async () => {
    listarMeusTelefonesMock.mockResolvedValue([makeTelefone({ id: "tel-1" })]);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { promise, resolve } = deferred<void>();
    removerMeuTelefoneMock.mockReturnValue(promise);

    render(<MeusTelefonesPage />);
    await waitFor(() => expect(screen.getByText("+5511987654321")).toBeTruthy());

    fireEvent.click(screen.getByText("Remover"));

    await waitFor(() => expect(screen.getByText("Removendo...")).toBeTruthy());
    expect((screen.getByText("Removendo...") as HTMLButtonElement).disabled).toBe(true);
    expect(removerMeuTelefoneMock).toHaveBeenCalledWith("tel-1");

    await act(async () => {
      resolve();
    });

    await waitFor(() => expect(screen.queryByText("+5511987654321")).toBeNull());
  });

  it("mostra mensagem de erro quando a remoção falha", async () => {
    listarMeusTelefonesMock.mockResolvedValue([makeTelefone({ id: "tel-1" })]);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    removerMeuTelefoneMock.mockRejectedValue(new Error("boom"));

    render(<MeusTelefonesPage />);
    await waitFor(() => expect(screen.getByText("+5511987654321")).toBeTruthy());

    await act(async () => {
      fireEvent.click(screen.getByText("Remover"));
    });

    await waitFor(() => expect(screen.getByText("Não foi possível remover o número.")).toBeTruthy());
    // A linha continua na lista já que a remoção falhou.
    expect(screen.getByText("+5511987654321")).toBeTruthy();
  });
});
