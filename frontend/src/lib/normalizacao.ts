// Espelha MatchingProdutoService.normTxt do backend: remove acento, minusculo,
// colapsa espaco - usado no autocomplete de fornecedor (client-side, sem endpoint de
// busca fuzzy server-side hoje).
const DIACRITICOS = /[̀-ͯ]/g;

export function normTxt(texto: string | null | undefined): string {
  if (!texto) return "";
  return texto
    .normalize("NFD")
    .replace(DIACRITICOS, "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}
