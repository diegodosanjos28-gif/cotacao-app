import { ReactNode } from "react";

const TOM = {
  default: "text-t1",
  prx: "text-prx",
  ok: "text-ok",
  wa: "text-wa",
  er: "text-er",
} as const;

// Card de métrica único do design system — fiel ao `.kpi` do protótipo (label
// uppercase pequeno → valor grande em negrito → hint com tag colorida opcional).
// Usado pelo Dashboard, Mapa de Compra e outros pontos que precisam de um card de
// métrica; `destaque` reproduz a variante de fundo sólido (ex: KPI principal do
// Dashboard).
export default function StatCard({
  label,
  value,
  tom = "default",
  hint,
  tag,
  destaque,
}: {
  label: string;
  value: ReactNode;
  tom?: keyof typeof TOM;
  hint?: ReactNode;
  tag?: string;
  destaque?: boolean;
}) {
  if (destaque) {
    return (
      <div className="rounded-xl bg-ok p-5 shadow-[0_2px_10px_rgba(16,185,129,.25)] transition-shadow hover:shadow-[0_8px_20px_rgba(16,185,129,.4)]">
        <p className="mb-2 text-[10.5px] font-semibold uppercase tracking-wide text-white/75">{label}</p>
        <p className="mb-1 text-2xl font-bold leading-none tracking-tight text-white sm:text-[26px]">{value}</p>
        <p className="text-[11.5px] text-white/60">
          {tag && <span className="mr-1 rounded bg-white/20 px-1.5 py-0.5 text-[10px] font-semibold text-white">{tag}</span>}
          {hint}
        </p>
      </div>
    );
  }
  return (
    <div className="rounded-xl bg-card p-5 shadow-[0_1px_3px_rgba(37,34,32,.08),0_1px_2px_rgba(37,34,32,.04)] transition-all hover:-translate-y-0.5 hover:shadow-[0_10px_15px_rgba(37,34,32,.06),0_4px_6px_rgba(37,34,32,.04)]">
      <p className="mb-2 text-[10.5px] font-semibold uppercase tracking-wide text-t3">{label}</p>
      <p className={`mb-1 text-2xl font-bold leading-none tracking-tight sm:text-[26px] ${TOM[tom]}`}>{value}</p>
      {(tag || hint) && (
        <p className="text-[11.5px] text-t2">
          {tag && <span className="mr-1 rounded bg-hov px-1.5 py-0.5 text-[10px] font-semibold text-t2">{tag}</span>}
          {hint}
        </p>
      )}
    </div>
  );
}
