import { ItemListaResponse } from "./types";

// IDs de produto únicos já resolvidos entre os itens de uma cotação — usado pra
// resolver nome via buscarProdutosPorIds (bounded pelo tamanho da lista da cotação),
// nunca pra carregar o catálogo inteiro do tenant.
export function idsProdutosDosItens(itens: ItemListaResponse[]): string[] {
  return Array.from(new Set(itens.map((i) => i.produtoIdEncontrado).filter((id): id is string => id != null)));
}
