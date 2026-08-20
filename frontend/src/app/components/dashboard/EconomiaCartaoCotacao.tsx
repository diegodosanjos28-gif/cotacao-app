"use client";

import { formatarMoeda, formatarPercentual } from "@/lib/format";
import { CotacaoFinalizadaCursorResponse } from "@/lib/types";

const MESES_ABREV = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];

function dataCard(iso: string): { data: string; hora: string } {
  const d = new Date(iso);
  return {
    data: `${String(d.getDate()).padStart(2, "0")} ${MESES_ABREV[d.getMonth()]}`,
    hora: d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" }),
  };
}

// Um card do carrossel "Cotações Registradas" — fundo fosco alternado ímpar/par
// (handoff §5), eleva no hover, abre o Modal de Conferência (somente leitura) ao
// clicar. Agregados (itens/valor/economia) já vêm prontos do backend
// (CotacaoFinalizadaCursorResponse) — este componente só formata.
export default function EconomiaCartaoCotacao({
  cotacao,
  index,
  onAbrirConferencia,
}: {
  cotacao: CotacaoFinalizadaCursorResponse;
  index: number;
  onAbrirConferencia: () => void;
}) {
  const { data, hora } = dataCard(cotacao.finalizadaEm);
  const impar = index % 2 === 0;

  return (
    <div
      className="flex min-h-[242px] flex-none flex-col rounded-[14px] border border-t1/[.05] shadow-[0_2px_10px_rgba(37,34,32,.06)] transition-[box-shadow,transform] duration-150 hover:-translate-y-[3px] hover:shadow-[0_12px_28px_rgba(37,34,32,.12)]"
      style={{
        width: "calc((100% - 42px)/4)",
        background: impar ? "linear-gradient(158deg, #FAF8F4, #F0ECE4)" : "linear-gradient(158deg, #FFFFFF, #F8F6F2)",
        scrollSnapAlign: "start",
      }}
    >
      <div className="border-b border-t1/[.07] px-3.5 py-3">
        <div className="text-[14.5px] font-medium tracking-tight text-t1">{data}</div>
        <div className="mt-px text-[10px] text-t3">
          {hora} · {cotacao.fornecedoresCount} fornecedor{cotacao.fornecedoresCount === 1 ? "" : "es"}
        </div>
      </div>
      <div className="flex flex-1 flex-col gap-2.5 px-3.5 py-3">
        <div className="flex flex-col gap-px">
          <span className="text-[9.5px] font-medium uppercase tracking-wide text-t3">Itens cotados</span>
          <span className="whitespace-nowrap text-[14.5px] font-medium text-t1">
            {cotacao.itensCotados}
            <span className="ml-[3px] text-[10.5px] font-medium text-t3">itens</span>
          </span>
        </div>
        <div className="flex flex-col gap-px">
          <span className="text-[9.5px] font-medium uppercase tracking-wide text-t3">Valor total comprado</span>
          <span className="whitespace-nowrap text-[14.5px] font-medium text-t1">{formatarMoeda(cotacao.valorTotalComprado)}</span>
        </div>
        <div className="mt-auto rounded-md border border-eco/25 px-[11px] py-2" style={{ background: "linear-gradient(180deg, rgba(16,185,129,.12), rgba(16,185,129,.06))" }}>
          <span className="text-[9px] font-medium uppercase tracking-wide text-eco">Economizado</span>
          <div className="flex items-baseline gap-1.5">
            <span className="whitespace-nowrap text-[15px] font-bold leading-tight tracking-tight text-eco">
              {formatarMoeda(cotacao.economizado)}
            </span>
            <span className="whitespace-nowrap text-[10px] font-medium text-eco">▼ {formatarPercentual(cotacao.economizadoPct)}</span>
          </div>
        </div>
      </div>
      <div className="px-3.5 pb-3.5">
        <button
          type="button"
          onClick={onAbrirConferencia}
          className="flex w-full items-center justify-center gap-1.5 rounded-md bg-prx px-3 py-2.5 text-[12.5px] font-medium text-white shadow-[0_2px_10px_rgba(255,128,0,.28)] transition-all hover:-translate-y-px hover:bg-prx-l hover:shadow-[0_4px_14px_rgba(255,128,0,.38)]"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 11l3 3L22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </svg>
          Conferência
        </button>
      </div>
    </div>
  );
}
