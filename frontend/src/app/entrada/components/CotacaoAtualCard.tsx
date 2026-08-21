"use client";

import { avatarCor, iniciais } from "@/lib/avatarCor";
import { canalLabel } from "@/lib/canalLabel";
import { formatarTempoRelativo } from "@/lib/format";
import { CotacaoAtualResponse } from "@/lib/types";
import { InboxIcon } from "@/components/icons/HallIcons";

const MESES_ABREV = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];

function dataHoraCurta(iso: string): string {
  const d = new Date(iso);
  const dataStr = `${String(d.getDate()).padStart(2, "0")} ${MESES_ABREV[d.getMonth()]}`;
  const horaStr = d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
  return `${dataStr}, ${horaStr}`;
}

const MAX_AVATARES = 5;

export default function CotacaoAtualCard({
  cotacaoAtual,
  onRevisarEAprovar,
  onCriarCotacao,
  carregando = false,
}: {
  cotacaoAtual: CotacaoAtualResponse | null;
  onRevisarEAprovar: () => void;
  onCriarCotacao: () => void;
  // true enquanto a landing busca os dados completos da cotação (itens/produtos/
  // fornecedores) pra abrir o AprovacaoModal — evita disparar a busca 2x com um
  // duplo-clique em "Revisar e aprovar".
  carregando?: boolean;
}) {
  if (!cotacaoAtual) {
    return (
      <div
        className="flex min-h-[380px] flex-col items-center justify-center gap-4 rounded-[26px] border border-white/10 px-8 py-10 text-center text-white shadow-[0_12px_36px_rgba(0,0,0,.30)]"
        style={{
          background:
            "radial-gradient(130% 150% at 100% 0%, rgba(255,255,255,.10), transparent 55%), linear-gradient(158deg, #1C1A18, #121110 55%, #0A0A0A)",
        }}
      >
        <span className="flex h-[80px] w-[80px] items-center justify-center rounded-full border border-white/10 bg-white/[.06] text-white/55">
          <InboxIcon />
        </span>
        <p className="text-2xl font-light tracking-tight text-white">Sem cotações no momento</p>
        <p className="max-w-[360px] text-sm leading-relaxed text-white/50">
          Assim que uma nova cotação chegar pelo WhatsApp AI, ela aparece aqui para você revisar e aprovar.
        </p>
        <button
          type="button"
          onClick={onCriarCotacao}
          className="mt-2 rounded-md bg-prx px-5 py-3 text-base font-semibold text-white hover:bg-prx-l"
        >
          Criar cotação pela web
        </button>
      </div>
    );
  }

  const respondidos = cotacaoAtual.fornecedores.filter((f) => f.status !== "PENDENTE");
  const total = cotacaoAtual.fornecedores.length;
  const faltam = total - respondidos.length;
  const avataresVisiveis = respondidos.slice(0, MAX_AVATARES);
  const avataresRestantes = respondidos.length - avataresVisiveis.length;

  return (
    <div
      className="relative flex flex-col overflow-hidden rounded-[26px] border-[1.5px] px-8 py-8"
      style={{
        borderColor: "rgba(245,158,11,.30)",
        animation: "entrada-pulse 2.2s ease-in-out infinite",
      }}
    >
      <div className="mb-5 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-wa-txt">
          <span
            className="h-2.5 w-2.5 rounded-full bg-wa"
            style={{ animation: "entrada-ring 1.6s infinite" }}
          />
          Nova cotação · {canalLabel(cotacaoAtual.canalOrigem)}
        </div>
        <span className="whitespace-nowrap rounded-full border border-wa/30 bg-wa-d px-3 py-1.5 text-xs font-bold text-wa-txt">
          Aguardando aprovação
        </span>
      </div>

      {respondidos.length > 0 && (
        <div className="mb-4 flex items-center">
          {avataresVisiveis.map((f) => (
            <span
              key={f.fornecedorId}
              className="-ml-[11px] flex h-[44px] w-[44px] items-center justify-center rounded-full border-[3px] border-white text-sm font-bold text-white shadow-[0_2px_6px_rgba(0,0,0,.12)] first:ml-0"
              style={{ background: avatarCor(f.nome) }}
              title={f.nome}
            >
              {iniciais(f.nome)}
            </span>
          ))}
          {avataresRestantes > 0 && (
            <span className="-ml-[11px] flex h-[44px] w-[44px] items-center justify-center rounded-full border-[3px] border-white bg-surf text-sm font-bold text-t2 shadow-[0_2px_6px_rgba(0,0,0,.12)]">
              +{avataresRestantes}
            </span>
          )}
          <span className="ml-3.5 text-sm text-t2">responderam esta cotação</span>
        </div>
      )}

      <div className="mb-1 text-sm font-semibold text-t2">Fornecedores que responderam</div>
      <div className="font-[var(--font-sans)] text-[84px] font-black leading-none tracking-[-4px] text-t1">
        {respondidos.length}
        <span className="text-4xl font-bold tracking-tight text-t3"> / {total}</span>
      </div>

      <div className="mt-4 flex flex-wrap gap-2.5">
        <span className="inline-flex items-center gap-1 rounded-full border border-bdr bg-surf px-3 py-1.5 text-sm font-semibold text-t2">
          {cotacaoAtual.itensListaBase} itens na lista base
        </span>
        <span className="inline-flex items-center gap-1 rounded-full border border-ok/20 bg-ok-d px-3 py-1.5 text-sm font-semibold text-ok">
          {cotacaoAtual.itensCotados} itens cotados
        </span>
      </div>

      <p className="mt-4 text-sm text-t3">
        Recebida {formatarTempoRelativo(cotacaoAtual.ultimaAtividadeEm)} · {dataHoraCurta(cotacaoAtual.criadoEm)}
        {faltam > 0 && ` · falta ${faltam} fornecedor${faltam === 1 ? "" : "es"} responder`}
      </p>

      <div className="mt-6 flex gap-3">
        <button
          type="button"
          onClick={onRevisarEAprovar}
          disabled={carregando}
          className="flex flex-1 items-center justify-center gap-2 rounded-md bg-wa px-5 py-3.5 text-[15px] font-bold text-brown shadow-[0_3px_14px_rgba(245,158,11,.34)] transition-all hover:-translate-y-px hover:shadow-[0_5px_18px_rgba(245,158,11,.46)] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:translate-y-0"
        >
          {carregando ? (
            "Carregando..."
          ) : (
            <>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
                <path d="M9 11l3 3L22 4" />
                <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
              </svg>
              Revisar e aprovar
            </>
          )}
        </button>
      </div>
    </div>
  );
}
