import { formatarMoeda } from "@/lib/format";
import { CenarioSelecionado } from "@/lib/types";

const TOKEN: Record<CenarioSelecionado, { cor: string; bg: string; dot: string; nome: string }> = {
  MENOR_PRECO: { cor: "text-cen1", bg: "border-cen1 bg-cen1/10", dot: "bg-cen1", nome: "Menor Preço" },
  EQUILIBRADA: { cor: "text-cen2", bg: "border-cen2 bg-cen2/10", dot: "bg-cen2", nome: "Compra Equilibrada" },
  MELHOR_PRAZO: { cor: "text-cen3", bg: "border-cen3 bg-cen3/10", dot: "bg-cen3", nome: "Melhor Prazo" },
};

function textoImpacto(
  valor: CenarioSelecionado,
  diffVsMenorPreco: number,
  abaixoMinimoCount: number,
  fornecedoresReduzidos: number,
): string {
  if (valor === "MENOR_PRECO") return "";

  const mins = abaixoMinimoCount === 0 ? "Todos os mínimos atendidos" : `${abaixoMinimoCount} mínimo(s) abaixo`;

  if (valor === "EQUILIBRADA") {
    const partes: string[] = [];
    if (fornecedoresReduzidos > 0) partes.push(`Reduz ${fornecedoresReduzidos} fornecedor(es)`);
    partes.push(mins);
    if (diffVsMenorPreco > 0) partes.push(`Acréscimo de ${formatarMoeda(diffVsMenorPreco)}`);
    return partes.join(" · ");
  }

  // MELHOR_PRAZO: o protótipo cita o fornecedor com melhor prazo, mas o backend só
  // expõe prazoEntregaPadrao como texto livre (parseado só internamente pelo
  // PrazoEntregaParser) — sem duplicar essa heurística no frontend, texto genérico.
  return `Prioriza fornecedores com melhor prazo de entrega · ${abaixoMinimoCount === 0 ? "Mínimos atendidos" : mins}`;
}

function badgeDiff(valor: CenarioSelecionado, diffVsMenorPreco: number): string {
  if (valor === "MENOR_PRECO") return "Referência";
  if (Math.abs(diffVsMenorPreco) < 0.01) return "Mesmo custo";
  const sinal = diffVsMenorPreco > 0 ? "+" : "";
  return `${sinal}${formatarMoeda(diffVsMenorPreco)} vs Menor Preço`;
}

export default function CenarioCard({
  valor,
  ativo,
  total,
  totalFornecedores,
  totalProdutos,
  abaixoMinimoCount,
  diffVsMenorPreco,
  fornecedoresReduzidos = 0,
  onClick,
}: {
  valor: CenarioSelecionado;
  ativo: boolean;
  total: number;
  totalFornecedores: number;
  totalProdutos: number;
  abaixoMinimoCount: number;
  diffVsMenorPreco: number;
  fornecedoresReduzidos?: number;
  onClick: () => void;
}) {
  const t = TOKEN[valor];
  const impacto = textoImpacto(valor, diffVsMenorPreco, abaixoMinimoCount, fornecedoresReduzidos);

  return (
    <button
      onClick={onClick}
      aria-pressed={ativo}
      className={`rounded-lg border p-5 text-left transition-colors ${
        ativo ? `${t.bg} shadow-sm` : "border-bdr hover:bg-hov"
      }`}
    >
      <p className={`text-sm font-semibold ${t.cor}`}>{t.nome}</p>
      <p className={`mt-1 text-xl font-semibold ${ativo ? t.cor : "text-t1"}`}>{formatarMoeda(total)}</p>
      <div className="mt-2 flex flex-wrap gap-3 text-xs text-t2">
        <span>{totalFornecedores} fornecedor(es)</span>
        <span>{totalProdutos} produto(s)</span>
        {abaixoMinimoCount > 0 ? (
          <span className="text-wa">{abaixoMinimoCount} abaixo do mínimo</span>
        ) : (
          <span className="text-ok">Mínimos ✓</span>
        )}
      </div>
      <span
        className={`mt-2 inline-block rounded px-1.5 py-0.5 text-[11px] font-semibold ${
          valor === "MENOR_PRECO" || Math.abs(diffVsMenorPreco) < 0.01 ? "text-ok" : "text-wa"
        }`}
      >
        {badgeDiff(valor, diffVsMenorPreco)}
      </span>
      {impacto && <p className="mt-1 text-xs text-t2">{impacto}</p>}
      <p className={`mt-3 flex items-center gap-1.5 text-xs font-medium ${ativo ? t.cor : "text-t3"}`}>
        <span className={`h-2.5 w-2.5 rounded-full ${ativo ? t.dot : "bg-bdr"}`} aria-hidden />
        {ativo ? "Cenário selecionado" : "Selecionar este cenário"}
      </p>
    </button>
  );
}
