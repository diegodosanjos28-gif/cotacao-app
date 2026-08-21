"use client";

import StatusBadge from "@/components/StatusBadge";
import { Cotacao } from "@/lib/types";
import { PassoEntrada } from "./EntradaStepper";

function ClockIcon() {
  return (
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <polyline points="12 6 12 12 16 14" />
    </svg>
  );
}

interface Props {
  cotacao: Cotacao;
  numFornecedores: number;
  // Rodapé tem comportamento dinâmico por passo da timeline (Prompt 25): no passo 1
  // (Lista de produtos) é só o botão de avançar pro próximo passo; no passo 2
  // (Fornecedores) são os botões de processamento + o gatilho do modal de aprovação.
  passoAtivo: PassoEntrada;
  podeAvancarPasso1: boolean;
  onAvancarPasso: () => void;
  onProcessar: () => void;
  processando: boolean;
  podeProcessar: boolean;
  // Abre o AprovacaoModal (Fase C) — finalizar a cotação vive inteiramente dentro
  // dele agora ("Lançar para Comparativo e Mapa de Compra"), este botão só navega até
  // lá. Visível sempre que já existe pelo menos 1 fornecedor na cotação.
  onAbrirAprovacao: () => void;
}

// Botões e barra de status do fim da tela de Entrada de Dados, espelhando o rodapé
// do protótipo COTA&TESTA V5 (rBtnCotacao/banner-cotacao-status). "Limpar Cotação"
// foi removido (achado do usuário, 2026-08-16): ficava sempre desabilitado, sem
// endpoint pra isso e sem uso real. "Processar Resposta Cotação" (renomeado de
// "Processar Cotação") envia o texto colado no painel do fornecedor ativo para o
// parser/matching. "+ Nova Cotação" foi removido (refactor da Entrada de Dados,
// 2026-08-20) — criar cotação passou a ser só pelo CTA do card vazio da landing
// (/entrada), único ponto de criação do app.
export default function EntradaFooter({
  cotacao,
  numFornecedores,
  passoAtivo,
  podeAvancarPasso1,
  onAvancarPasso,
  onProcessar,
  processando,
  podeProcessar,
  onAbrirAprovacao,
}: Props) {
  const finalizada = cotacao.status === "FINALIZADA";

  const iniciadaEm = new Date(cotacao.criadoEm).toLocaleDateString("pt-BR");
  const dataReferencia = cotacao.ultimaAtividadeEm ?? cotacao.atualizadoEm;
  const atualizadaAs = dataReferencia
    ? new Date(dataReferencia).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })
    : "—";
  const finalizadaEm = cotacao.finalizadaEm
    ? new Date(cotacao.finalizadaEm).toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" })
    : "—";

  return (
    <div className="space-y-3 border-t border-bdr pt-4">
      {finalizada ? (
        <div className="flex flex-wrap items-center gap-3 rounded-lg border border-ok/30 bg-ok-d px-4 py-3 text-sm text-t2">
          <span className="text-ok">
            <ClockIcon />
          </span>
          <span>
            <strong className="text-t1">Cotação finalizada</strong> · Registrada no histórico em {finalizadaEm} ·{" "}
            {numFornecedores} fornecedor{numFornecedores !== 1 ? "es" : ""}
          </span>
          <span className="ml-auto">
            <StatusBadge status="FINALIZADA" />
          </span>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-center gap-2.5">
            {passoAtivo === 1 ? (
              podeAvancarPasso1 ? (
                <button
                  type="button"
                  onClick={onAvancarPasso}
                  className="rounded-md bg-prx px-5 py-2.5 text-sm font-semibold text-white hover:bg-prx-l"
                >
                  Avançar para o próximo passo →
                </button>
              ) : (
                <p className="text-xs text-t3">Adicione ao menos um produto à lista para avançar.</p>
              )
            ) : (
              <>
                <button
                  type="button"
                  onClick={onProcessar}
                  disabled={processando || !podeProcessar}
                  className="rounded-md bg-prx px-5 py-2.5 text-sm font-semibold text-white hover:bg-prx-l disabled:opacity-50"
                >
                  {processando ? "Processando..." : "Processar Resposta Cotação"}
                </button>
                <button
                  type="button"
                  onClick={onAbrirAprovacao}
                  disabled={numFornecedores === 0}
                  className="rounded-md border border-prx px-4 py-2 text-sm font-medium text-prx hover:bg-prx/10 disabled:opacity-50"
                >
                  Revisar e aprovar
                </button>
              </>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-3 rounded-lg border border-prx/30 bg-prx/5 px-4 py-3 text-sm text-t2">
            <span className="text-prx">
              <ClockIcon />
            </span>
            <span>
              <strong className="text-t1">Cotação em andamento</strong> · {numFornecedores} fornecedor
              {numFornecedores !== 1 ? "es" : ""} · Iniciada em {iniciadaEm} · Atualizada às {atualizadaAs}
            </span>
            <span className="ml-auto">
              <StatusBadge status="EM_ANDAMENTO" />
            </span>
          </div>
        </>
      )}
    </div>
  );
}
