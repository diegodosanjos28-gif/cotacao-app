"use client";

import { labelMes, mesAtual, somarMeses } from "./competencia";

// Controle de mês/competência dos dois painéis de insight (Variação de Preço,
// Concentração de Fornecedores) — estado único em page.tsx, passado como prop pros
// dois painéis pra nunca dessincronizar qual mês cada um mostra.
export default function CompetenciaSelector({ mes, onChange }: { mes: string; onChange: (mes: string) => void }) {
  const podeAvancar = mes < mesAtual();

  return (
    <div className="mt-8 flex items-center justify-between">
      <h2 className="text-lg font-semibold tracking-tight text-t1">Insights do mês</h2>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => onChange(somarMeses(mes, -1))}
          aria-label="Mês anterior"
          className="flex h-8 w-8 items-center justify-center rounded-md border border-bdr text-t2 hover:border-prx hover:bg-prx/10 hover:text-prx"
        >
          ‹
        </button>
        <span className="min-w-[9rem] text-center text-sm font-medium text-t1">{labelMes(mes)}</span>
        <button
          type="button"
          onClick={() => podeAvancar && onChange(somarMeses(mes, 1))}
          disabled={!podeAvancar}
          aria-label="Próximo mês"
          className="flex h-8 w-8 items-center justify-center rounded-md border border-bdr text-t2 hover:border-prx hover:bg-prx/10 hover:text-prx disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-bdr disabled:hover:bg-transparent disabled:hover:text-t2"
        >
          ›
        </button>
      </div>
    </div>
  );
}
