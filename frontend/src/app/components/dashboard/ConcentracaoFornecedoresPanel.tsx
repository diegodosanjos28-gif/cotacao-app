"use client";

import { concentracaoFornecedores } from "@/lib/api";
import { formatarMoeda, formatarPercentual } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";

// Painel "Concentração de Fornecedores" (Dashboard, linha 2 — 50%) — Top 5
// fornecedores por participação no valor comprado dentro da competência
// selecionada, com alerta de dependência quando o líder atinge o limite (backend
// decide o limite — ConcentracaoFornecedorService.LIMITE_DEPENDENCIA_PCT — nunca
// hardcodado aqui).
export default function ConcentracaoFornecedoresPanel({ mes }: { mes: string }) {
  const { data, carregando } = useAsync(
    () => concentracaoFornecedores(mes),
    [mes],
    "Não foi possível carregar a concentração de fornecedores.",
  );

  const fornecedores = data?.fornecedores ?? [];

  return (
    <div
      className="flex flex-col rounded-2xl border border-white/25 p-5 text-white sm:p-6"
      style={{
        background:
          "radial-gradient(130% 150% at 0% 0%, rgba(255,255,255,.16), transparent 52%), linear-gradient(158deg, #FF8A5B, #FE7641 55%, #EE5E2C)",
        boxShadow: "0 12px 36px rgba(238,94,44,.30), 0 2px 8px rgba(0,0,0,.10)",
      }}
    >
      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-[15px] font-medium text-white">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth={2.1} strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2a10 10 0 1 0 10 10H12z" />
            <path d="M12 2v10l7 5" />
          </svg>
          Concentração de Fornecedores
        </div>
        <span
          className={`whitespace-nowrap rounded-full px-2.5 py-1 text-[10.5px] font-medium ${
            data?.algumEmRisco ? "border border-brown/45 bg-brown text-[#FFCDB6]" : "border border-white/30 bg-white/15 text-white"
          }`}
        >
          {data ? (data.algumEmRisco ? "⚠ Dependência" : "Equilibrado") : "—"}
        </span>
      </div>

      {carregando && <p className="text-sm text-white/85">Carregando...</p>}
      {!carregando && fornecedores.length === 0 && (
        <p className="text-sm text-white/85">Nenhuma compra registrada nesta competência.</p>
      )}

      {data?.algumEmRisco && fornecedores[0] && (
        <div className="mb-4 flex items-start gap-2.5 rounded-[10px] border border-brown/30 bg-brown/25 px-3.5 py-2.5 text-[11.5px] leading-relaxed text-white/90">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="mt-0.5 shrink-0">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
            <path d="M12 9v4M12 17h.01" />
          </svg>
          <span>
            <b className="font-medium text-white">{fornecedores[0].nomeFornecedor}</b> concentra{" "}
            <b className="font-medium text-white">{formatarPercentual(fornecedores[0].sharePct)}</b> das compras do
            mês. Risco de dependência de prazo, preço e ruptura — vale diversificar ou renegociar.
          </span>
        </div>
      )}

      <div className="flex flex-1 flex-col justify-between gap-4">
        {fornecedores.map((f) => (
          <div key={f.fornecedorId} className="flex flex-col gap-1.5">
            <div className="flex items-end justify-between gap-2">
              <span className="flex min-w-0 items-center gap-2 truncate text-[13px] text-white">
                {f.nomeFornecedor}
                {f.acimaDoLimite && (
                  <span className="shrink-0 rounded-[5px] bg-brown px-1.5 py-0.5 text-[8.5px] font-medium uppercase tracking-wide text-[#FFCDB6]">
                    alto
                  </span>
                )}
              </span>
              <span className="flex shrink-0 items-baseline gap-2 whitespace-nowrap">
                <span className="text-[20px] font-light tracking-tight text-white">{formatarPercentual(f.sharePct)}</span>
                <span className="text-[10.5px] text-white/65">{formatarMoeda(f.valorComprado)}</span>
              </span>
            </div>
            <span className="relative h-[7px] overflow-hidden rounded-full bg-brown/25">
              <span
                className="absolute left-0 top-0 h-full rounded-full bg-white transition-[width] duration-300"
                style={{ width: `${Math.min(f.sharePct, 100)}%` }}
              />
              {data && (
                <span
                  className="absolute -top-[3px] -bottom-[3px] w-0.5 rounded-sm bg-brown opacity-70"
                  style={{ left: `${data.limiteDependenciaPct}%` }}
                />
              )}
            </span>
          </div>
        ))}
      </div>

      {data && (
        <div className="mt-3.5 flex items-center gap-1.5 border-t border-white/20 pt-3 text-[10px] text-white/75">
          <span className="inline-block h-0.5 w-3.5 rounded-sm bg-brown opacity-70" />
          Limite de dependência sugerido: {data.limiteDependenciaPct}%
        </div>
      )}
    </div>
  );
}
