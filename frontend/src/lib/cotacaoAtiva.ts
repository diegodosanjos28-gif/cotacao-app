// Rastreia qual cotação está "ativa" na sessão do usuário, para a navbar global
// saber pra onde levar cliques em Entrada/Comparativo/Mapa/Alertas fora de uma tela
// de cotação (ex: Visão Geral). Fonte de verdade continua sendo a URL quando ela
// traz um id — isto aqui só cobre o caso sem id na URL.

const KEY = "cotacao.ativaId";

export function getCotacaoAtivaId(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(KEY);
}

export function setCotacaoAtivaId(id: string): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(KEY, id);
}

// Chamado a cada login bem-sucedido — sem isso, um id de cotação de outro
// tenant/usuário (lembrado de uma sessão anterior no mesmo navegador) sobrevive à
// troca de identidade e quebra as abas Entrada/Comparativo/Mapa/Alertas com 404,
// já que a cotação não existe no tenant do usuário recém-logado (RLS filtra).
export function clearCotacaoAtivaId(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(KEY);
}
