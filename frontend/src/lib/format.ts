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

// "há 12 min" / "há 1h20" — card "Cotação atual" e Hall dos Fornecedores (landing).
export function formatarTempoRelativo(iso: string | null | undefined): string {
  if (!iso) return "—";
  const diffMin = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (diffMin < 1) return "agora mesmo";
  if (diffMin < 60) return `há ${diffMin} min`;
  const horas = Math.floor(diffMin / 60);
  const minRestantes = diffMin % 60;
  return `há ${horas}h${minRestantes > 0 ? String(minRestantes).padStart(2, "0") : ""}`;
}

// "42 min" / "1h10" — Hall dos Fornecedores modo histórico (tempo médio de resposta).
export function formatarDuracaoMinutos(minutos: number): string {
  if (minutos < 60) return `${Math.round(minutos)} min`;
  const horas = Math.floor(minutos / 60);
  const minRestantes = Math.round(minutos % 60);
  return `${horas}h${minRestantes > 0 ? String(minRestantes).padStart(2, "0") : ""}`;
}
