import { describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useAsync } from "@/hooks/useAsync";
import { ApiError } from "@/lib/api";

describe("useAsync", () => {
  it("mostra erro visível quando o fetcher nunca assenta (timeout), em vez de loading infinito", async () => {
    const fetcher = () => new Promise<string>(() => {});
    const { result } = renderHook(() => useAsync(fetcher, [], "erro padrão", { timeoutMs: 20 }));

    expect(result.current.carregando).toBe(true);

    await waitFor(() => {
      expect(result.current.carregando).toBe(false);
    });

    expect(result.current.erro).toBe("Tempo esgotado ao carregar. Tente novamente.");
    expect(result.current.data).toBeNull();
  });

  it("resolve normalmente quando o fetcher assenta antes do timeout", async () => {
    const fetcher = () => Promise.resolve("dados");
    const { result } = renderHook(() => useAsync(fetcher, [], "erro padrão", { timeoutMs: 5000 }));

    await waitFor(() => {
      expect(result.current.carregando).toBe(false);
    });

    expect(result.current.data).toBe("dados");
    expect(result.current.erro).toBeNull();
  });

  it("erro do fetcher (não timeout) ainda aparece normalmente", async () => {
    const fetcher = () => Promise.reject(new ApiError("falhou", 500));
    const { result } = renderHook(() => useAsync(fetcher, [], "erro padrão", { timeoutMs: 5000 }));

    await waitFor(() => {
      expect(result.current.carregando).toBe(false);
    });

    expect(result.current.erro).toBe("falhou");
  });
});
