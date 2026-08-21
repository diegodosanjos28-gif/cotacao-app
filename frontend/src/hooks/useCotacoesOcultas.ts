"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/components/AuthProvider";

// Soft-hide client-side dos mini cards de "Cotações anteriores" (botão "Fechar") —
// decisão deliberada do refactor da Entrada de Dados (2026-08-20): sem persistência
// no backend, sem migration. Sobrevive a reload no mesmo navegador (localStorage por
// tenant), mas não atravessa dispositivo/navegador diferente.
function chave(tenantId: string): string {
  return `cotacoes.ocultas.${tenantId}`;
}

export function useCotacoesOcultas() {
  const { tenantId } = useAuth();
  const [ocultos, setOcultos] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!tenantId) {
      // Sincroniza com a claim do token (fonte externa) — mesmo padrão já usado em
      // NavBar/AuthProvider para outros campos derivados do JWT.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setOcultos(new Set());
      return;
    }
    try {
      const raw = localStorage.getItem(chave(tenantId));
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setOcultos(raw ? new Set(JSON.parse(raw)) : new Set());
    } catch {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setOcultos(new Set());
    }
  }, [tenantId]);

  const ocultar = useCallback(
    (id: string) => {
      if (!tenantId) return;
      setOcultos((atual) => {
        const novo = new Set(atual);
        novo.add(id);
        localStorage.setItem(chave(tenantId), JSON.stringify([...novo]));
        return novo;
      });
    },
    [tenantId],
  );

  const estaOculto = useCallback((id: string) => ocultos.has(id), [ocultos]);

  return { ocultar, estaOculto };
}
