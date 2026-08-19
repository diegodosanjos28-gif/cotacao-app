// Estado de sessão no cliente: o access token vive só em memória (nunca em
// localStorage/sessionStorage) — não sobrevive a um reload de página por design; quem
// restaura a sessão no boot é o silent refresh feito pelo AuthProvider (cookie
// httpOnly refresh_token, invisível a JS, é o que sobrevive ao reload). Ver
// components/AuthProvider.tsx.

import type { Papel } from "@/lib/types";

let accessToken: string | null = null;
const listeners = new Set<() => void>();

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
  listeners.forEach((listener) => listener());
}

// Usado pelo AuthProvider pra re-renderizar (papel/tenantId derivados do token) toda
// vez que login/refresh/logout muda o token em memória — accessToken não é estado
// React, então não há re-render automático sem isso.
export function subscribeAccessToken(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function isAuthenticated(): boolean {
  return accessToken !== null;
}

const REFRESH_MARGIN_MS = 30_000;

// Decodifica o payload do JWT sem validar assinatura — isso é responsabilidade do
// backend. Usado tanto para checar expiração quanto para ler claims como `papel`.
function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split(".")[1];
    return JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return null;
  }
}

function accessTokenExpiraEm(token: string): number | null {
  const json = decodeJwtPayload(token);
  return json && typeof json.exp === "number" ? json.exp * 1000 : null;
}

export function isAccessTokenExpiringSoon(): boolean {
  if (!accessToken) return false;
  const expiraEm = accessTokenExpiraEm(accessToken);
  if (expiraEm === null) return false;
  return expiraEm - Date.now() < REFRESH_MARGIN_MS;
}

// Lê a claim `papel` (ADMIN_PRX/OPERADOR_CLIENTE) do access token atual — usada para
// mostrar/esconder a navegação e as rotas administrativas no cliente. O backend é
// quem de fato garante a autorização (SecurityConfig `/admin/**`); esta checagem é só
// de UX, não de segurança.
export function getPapel(): Papel | null {
  if (!accessToken) return null;
  const json = decodeJwtPayload(accessToken);
  const papel = json?.papel;
  return papel === "ADMIN_PRX" || papel === "OPERADOR_CLIENTE" ? papel : null;
}

// Lê a claim `tenant_id` do access token atual. Para OPERADOR_CLIENTE é sempre o
// próprio tenant (nunca null). Para ADMIN_PRX é null no painel admin puro, e o id do
// tenant escolhido enquanto ele está "navegando" via POST /auth/selecionar-tenant —
// usado pelo AuthGuard/NavBar pra saber se um admin já escolheu um tenant. Assim como
// getPapel(), é só UX: a garantia de verdade é o backend (TenantFilter + RLS).
export function getTenantId(): string | null {
  if (!accessToken) return null;
  const json = decodeJwtPayload(accessToken);
  const tenantId = json?.tenant_id;
  return typeof tenantId === "string" ? tenantId : null;
}
