// Cobre a tela /admin/{tenantId} (detalhe do tenant + tabela de usuários) migrada
// para o DataGrid compartilhado: colunas Email/Status/Criado em/Ações, o botão
// "Editar" abrindo o modal de edição, e "Resetar senha" desabilitando e mostrando
// "Resetando..." enquanto a chamada está em voo. Segue o mesmo padrão de
// `entrada/__tests__/page.test.tsx` para páginas com `params: Promise<...>` (React `use`).

import { Suspense } from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import TenantDetalhePage from "@/app/admin/[tenantId]/page";
import { Tenant, UsuarioAdmin } from "@/lib/types";

const { buscarTenantMock, listarUsuariosDoTenantMock, resetarSenhaUsuarioMock } = vi.hoisted(() => ({
  buscarTenantMock: vi.fn(),
  listarUsuariosDoTenantMock: vi.fn(),
  resetarSenhaUsuarioMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/admin/t-1",
}));

vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return {
    ...actual,
    isAuthenticated: () => true,
    getPapel: () => "ADMIN_PRX",
    getTenantId: () => null,
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    buscarTenant: buscarTenantMock,
    listarUsuariosDoTenant: listarUsuariosDoTenantMock,
    resetarSenhaUsuario: resetarSenhaUsuarioMock,
  };
});

function makeTenant(overrides: Partial<Tenant> = {}): Tenant {
  return {
    id: "t-1",
    nomeFantasia: "Mercado Alfa",
    razaoSocial: "Alfa Comércio Ltda",
    cnpj: "111",
    status: "ATIVO",
    plano: "PRO",
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

function makeUsuario(overrides: Partial<UsuarioAdmin> = {}): UsuarioAdmin {
  return {
    id: "u-1",
    tenantId: "t-1",
    email: "operador@alfa.com",
    papel: "OPERADOR_CLIENTE",
    ativo: true,
    criadoEm: "2026-07-02T10:00:00Z",
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

async function renderPage(tenantId = "t-1") {
  await act(async () => {
    render(
      <Suspense fallback={null}>
        <TenantDetalhePage params={Promise.resolve({ tenantId })} />
      </Suspense>,
    );
  });
}

beforeEach(() => {
  buscarTenantMock.mockReset();
  listarUsuariosDoTenantMock.mockReset();
  resetarSenhaUsuarioMock.mockReset();
  buscarTenantMock.mockResolvedValue(makeTenant());
});

describe("TenantDetalhePage — tabela de usuários", () => {
  it("renderiza email, status (ATIVO/INATIVO) e data de criação de cada usuário", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([
      makeUsuario({ id: "u-1", email: "ativo@alfa.com", ativo: true }),
      makeUsuario({ id: "u-2", email: "inativo@alfa.com", ativo: false }),
    ]);

    await renderPage();

    await waitFor(() => expect(screen.getByText("ativo@alfa.com")).toBeTruthy());
    expect(screen.getByText("inativo@alfa.com")).toBeTruthy();
    // StatusBadge mapeia ATIVO/INATIVO para os labels "Ativo"/"Inativo" (ver StatusBadge.tsx).
    // Escopado à linha do usuário: o card do tenant no topo também mostra um badge "Ativo".
    const linhaAtivo = screen.getByText("ativo@alfa.com").closest("tr")!;
    const linhaInativo = screen.getByText("inativo@alfa.com").closest("tr")!;
    expect(linhaAtivo.textContent).toContain("Ativo");
    expect(linhaInativo.textContent).toContain("Inativo");
  });

  it("mostra 'Nenhum usuário cadastrado neste tenant ainda.' quando a lista está vazia", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() =>
      expect(screen.getByText("Nenhum usuário cadastrado neste tenant ainda.")).toBeTruthy(),
    );
  });

  // Nota: o estado "Carregando..." (loading=true no DataGrid) não é re-testado aqui
  // com uma promise pendente para sempre — combinado com o `use(params)` desta página
  // (Suspense), isso trava `act`/`waitFor` indefinidamente (interação conhecida entre
  // React 19 `use()` e o scheduler em jsdom, não um bug da tela). O mecanismo genérico
  // de `loading` do DataGrid já é coberto em components/grid/__tests__/DataGrid.test.tsx,
  // e o mesmo estado desta tela é coberto nas páginas irmãs sem `use(params)`
  // (AdminPage, AdministradoresPage, SelecionarTenantPage, MeusTelefonesPage).
});

describe("TenantDetalhePage — ação Editar", () => {
  it("clicar em 'Editar' na linha do usuário abre o modal de edição", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([makeUsuario({ email: "operador@alfa.com" })]);

    await renderPage();
    await waitFor(() => expect(screen.getByText("operador@alfa.com")).toBeTruthy());

    const linha = screen.getByText("operador@alfa.com").closest("tr")!;
    const botaoEditar = Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Editar")!;
    act(() => {
      botaoEditar.click();
    });

    expect(screen.getByText("Editar usuário")).toBeTruthy();
  });
});

describe("TenantDetalhePage — ação Resetar senha", () => {
  it("mostra 'Resetando...' e desabilita o botão enquanto a chamada está em voo, depois abre o modal com a senha", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([makeUsuario({ id: "u-1", email: "operador@alfa.com" })]);
    const { promise, resolve } = deferred<{ senha: string }>();
    resetarSenhaUsuarioMock.mockReturnValue(promise);

    await renderPage();
    await waitFor(() => expect(screen.getByText("operador@alfa.com")).toBeTruthy());

    const linha = screen.getByText("operador@alfa.com").closest("tr")!;
    const botaoResetar = Array.from(linha.querySelectorAll("button")).find(
      (b) => b.textContent === "Resetar senha",
    )! as HTMLButtonElement;

    act(() => {
      botaoResetar.click();
    });

    await waitFor(() => expect(screen.getByText("Resetando...")).toBeTruthy());
    expect((screen.getByText("Resetando...") as HTMLButtonElement).disabled).toBe(true);
    expect(resetarSenhaUsuarioMock).toHaveBeenCalledWith("t-1", "u-1");

    await act(async () => {
      resolve({ senha: "abc12345" });
    });

    await waitFor(() => expect(screen.getByText("abc12345")).toBeTruthy());
  });

  it("mostra mensagem de erro quando o reset falha", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([makeUsuario({ id: "u-1", email: "operador@alfa.com" })]);
    resetarSenhaUsuarioMock.mockRejectedValue(new Error("boom"));

    await renderPage();
    await waitFor(() => expect(screen.getByText("operador@alfa.com")).toBeTruthy());

    const linha = screen.getByText("operador@alfa.com").closest("tr")!;
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
