"use client";

import { createContext, useContext, useEffect, useSyncExternalStore, useState } from "react";
import { getAccessToken, getPapel, getTenantId, subscribeAccessToken } from "@/lib/auth";
import { refreshSession } from "@/lib/api";
import type { Papel } from "@/lib/types";

interface AuthContextValue {
  // Só fica true depois que o silent refresh do bootstrap termina (sucesso ou
  // falha) — é o sinal que AuthGuard espera antes de decidir redirecionar, senão uma
  // tela protegida piscaria "não autenticado" enquanto o refresh ainda está em voo.
  ready: boolean;
  authenticated: boolean;
  papel: Papel | null;
  tenantId: string | null;
}

const AuthContext = createContext<AuthContextValue>({
  ready: false,
  authenticated: false,
  papel: null,
  tenantId: null,
});

export function useAuth(): AuthContextValue {
  return useContext(AuthContext);
}

export default function AuthProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  // Reflete o access token em memória (lib/auth.ts) — muda em login/refresh/logout,
  // nenhum dos quais é estado React, daí o useSyncExternalStore em vez de useState.
  const token = useSyncExternalStore(subscribeAccessToken, getAccessToken, () => null);

  useEffect(() => {
    // Bootstrap: tenta restaurar a sessão a partir do cookie httpOnly refresh_token
    // antes de renderizar qualquer tela autenticada. Falha (sem cookie, expirado,
    // reuso já detectado) é um resultado normal — só significa "não estava logado".
    refreshSession().finally(() => setReady(true));
  }, []);

  const value: AuthContextValue = {
    ready,
    authenticated: token !== null,
    papel: getPapel(),
    tenantId: getTenantId(),
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
