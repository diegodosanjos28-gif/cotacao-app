// Regressão do bug de 2026-07-18: o backend caía em 403 pra token expirado (Spring
// Security sem AuthenticationEntryPoint custom) e o interceptor de 401 aqui nunca
// disparava de verdade em produção. Esses testes cobrem o interceptor isoladamente,
// assumindo que o backend agora devolve 401 corretamente (RestAuthenticationEntryPointTest
// no backend cobre esse lado). vi.resetModules() + import dinâmico por teste porque
// api.ts guarda o lock de refresh (refreshInFlight) em estado de módulo.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

function makeJwt(expSeconds: number): string {
  const header = btoa(JSON.stringify({ alg: "none", typ: "JWT" }));
  const payload = btoa(JSON.stringify({ exp: expSeconds }));
  return `${header}.${payload}.sig`;
}

const FAR_FUTURE = Math.floor(Date.now() / 1000) + 3600;

describe("api.ts — interceptor de 401", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("401 dispara refresh e repete a chamada original com sucesso", async () => {
    const tokenAntigo = makeJwt(FAR_FUTURE);
    localStorage.setItem("cotacao.accessToken", tokenAntigo);
    localStorage.setItem("cotacao.refreshToken", "refresh-original");

    let refreshCalls = 0;
    let cotacaoCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        refreshCalls++;
        return new Response(
          JSON.stringify({ accessToken: makeJwt(FAR_FUTURE), refreshToken: "refresh-novo" }),
          { status: 200 },
        );
      }
      if (url.includes("/cotacoes/abc")) {
        cotacaoCalls++;
        if (cotacaoCalls === 1) return new Response(null, { status: 401 });
        return new Response(JSON.stringify({ id: "abc" }), { status: 200 });
      }
      throw new Error("URL inesperada: " + url);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { buscarCotacao } = await import("@/lib/api");
    const resultado = await buscarCotacao("abc");

    expect(resultado).toEqual({ id: "abc" });
    expect(refreshCalls).toBe(1);
    expect(cotacaoCalls).toBe(2);
  });

  it("múltiplas requisições falhando por 401 ao mesmo tempo disparam só um refresh (single-flight)", async () => {
    const tokenAntigo = makeJwt(FAR_FUTURE);
    localStorage.setItem("cotacao.accessToken", tokenAntigo);
    localStorage.setItem("cotacao.refreshToken", "refresh-original");

    let refreshCalls = 0;
    let liberarRefresh: () => void = () => {};
    const gateDoRefresh = new Promise<void>((resolve) => {
      liberarRefresh = resolve;
    });

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const authHeader = (init?.headers as Headers | undefined)?.get?.("Authorization");

      if (url.includes("/auth/refresh")) {
        refreshCalls++;
        await gateDoRefresh;
        return new Response(
          JSON.stringify({ accessToken: makeJwt(FAR_FUTURE + 1), refreshToken: "refresh-novo" }),
          { status: 200 },
        );
      }
      if (url.includes("/cotacoes/")) {
        if (authHeader === `Bearer ${tokenAntigo}`) {
          return new Response(null, { status: 401 });
        }
        const id = url.split("/").pop();
        return new Response(JSON.stringify({ id }), { status: 200 });
      }
      throw new Error("URL inesperada: " + url);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { buscarCotacao } = await import("@/lib/api");

    const p1 = buscarCotacao("a");
    const p2 = buscarCotacao("b");

    // Deixa as duas chamadas baterem 401 e entrarem em tryRefresh (a segunda deve
    // encontrar o lock já ocupado) antes de liberar a resposta do refresh.
    await new Promise((resolve) => setTimeout(resolve, 0));
    liberarRefresh();

    const [r1, r2] = await Promise.all([p1, p2]);

    expect(r1).toEqual({ id: "a" });
    expect(r2).toEqual({ id: "b" });
    expect(refreshCalls).toBe(1);
  });

  it("refresh também falhando limpa a sessão e rejeita com ApiError", async () => {
    localStorage.setItem("cotacao.accessToken", makeJwt(FAR_FUTURE));
    localStorage.setItem("cotacao.refreshToken", "refresh-invalido");

    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) return new Response(null, { status: 401 });
      if (url.includes("/cotacoes/")) return new Response(null, { status: 401 });
      throw new Error("URL inesperada: " + url);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { buscarCotacao, ApiError } = await import("@/lib/api");

    await expect(buscarCotacao("abc")).rejects.toThrow(ApiError);
    expect(localStorage.getItem("cotacao.accessToken")).toBeNull();
    expect(localStorage.getItem("cotacao.refreshToken")).toBeNull();
  });

  it("renova proativamente quando o access token está perto de expirar, sem esperar um 401", async () => {
    const tokenQuaseExpirado = makeJwt(Math.floor(Date.now() / 1000) + 5); // ~5s pra expirar
    localStorage.setItem("cotacao.accessToken", tokenQuaseExpirado);
    localStorage.setItem("cotacao.refreshToken", "refresh-original");

    let refreshCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        refreshCalls++;
        return new Response(
          JSON.stringify({ accessToken: makeJwt(FAR_FUTURE), refreshToken: "refresh-novo" }),
          { status: 200 },
        );
      }
      if (url.includes("/cotacoes/abc")) {
        const authHeader = (init?.headers as Headers | undefined)?.get?.("Authorization");
        // Se renovou de verdade antes de mandar, o header já vem com o token novo.
        expect(authHeader).not.toBe(`Bearer ${tokenQuaseExpirado}`);
        return new Response(JSON.stringify({ id: "abc" }), { status: 200 });
      }
      throw new Error("URL inesperada: " + url);
    });
    vi.stubGlobal("fetch", fetchMock);

    const { buscarCotacao } = await import("@/lib/api");
    await buscarCotacao("abc");

    expect(refreshCalls).toBe(1);
  });
});
