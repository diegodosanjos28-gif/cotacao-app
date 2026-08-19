// Cobre a tela /admin (lista de tenants) migrada de <table> manual para o DataGrid
// compartilhado: colunas exibidas, busca por nome fantasia/razão social/CNPJ, os dois
// estados vazios distintos (lista toda vazia vs. busca sem resultado), o estado de
// carregamento, e a navegação por clique de linha para /admin/{id}. A mecânica
// genérica do DataGrid (colSpan, onRowClick, classes) já é coberta por
// components/grid/__tests__/DataGrid.test.tsx — aqui só o comportamento específico
// desta tela.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor, fireEvent } from "@testing-library/react";
import AdminPage from "@/app/admin/page";
import { formatarData } from "@/lib/format";
import { Tenant } from "@/lib/types";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
const { listarTenantsMock } = vi.hoisted(() => ({ listarTenantsMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  usePathname: () => "/admin",
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
    listarTenants: listarTenantsMock,
  };
});

function makeTenant(overrides: Partial<Tenant> = {}): Tenant {
  return {
    id: "t-1",
    nomeFantasia: "Mercado Alfa",
    razaoSocial: "Alfa Comércio Ltda",
    cnpj: "11.222.333/0001-44",
    status: "ATIVO",
    plano: "PRO",
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

beforeEach(() => {
  pushMock.mockReset();
  listarTenantsMock.mockReset();
});

describe("AdminPage — carregamento", () => {
  it("mostra 'Carregando...' enquanto tenants ainda não chegaram", () => {
    listarTenantsMock.mockReturnValue(new Promise(() => {}));

    render(<AdminPage />);

    expect(screen.getByText("Carregando...")).toBeTruthy();
  });
});

describe("AdminPage — colunas e dados", () => {
  it("renderiza nome fantasia, CNPJ, status, plano e data de criação de cada tenant", async () => {
    const tenant = makeTenant();
    listarTenantsMock.mockResolvedValue([tenant]);

    render(<AdminPage />);

    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());
    expect(screen.getByText("11.222.333/0001-44")).toBeTruthy();
    expect(screen.getByText("Ativo")).toBeTruthy();
    expect(screen.getByText("PRO")).toBeTruthy();
    expect(screen.getByText(formatarData(tenant.criadoEm))).toBeTruthy();
  });

  it("mostra '—' quando CNPJ ou plano são nulos", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant({ cnpj: null, plano: null })]);

    render(<AdminPage />);

    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(2);
  });
});

describe("AdminPage — estados vazios", () => {
  it("mostra 'Nenhum tenant cadastrado ainda.' quando a lista completa está vazia", async () => {
    listarTenantsMock.mockResolvedValue([]);

    render(<AdminPage />);

    await waitFor(() => expect(screen.getByText("Nenhum tenant cadastrado ainda.")).toBeTruthy());
  });

  it("mostra 'Nenhum tenant encontrado para esta busca.' quando a busca filtra tudo", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant()]);

    render(<AdminPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText("Buscar por nome fantasia, razão social ou CNPJ..."), {
      target: { value: "não existe" },
    });

    await waitFor(() => expect(screen.getByText("Nenhum tenant encontrado para esta busca.")).toBeTruthy());
    expect(screen.queryByText("Nenhum tenant cadastrado ainda.")).toBeNull();
  });
});

describe("AdminPage — busca", () => {
  it("filtra por nome fantasia, razão social ou CNPJ (case-insensitive)", async () => {
    listarTenantsMock.mockResolvedValue([
      makeTenant({ id: "t-1", nomeFantasia: "Mercado Alfa", razaoSocial: "Alfa Ltda", cnpj: "111" }),
      makeTenant({ id: "t-2", nomeFantasia: "Padaria Beta", razaoSocial: "Beta ME", cnpj: "222" }),
    ]);

    render(<AdminPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());
    // Deixa o `useAsync` assentar completamente (setCarregando(false) roda num microtask
    // após o setData que a asserção acima já capturou) antes de disparar mais interações.
    await act(async () => {});

    fireEvent.change(screen.getByPlaceholderText("Buscar por nome fantasia, razão social ou CNPJ..."), {
      target: { value: "beta" },
    });

    expect(screen.queryByText("Mercado Alfa")).toBeNull();
    expect(screen.getByText("Padaria Beta")).toBeTruthy();
  });
});

describe("AdminPage — navegação", () => {
  it("clicar numa linha navega para /admin/{id}", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant({ id: "t-42" })]);

    render(<AdminPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.click(screen.getByText("Mercado Alfa").closest("tr")!);

    expect(pushMock).toHaveBeenCalledWith("/admin/t-42");
  });
});

describe("AdminPage — erro ao carregar", () => {
  it("mostra mensagem de erro quando listarTenants falha", async () => {
    listarTenantsMock.mockRejectedValue(new Error("boom"));

    render(<AdminPage />);

    await waitFor(() => expect(screen.getByText("Não foi possível carregar os tenants.")).toBeTruthy());
  });
});
