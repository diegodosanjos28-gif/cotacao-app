// Agregações derivadas client-side sobre HistoricoPrecoProduto[] — mesmo espírito de
// comparativo.ts: o backend só entrega os pontos de referência já resolvidos (menor
// oferta válida por cotação finalizada, ver HistoricoPrecoService), toda a
// classificação/partição acontece aqui.

import { HistoricoPrecoProduto } from "./types";

// Tolerância do protótipo validado (HIST_TOLERANCE, COTA&TESTA - 14.07 - V5.html):
// abaixo desse módulo de variação entre os 2 pontos mais recentes, a tendência é
// classificada como estável em vez de alta/queda.
export const LIMIAR_ESTAVEL_PCT = 2;

export type Tendencia = "ESTAVEL" | "ALTA" | "QUEDA" | "NOVO";

// >= 2 pontos: cotação atual + ao menos 1 referência anterior, mínimo pra comparar.
export function temHistorico(p: HistoricoPrecoProduto): boolean {
  return p.pontos.length >= 2;
}

export function precoMaisRecente(p: HistoricoPrecoProduto): number | null {
  return p.pontos[0]?.preco ?? null;
}

/** Variação % entre pontos[indice] e pontos[indice+1] (referência anterior). null se não houver ponto mais antigo. */
export function variacaoEntrePontos(p: HistoricoPrecoProduto, indice: number): number | null {
  const atual = p.pontos[indice];
  const anterior = p.pontos[indice + 1];
  if (!atual || !anterior || anterior.preco === 0) return null;
  return ((atual.preco - anterior.preco) / anterior.preco) * 100;
}

/** Variação entre os 2 pontos mais recentes — null se o produto tem menos de 2 pontos. */
export function variacaoRecente(p: HistoricoPrecoProduto): number | null {
  return temHistorico(p) ? variacaoEntrePontos(p, 0) : null;
}

/**
 * null quando o produto nunca foi cotado (0 pontos — não há preço nenhum a
 * classificar). "NOVO" quando há preço atual mas nenhuma referência anterior pra
 * comparar (1 ponto só) — mesmo estado 'novo' de classificarPrecoHistorico() no
 * protótipo.
 */
export function tendencia(p: HistoricoPrecoProduto): Tendencia | null {
  if (p.pontos.length === 0) return null;
  if (p.pontos.length === 1) return "NOVO";
  const variacao = variacaoRecente(p)!;
  if (Math.abs(variacao) < LIMIAR_ESTAVEL_PCT) return "ESTAVEL";
  return variacao > 0 ? "ALTA" : "QUEDA";
}

export function produtosComHistorico(produtos: HistoricoPrecoProduto[]): HistoricoPrecoProduto[] {
  return produtos.filter(temHistorico);
}

// Partição exata de produtosComHistorico junto com produtosComOportunidade: empate
// (variação == 0) conta como "acima" — "preço atual igual ou acima da referência
// anterior" no texto do requisito.
export function produtosAcimaDaUltimaReferencia(produtos: HistoricoPrecoProduto[]): HistoricoPrecoProduto[] {
  return produtosComHistorico(produtos).filter((p) => (variacaoRecente(p) ?? 0) >= 0);
}

export function produtosComOportunidade(produtos: HistoricoPrecoProduto[]): HistoricoPrecoProduto[] {
  return produtosComHistorico(produtos).filter((p) => (variacaoRecente(p) ?? 0) < 0);
}
