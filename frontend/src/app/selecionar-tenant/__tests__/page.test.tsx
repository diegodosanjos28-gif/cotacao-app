// Cobre a tela /selecionar-tenant (seletor de tenant para ADMIN_PRX) migrada para o
// DataGrid compartilhado: coluna extra "Navegar →"/"Entrando...", clique de linha
// chamando onSelecionar, e principalmente o guard contra duplo clique — enquanto uma
// seleção está em voo (`selecionando !== null`), clicar em outra linha não deve
// disparar uma segunda chamada. Esse guard migrou de dentro do <tr onClick> original
// para a prop onRowClick do DataGrid; é o comportamento mais arriscado de quebrar
// silenciosamente numa migração de markup, por isso o teste dedicado.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import SelecionarTenantPage from "@/app/selecionar-tenant/page";
import { Tenant } from "@/lib/types";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
const { listarTenantsMock, selecionarTenantMock } = vi.hoisted(() => ({
  listarTenantsMock: vi.fn(),
  selecionarTenantMock: vi.fn(),
}));
const { setTokensMock, clearCotacaoAtivaIdMock } = vi.hoisted(() => ({
  setTokensMock: vi.fn(),
  clearCotacaoAtivaIdMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  usePathname: () => "/selecionar-tenant",
}));

vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return {
    ...actual,
    isAuthenticated: () => true,
    getPapel: () => "ADMIN_PRX",
    getTenantId: () => null,
    setTokens: setTokensMock,
  };
});

vi.mock("@/lib/cotacaoAtiva", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/cotacaoAtiva")>();
  return {
    ...actual,
    clearCotacaoAtivaId: clearCotacaoAtivaIdMock,
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    listarTenants: listarTenantsMock,
    selecionarTenant: selecionarTenantMock,
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (err: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  pushMock.mockReset();
  listarTenantsMock.mockReset();
  selecionarTenantMock.mockReset();
  setTokensMock.mockReset();
  clearCotacaoAtivaIdMock.mockReset();
});

describe("SelecionarTenantPage — listagem", () => {
  it("mostra 'Navegar →' na coluna de ação de cada tenant listado", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant()]);

    render(<SelecionarTenantPage />);

    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());
    expect(screen.getByText("Navegar →")).toBeTruthy();
  });

  it("mostra 'Nenhum tenant cadastrado ainda.' quando não há tenants", async () => {
    listarTenantsMock.mockResolvedValue([]);

    render(<SelecionarTenantPage />);

    await waitFor(() => expect(screen.getByText("Nenhum tenant cadastrado ainda.")).toBeTruthy());
  });

  it("filtra por busca e mostra a mensagem de busca sem resultado", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant({ nomeFantasia: "Mercado Alfa" })]);

    render(<SelecionarTenantPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText("Buscar por nome fantasia, razão social ou CNPJ..."), {
      target: { value: "zzz" },
    });

    await waitFor(() => expect(screen.getByText("Nenhum tenant encontrado para esta busca.")).toBeTruthy());
  });
});

describe("SelecionarTenantPage — selecionar um tenant (clique de linha)", () => {
  it("clicar numa linha seleciona o tenant, salva tokens, limpa a cotação ativa e navega para '/'", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant({ id: "t-42" })]);
    selecionarTenantMock.mockResolvedValue({ accessToken: "acc", refreshToken: "ref" });

    render(<SelecionarTenantPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.click(screen.getByText("Mercado Alfa").closest("tr")!);

    await waitFor(() => expect(selecionarTenantMock).toHaveBeenCalledWith("t-42"));
    expect(setTokensMock).toHaveBeenCalledWith("acc", "ref");
    expect(clearCotacaoAtivaIdMock).toHaveBeenCalled();
    expect(pushMock).toHaveBeenCalledWith("/");
  });

  it("mostra 'Entrando...' na linha em voo enquanto a seleção não resolveu", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant({ id: "t-42" })]);
    const { promise } = deferred<{ accessToken: string; refreshToken: string }>();
    selecionarTenantMock.mockReturnValue(promise);

    render(<SelecionarTenantPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.click(screen.getByText("Mercado Alfa").closest("tr")!);

    await waitFor(() => expect(screen.getByText("Entrando...")).toBeTruthy());
  });

  it("clicar no botão 'Painel administrativo' seleciona tenantId=null e navega para /admin", async () => {
    listarTenantsMock.mockResolvedValue([]);
    selecionarTenantMock.mockResolvedValue({ accessToken: "acc", refreshToken: "ref" });

    render(<SelecionarTenantPage />);
    await waitFor(() => expect(screen.getByText("Nenhum tenant cadastrado ainda.")).toBeTruthy());

    fireEvent.click(screen.getByText("Painel administrativo (nenhum tenant)"));

    await waitFor(() => expect(selecionarTenantMock).toHaveBeenCalledWith(null));
    expect(pushMock).toHaveBeenCalledWith("/admin");
  });

  it("mostra erro e libera a seleção quando selecionarTenant falha", async () => {
    listarTenantsMock.mockResolvedValue([makeTenant({ id: "t-42" })]);
    selecionarTenantMock.mockRejectedValue(new Error("boom"));

    render(<SelecionarTenantPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.click(screen.getByText("Mercado Alfa").closest("tr")!);

    await waitFor(() =>
      expect(screen.getByText("Não foi possível selecionar o tenant.")).toBeTruthy(),
    );
    expect(pushMock).not.toHaveBeenCalled();
    // Seleção liberada — o botão de ação volta a mostrar "Navegar →".
    expect(screen.getByText("Navegar →")).toBeTruthy();
  });
});

describe("SelecionarTenantPage — guard contra duplo clique durante seleção em voo", () => {
  it("clicar numa segunda linha enquanto a primeira seleção ainda está em voo é um no-op", async () => {
    listarTenantsMock.mockResolvedValue([
      makeTenant({ id: "t-1", nomeFantasia: "Mercado Alfa" }),
      makeTenant({ id: "t-2", nomeFantasia: "Padaria Beta" }),
    ]);
    const { promise, resolve } = deferred<{ accessToken: string; refreshToken: string }>();
    selecionarTenantMock.mockReturnValue(promise);

    render(<SelecionarTenantPage />);
    await waitFor(() => expect(screen.getByText("Mercado Alfa")).toBeTruthy());

    fireEvent.click(screen.getByText("Mercado Alfa").closest("tr")!);
    await waitFor(() => expect(selecionarTenantMock).toHaveBeenCalledTimes(1));

    // Segunda linha clicada antes da primeira seleção resolver — deve ser ignorado.
    fireEvent.click(screen.getByText("Padaria Beta").closest("tr")!);
    expect(selecionarTenantMock).toHaveBeenCalledTimes(1);
    expect(selecionarTenantMock).toHaveBeenCalledWith("t-1");

    resolve({ accessToken: "acc", refreshToken: "ref" });
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/"));
    // Ainda só a primeira seleção foi feita.
    expect(selecionarTenantMock).toHaveBeenCalledTimes(1);
  });
});
