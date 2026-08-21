"use client";

import { avatarCor, iniciais } from "@/lib/avatarCor";
import { formatarDuracaoMinutos, formatarTempoRelativo } from "@/lib/format";
import { DocIcon, ClockIcon } from "@/components/icons/HallIcons";
import { FornecedorHistoricoResponse, FornecedorRespostaResumo, SeloConfiabilidade } from "@/lib/types";

const SELO_LABEL: Record<SeloConfiabilidade, string> = {
  AGIL: "Resposta ágil",
  REGULAR: "Resposta regular",
  ATRASA: "Costuma atrasar",
};

// Classes de pill por selo — reaproveita os tokens de status já existentes
// (ok/wa/t3), sem introduzir um 4º tom só para este card.
const SELO_CLASSE: Record<SeloConfiabilidade, string> = {
  AGIL: "border-ok/28 bg-ok-d text-ok",
  REGULAR: "border-wa/30 bg-wa-d text-wa-txt",
  ATRASA: "border-bdr bg-surf text-t3",
};

type Props =
  | { modo: "ativa"; fornecedor: FornecedorRespostaResumo; itensListaBase: number }
  | { modo: "historico"; fornecedor: FornecedorHistoricoResponse };

export default function HallFornecedorCard(props: Props) {
  const isAtiva = props.modo === "ativa";
  const nome = props.fornecedor.nome;
  const cor = avatarCor(nome);

  if (isAtiva) {
    const { fornecedor, itensListaBase } = props;
    const respondeu = fornecedor.status !== "PENDENTE";
    const confirmado = fornecedor.status === "CONFIRMADO";
    const pct = itensListaBase > 0 && respondeu ? Math.min(100, (fornecedor.itensCotados / itensListaBase) * 100) : 0;

    return (
      <div
        className={`flex flex-col gap-4 rounded-2xl border p-5 transition-all hover:-translate-y-[3px] hover:shadow-[0_14px_30px_rgba(37,34,32,.12)] ${
          respondeu
            ? "border-bdr bg-card shadow-[0_6px_20px_rgba(37,34,32,.07),0_1px_3px_rgba(37,34,32,.05)]"
            : "border-dashed border-bdr shadow-none"
        }`}
        style={respondeu ? undefined : { background: "linear-gradient(158deg, #FAFAF8, #F1EEE8)" }}
      >
        <div className="flex items-center gap-3.5">
          <span
            className={`flex h-[54px] w-[54px] shrink-0 items-center justify-center rounded-full text-base font-medium text-white shadow-[0_2px_8px_rgba(0,0,0,.16)] ${respondeu ? "" : "opacity-55"}`}
            style={{ background: cor }}
          >
            {iniciais(nome)}
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-base font-medium text-t1">{nome}</div>
            <div className="mt-1 flex items-center gap-2 text-xs font-normal text-t3">
              <ClockIcon />
              {respondeu ? `Respondeu ${formatarTempoRelativo(fornecedor.respondeuEm)}` : "Ainda não respondeu"}
            </div>
          </div>
          <span
            className={`ml-auto inline-flex shrink-0 items-center gap-2 self-start whitespace-nowrap rounded-full px-3 py-1.5 text-xs font-medium ${
              confirmado
                ? "border border-ok/28 bg-ok-d text-ok"
                : respondeu
                  ? "border border-prx/28 bg-prx/[.12] text-prx-l"
                  : "border border-bdr bg-surf text-t3"
            }`}
          >
            <span
              className={`h-1.5 w-1.5 shrink-0 rounded-full ${confirmado ? "bg-ok" : respondeu ? "bg-prx" : "bg-bdr-m"}`}
              style={respondeu && !confirmado ? { animation: "entrada-blink 1.4s infinite" } : undefined}
            />
            {confirmado ? "Confirmado" : respondeu ? "Aguardando sua aprovação" : "Aguardando resposta"}
          </span>
        </div>
        <div className="flex flex-col justify-center gap-2">
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-[11px] font-medium uppercase tracking-wide text-t3">Cobertura da lista</span>
            <span className="font-[var(--font-sans)] text-sm font-normal text-t2">
              <b className="font-light text-2xl tracking-[-.6px] text-prx-l">{respondeu ? fornecedor.itensCotados : "—"}</b> / {itensListaBase} itens
            </span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-t1/[.08]">
            <span
              className="block h-full rounded-full"
              style={{ width: `${pct}%`, background: respondeu ? "linear-gradient(90deg, #FE7641, #FFB25E)" : "var(--color-bdr-m)" }}
            />
          </div>
        </div>
      </div>
    );
  }

  const { fornecedor } = props;
  const seloClasse = SELO_CLASSE[fornecedor.selo];

  return (
    <div className="flex flex-col gap-4 rounded-2xl border border-bdr bg-card p-5 shadow-[0_6px_20px_rgba(37,34,32,.07),0_1px_3px_rgba(37,34,32,.05)] transition-all hover:-translate-y-[3px] hover:shadow-[0_14px_30px_rgba(37,34,32,.12)]">
      <div className="flex items-center gap-3.5">
        <span className="flex h-[54px] w-[54px] shrink-0 items-center justify-center rounded-full text-base font-medium text-white shadow-[0_2px_8px_rgba(0,0,0,.16)]" style={{ background: cor }}>
          {iniciais(nome)}
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-base font-medium text-t1">{nome}</div>
          <div className="mt-1 flex items-center gap-2 text-xs font-normal text-t3">
            <DocIcon />
            Participou de {fornecedor.cotacoesParticipadas} cotaç{fornecedor.cotacoesParticipadas === 1 ? "ão" : "ões"}
          </div>
        </div>
        <span className={`ml-auto inline-flex shrink-0 items-center gap-2 self-start whitespace-nowrap rounded-full border px-3 py-1.5 text-xs font-medium ${seloClasse}`}>
          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
          {SELO_LABEL[fornecedor.selo]}
        </span>
      </div>
      <div className="flex flex-col justify-center gap-2">
        <div className="flex items-baseline justify-between gap-2">
          <span className="text-[11px] font-medium uppercase tracking-wide text-t3">Cobertura média</span>
          <span className="font-[var(--font-sans)] text-sm font-normal text-t2">
            <b className="font-light text-2xl tracking-[-.6px] text-prx-l">{Math.round(fornecedor.coberturaMediaPct)}</b>%
          </span>
        </div>
        <div className="h-2 overflow-hidden rounded-full bg-t1/[.08]">
          <span className="block h-full rounded-full" style={{ width: `${fornecedor.coberturaMediaPct}%`, background: "linear-gradient(90deg, #FE7641, #FFB25E)" }} />
        </div>
      </div>
      <div className="flex gap-2.5">
        <div className="flex min-w-0 flex-1 flex-col justify-center rounded-xl border border-bdr bg-surf px-3.5 py-2">
          <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-t3">
            <DocIcon />
            Cotações
          </div>
          <div className="mt-0.5 whitespace-nowrap text-sm font-medium text-t1">{fornecedor.cotacoesParticipadas}</div>
        </div>
        <div className="flex min-w-0 flex-1 flex-col justify-center rounded-xl border border-bdr bg-surf px-3.5 py-2">
          <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-t3">
            <ClockIcon />
            Resp. média
          </div>
          <div className="mt-0.5 whitespace-nowrap text-sm font-medium text-t1">{formatarDuracaoMinutos(fornecedor.tempoRespostaMedioMinutos)}</div>
        </div>
      </div>
    </div>
  );
}
