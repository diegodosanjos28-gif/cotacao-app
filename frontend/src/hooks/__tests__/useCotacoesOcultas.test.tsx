// useCotacoesOcultas — soft-hide client-side dos mini cards de "Cotações
// anteriores" (ver comentário no hook). Cobre: chave por tenant, persistência em
// localStorage, releitura ao trocar de tenant e resiliência a JSON corrompido.

import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useCotacoesOcultas } from "../useCotacoesOcultas";

const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }));

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return { ...actual, useAuth: useAuthMock };
});

function mockAuth(tenantId: string | null) {
  useAuthMock.mockReturnValue({ ready: true, authenticated: tenantId !== null, papel: "OPERADOR_CLIENTE", tenantId });
}

beforeEach(() => {
  useAuthMock.mockReset();
  localStorage.clear();
});

describe("useCotacoesOcultas", () => {
  it("sem tenantId, estaOculto é sempre false (Set vazio)", async () => {
    mockAuth(null);

    const { result } = renderHook(() => useCotacoesOcultas());

    await waitFor(() => expect(result.current.estaOculto("cot-1")).toBe(false));
  });

  it("ocultar(id) marca o id como oculto e persiste no localStorage sob a chave do tenant", async () => {
    mockAuth("t-1");

    const { result } = renderHook(() => useCotacoesOcultas());
    await waitFor(() => expect(result.current.estaOculto("cot-1")).toBe(false));

    act(() => {
      result.current.ocultar("cot-1");
    });

    await waitFor(() => expect(result.current.estaOculto("cot-1")).toBe(true));
    expect(JSON.parse(localStorage.getItem("cotacoes.ocultas.t-1")!)).toEqual(["cot-1"]);
    // Outro id não vira oculto de brinde.
    expect(result.current.estaOculto("cot-2")).toBe(false);
  });

  it("ao trocar de tenantId, relê do localStorage do novo tenant — não mistura ids", async () => {
    localStorage.setItem("cotacoes.ocultas.t-1", JSON.stringify(["cot-a"]));
    localStorage.setItem("cotacoes.ocultas.t-2", JSON.stringify(["cot-b"]));
    mockAuth("t-1");

    const { result, rerender } = renderHook(() => useCotacoesOcultas());

    await waitFor(() => expect(result.current.estaOculto("cot-a")).toBe(true));
    expect(result.current.estaOculto("cot-b")).toBe(false);

    mockAuth("t-2");
    rerender();

    await waitFor(() => expect(result.current.estaOculto("cot-b")).toBe(true));
    expect(result.current.estaOculto("cot-a")).toBe(false);
  });

  it("localStorage com JSON inválido não quebra o hook — cai para Set vazio", async () => {
    localStorage.setItem("cotacoes.ocultas.t-1", "{isso não é json válido");
    mockAuth("t-1");

    const { result } = renderHook(() => useCotacoesOcultas());

    await waitFor(() => expect(result.current.estaOculto("cot-a")).toBe(false));

    // Continua funcional depois da recuperação do erro — ocultar ainda funciona.
    act(() => {
      result.current.ocultar("cot-novo");
    });
    await waitFor(() => expect(result.current.estaOculto("cot-novo")).toBe(true));
  });
});
