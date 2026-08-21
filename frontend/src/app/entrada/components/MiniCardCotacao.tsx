"use client";

import { useRouter } from "next/navigation";
import { CotacaoAnteriorCursorResponse } from "@/lib/types";

const MESES_ABREV = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];

function dataCard(iso: string): { data: string; hora: string } {
  const d = new Date(iso);
  return {
    data: `${String(d.getDate()).padStart(2, "0")} ${MESES_ABREV[d.getMonth()]}`,
    hora: d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" }),
  };
}

function ReabrirIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 4v6h6" />
      <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}

// Alterna fundo coral/creme por índice ímpar/par (fidelidade ao mockup) — "Reabrir"
// navega pro Comparativo, que já lê o mapaFinalSnapshot congelado (sem recalcular)
// quando a cotação está FINALIZADA. "Fechar" é soft-hide client-side (decisão
// deliberada, ver useCotacoesOcultas) — não finaliza nem apaga nada no backend.
export default function MiniCardCotacao({
  cotacao,
  index,
  onFechar,
}: {
  cotacao: CotacaoAnteriorCursorResponse;
  index: number;
  onFechar: (id: string) => void;
}) {
  const router = useRouter();
  const { data, hora } = dataCard(cotacao.finalizadaEm);
  const impar = index % 2 === 0;

  return (
    <div
      className="flex w-40 flex-none flex-col rounded-[14px] border p-3.5 shadow-[0_6px_15px_rgba(37,34,32,.12),0_1px_4px_rgba(37,34,32,.08)] transition-all hover:-translate-y-[3px] hover:shadow-[0_12px_24px_rgba(37,34,32,.16),0_3px_8px_rgba(37,34,32,.10)]"
      style={{
        scrollSnapAlign: "start",
        background: impar
          ? "radial-gradient(130% 150% at 100% 0%, rgba(255,255,255,.16), transparent 55%), linear-gradient(158deg, #FF8A5B, #FE7641 60%, #F0602F)"
          : "linear-gradient(158deg, #FBF9F6, #F1EEE8)",
        borderColor: impar ? "rgba(255,255,255,.22)" : "var(--color-bdr)",
        color: impar ? "#fff" : "var(--color-t1)",
      }}
    >
      <div className="text-[13.5px] font-extrabold tracking-tight">{data}</div>
      <div className={`mb-2 text-[9.5px] ${impar ? "text-white/72" : "text-t3"}`}>{hora} · processada</div>
      <div className="mb-2.5 flex flex-col gap-1">
        <div className="flex items-center justify-between gap-2">
          <span className={`text-[10px] ${impar ? "text-white/78" : "text-t2"}`}>Lista base</span>
          <span className="text-[11.5px] font-bold">{cotacao.itensListaBase} itens</span>
        </div>
        <div className="flex items-center justify-between gap-2">
          <span className={`text-[10px] ${impar ? "text-white/78" : "text-t2"}`}>Itens cotados</span>
          <span className="text-[11.5px] font-bold">{cotacao.itensCotados}</span>
        </div>
      </div>
      <div className="mt-0.5 flex overflow-hidden rounded-lg shadow-[0_2px_8px_rgba(0,0,0,.16)]">
        <button
          type="button"
          onClick={() => router.push(`/cotacoes/${cotacao.id}/comparativo`)}
          className="flex flex-1 items-center justify-center gap-1 bg-[#2E2B28] py-2 text-[10px] font-bold text-white hover:brightness-[1.12]"
        >
          <span className="text-[#5CB8FF]">
            <ReabrirIcon />
          </span>
          Reabrir
        </button>
        <button
          type="button"
          onClick={() => onFechar(cotacao.id)}
          className="flex flex-1 items-center justify-center gap-1 border-l border-white/15 bg-[#2E2B28] py-2 text-[10px] font-bold text-white hover:brightness-[1.12]"
        >
          <span className="text-[#4ADE80]">
            <CheckIcon />
          </span>
          Fechar
        </button>
      </div>
    </div>
  );
}
