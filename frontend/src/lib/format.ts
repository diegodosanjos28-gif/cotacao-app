export function formatarMoeda(valor: number | null | undefined): string {
  if (valor === null || valor === undefined) return "—";
  return valor.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

export function formatarQuantidade(valor: number | null | undefined): string {
  if (valor === null || valor === undefined) return "—";
  return Number.isInteger(valor) ? String(valor) : valor.toLocaleString("pt-BR");
}

export function formatarData(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
}

export function formatarPercentual(valor: number | null | undefined, casas = 1): string {
  if (valor === null || valor === undefined) return "—";
  return `${valor.toLocaleString("pt-BR", { minimumFractionDigits: casas, maximumFractionDigits: casas })}%`;
}
