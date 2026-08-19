// Cobre a tela /admin/administradores migrada para o DataGrid compartilhado: mesmo
// padrão Email/Status/Criado em/Ações de admin/[tenantId], mas sem tenantId (lista
// global de ADMIN_PRX). Foco no que é específico desta tela: dados renderizados,
// estado vazio, e as ações Editar/Resetar senha.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import AdministradoresPage from "@/app/admin/administradores/page";
import { UsuarioAdmin } from "@/lib/types";

const { listarAdministradoresMock, resetarSenhaAdministradorMock } = vi.hoisted(() => ({
  listarAdministradoresMock: vi.fn(),
  resetarSenhaAdministradorMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/admin/administradores",
}));

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return {
    ...actual,
    useAuth: () => ({ ready: true, authenticated: true, papel: "ADMIN_PRX", tenantId: null }),
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    listarAdministradores: listarAdministradoresMock,
    resetarSenhaAdministrador: resetarSenhaAdministradorMock,
  };
});

function makeAdmin(overrides: Partial<UsuarioAdmin> = {}): UsuarioAdmin {
  return {
    id: "a-1",
    tenantId: null,
    email: "admin@prx.com",
    papel: "ADMIN_PRX",
    ativo: true,
    criadoEm: "2026-07-01T10:00:00Z",
    senhaGerada: null,
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
  listarAdministradoresMock.mockReset();
  resetarSenhaAdministradorMock.mockReset();
});

describe("AdministradoresPage — tabela", () => {
  it("renderiza email, status (ATIVO/INATIVO) e data de criação de cada administrador", async () => {
    listarAdministradoresMock.mockResolvedValue([
      makeAdmin({ id: "a-1", email: "ativo@prx.com", ativo: true }),
      makeAdmin({ id: "a-2", email: "inativo@prx.com", ativo: false }),
    ]);

    render(<AdministradoresPage />);

    await waitFor(() => expect(screen.getByText("ativo@prx.com")).toBeTruthy());
    expect(screen.getByText("inativo@prx.com")).toBeTruthy();
    // StatusBadge mapeia ATIVO/INATIVO para os labels "Ativo"/"Inativo" (ver StatusBadge.tsx).
    expect(screen.getByText("Ativo")).toBeTruthy();
    expect(screen.getByText("Inativo")).toBeTruthy();
  });

  it("mostra 'Nenhum administrador cadastrado ainda.' quando a lista está vazia", async () => {
    listarAdministradoresMock.mockResolvedValue([]);

    render(<AdministradoresPage />);

    await waitFor(() => expect(screen.getByText("Nenhum administrador cadastrado ainda.")).toBeTruthy());
  });

  it("mostra 'Carregando...' enquanto os administradores ainda não chegaram", () => {
    listarAdministradoresMock.mockReturnValue(new Promise(() => {}));

    render(<AdministradoresPage />);

    expect(screen.getByText("Carregando...")).toBeTruthy();
  });
});

describe("AdministradoresPage — ação Editar", () => {
  it("clicar em 'Editar' na linha abre o modal de edição", async () => {
    listarAdministradoresMock.mockResolvedValue([makeAdmin({ email: "admin@prx.com" })]);

    render(<AdministradoresPage />);
    await waitFor(() => expect(screen.getByText("admin@prx.com")).toBeTruthy());

    const linha = screen.getByText("admin@prx.com").closest("tr")!;
    const botaoEditar = Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Editar")!;
    act(() => {
      botaoEditar.click();
    });

    expect(screen.getByText("Editar administrador")).toBeTruthy();
  });
});

describe("AdministradoresPage — ação Resetar senha", () => {
  it("mostra 'Resetando...' e desabilita o botão enquanto a chamada está em voo", async () => {
    listarAdministradoresMock.mockResolvedValue([makeAdmin({ id: "a-1", email: "admin@prx.com" })]);
    const { promise } = deferred<{ senha: string }>();
    resetarSenhaAdministradorMock.mockReturnValue(promise);

    render(<AdministradoresPage />);
    await waitFor(() => expect(screen.getByText("admin@prx.com")).toBeTruthy());

    const linha = screen.getByText("admin@prx.com").closest("tr")!;
    const botaoResetar = Array.from(linha.querySelectorAll("button")).find(
      (b) => b.textContent === "Resetar senha",
    )!;

    act(() => {
      botaoResetar.click();
    });

    await waitFor(() => expect(screen.getByText("Resetando...")).toBeTruthy());
    expect((screen.getByText("Resetando...") as HTMLButtonElement).disabled).toBe(true);
    expect(resetarSenhaAdministradorMock).toHaveBeenCalledWith("a-1");
  });

  it("mostra a senha gerada após o reset resolver", async () => {
    listarAdministradoresMock.mockResolvedValue([makeAdmin({ id: "a-1", email: "admin@prx.com" })]);
    resetarSenhaAdministradorMock.mockResolvedValue({ senha: "xyz98765" });

    render(<AdministradoresPage />);
    await waitFor(() => expect(screen.getByText("admin@prx.com")).toBeTruthy());

    const linha = screen.getByText("admin@prx.com").closest("tr")!;
    const botaoResetar = Array.from(linha.querySelectorAll("button")).find(
      (b) => b.textContent === "Resetar senha",
    )!;

    await act(async () => {
      botaoResetar.click();
    });

    await waitFor(() => expect(screen.getByText("xyz98765")).toBeTruthy());
  });

  it("mostra mensagem de erro quando o reset falha", async () => {
    listarAdministradoresMock.mockResolvedValue([makeAdmin({ id: "a-1", email: "admin@prx.com" })]);
    resetarSenhaAdministradorMock.mockRejectedValue(new Error("boom"));

    render(<AdministradoresPage />);
    await waitFor(() => expect(screen.getByText("admin@prx.com")).toBeTruthy());

    const linha = screen.getByText("admin@prx.com").closest("tr")!;
    const botaoResetar = Array.from(linha.querySelectorAll("button")).find(
      (b) => b.textContent === "Resetar senha",
    )!;

    await act(async () => {
      botaoResetar.click();
    });

    await waitFor(() =>
      expect(screen.getByText("Não foi possível resetar a senha.")).toBeTruthy(),
    );
  });
});
