// Armazenamento de tokens no cliente (localStorage) — a API é stateless (JWT via
// Authorization header), sem sessão de servidor. Ver seção 2.1/4 da doc técnica:
// Next.js é um SPA puro consumindo a API via HTTPS/JSON.

import type { Papel } from "@/lib/types";

const ACCESS_KEY = "cotacao.accessToken";
const REFRESH_KEY = "cotacao.refreshToken";

export function getAccessToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(ACCESS_KEY);
}

export function getRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(REFRESH_KEY);
}

export function setTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem(ACCESS_KEY, accessToken);
  localStorage.setItem(REFRESH_KEY, refreshToken);
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
}

export function isAuthenticated(): boolean {
  return getAccessToken() !== null;
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
  const token = getAccessToken();
  if (!token) return false;
  const expiraEm = accessTokenExpiraEm(token);
  if (expiraEm === null) return false;
  return expiraEm - Date.now() < REFRESH_MARGIN_MS;
}

// Lê a claim `papel` (ADMIN_PRX/OPERADOR_CLIENTE) do access token atual — usada para
// mostrar/esconder a navegação e as rotas administrativas no cliente. O backend é
// quem de fato garante a autorização (SecurityConfig `/admin/**`); esta checagem é só
// de UX, não de segurança.
export function getPapel(): Papel | null {
  const token = getAccessToken();
  if (!token) return null;
  const json = decodeJwtPayload(token);
  const papel = json?.papel;
  return papel === "ADMIN_PRX" || papel === "OPERADOR_CLIENTE" ? papel : null;
}

// Lê a claim `tenant_id` do access token atual. Para OPERADOR_CLIENTE é sempre o
// próprio tenant (nunca null). Para ADMIN_PRX é null no painel admin puro, e o id do
// tenant escolhido enquanto ele está "navegando" via POST /auth/selecionar-tenant —
// usado pelo AuthGuard/NavBar pra saber se um admin já escolheu um tenant. Assim como
// getPapel(), é só UX: a garantia de verdade é o backend (TenantFilter + RLS).
export function getTenantId(): string | null {
  const token = getAccessToken();
  if (!token) return null;
  const json = decodeJwtPayload(token);
  const tenantId = json?.tenant_id;
  return typeof tenantId === "string" ? tenantId : null;
}

// Único ponto de saída: limpa os tokens e força um reload completo pra /login.
// Usado tanto pelo botão "Sair" (NavBar) quanto pelo fetch wrapper (api.ts) quando
// o refresh falha — window.location.href em vez de router.replace porque este
// módulo não é um componente React (sem acesso a useRouter) e o hard redirect
// garante que nenhum estado de componente sobrevive ao logout.
export function logout(): void {
  clearTokens();
  if (typeof window !== "undefined") window.location.href = "/login";
}
