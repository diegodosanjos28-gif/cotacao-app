"use client";

import { Dispatch, SetStateAction, useState } from "react";
import { useRouter } from "next/navigation";
import Modal from "@/components/Modal";
import StatusBadge from "@/components/StatusBadge";
import NovaCotacaoForm from "@/components/NovaCotacaoForm";
import { setCotacaoAtivaId } from "@/lib/cotacaoAtiva";
import { finalizarCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { Cotacao } from "@/lib/types";

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
  onCotacaoAtualizada: (cotacao: Cotacao) => void;
  onProcessar: () => void;
  processando: boolean;
  podeProcessar: boolean;
  setErro: Dispatch<SetStateAction<string | null>>;
}

// Botões e barra de status do fim da tela de Entrada de Dados, espelhando o rodapé
// do protótipo COTA&TESTA V5 (rBtnCotacao/banner-cotacao-status). "Limpar Cotação"
// fica desabilitado: removeria fornecedores já vinculados à cotação, e não existe
// endpoint pra isso hoje (ver diagnóstico do prompt) — não é algo que dê pra compor
// com segurança a partir dos endpoints atuais sem risco de estado inconsistente.
// "Processar Cotação" é o único botão de processamento da tela (igual ao protótipo):
// envia o texto colado no painel do fornecedor ativo para o parser/matching.
export default function EntradaFooter({
  cotacao,
  numFornecedores,
  onCotacaoAtualizada,
  onProcessar,
  processando,
  podeProcessar,
  setErro,
}: Props) {
  const router = useRouter();
  const [finalizando, setFinalizando] = useState(false);
  const [criandoNova, setCriandoNova] = useState(false);

  const finalizada = cotacao.status === "FINALIZADA";

  async function onFinalizar() {
    if (
      !window.confirm(
        "Finalizar esta cotação? Ela será registrada no histórico de economia e não poderá receber novos itens.",
      )
    ) {
      return;
    }
    setFinalizando(true);
    setErro(null);
    try {
      // Atalho de finalização direto da Entrada, sem passar pela seleção de cenário
      // do Mapa de Compra — grava Menor Preço, mesmo cenário que já era exibido por
      // padrão aqui antes de cenarioSelecionado passar a ser persistido de verdade.
      const atualizada = await finalizarCotacao(cotacao.id, "MENOR_PRECO");
      onCotacaoAtualizada(atualizada);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível finalizar a cotação."));
    } finally {
      setFinalizando(false);
    }
  }

  function onNovaCotacaoCriada(novaId: string) {
    setCotacaoAtivaId(novaId);
    setCriandoNova(false);
    router.push(`/cotacoes/${novaId}/entrada`);
  }

  const iniciadaEm = new Date(cotacao.criadoEm).toLocaleDateString("pt-BR");
  const dataReferencia = cotacao.ultimaAtividadeEm ?? cotacao.atualizadoEm;
  const atualizadaAs = dataReferencia
    ? new Date(dataReferencia).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })
    : "—";
  const finalizadaEm = cotacao.finalizadaEm
    ? new Date(cotacao.finalizadaEm).toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" })
    : "—";

  return (
    <div className="space-y-3 border-t border-bdr pt-6">
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
            <button
              type="button"
              disabled
              title="Ainda não há endpoint para remover fornecedores de uma cotação — pendência de backend."
              className="cursor-not-allowed rounded-md border border-er/40 px-4 py-2 text-sm font-medium text-er opacity-50"
            >
              Limpar Cotação
            </button>
            <button
              type="button"
              onClick={onProcessar}
              disabled={processando || !podeProcessar}
              className="rounded-md bg-prx px-5 py-2.5 text-sm font-semibold text-white hover:bg-prx-l disabled:opacity-50"
            >
              {processando ? "Processando..." : "Processar Cotação"}
            </button>
            <button
              type="button"
              onClick={onFinalizar}
              disabled={finalizando}
              className="rounded-md border border-prx px-4 py-2 text-sm font-medium text-prx hover:bg-prx/10 disabled:opacity-50"
            >
              {finalizando ? "Finalizando..." : "✓ Finalizar Cotação"}
            </button>
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

      {/* Sticky ao rodapé da viewport, igual ao .entrada-footer do protótipo — só este
          bloco é sticky, os botões e a barra de status acima seguem no fluxo normal. */}
      <div className="sticky bottom-0 z-10 mt-6 flex justify-end rounded-t-lg border-t-2 border-prx bg-bg px-5 py-4 shadow-[0_-4px_20px_rgba(37,34,32,.08)]">
        <button
          type="button"
          onClick={() => setCriandoNova(true)}
          className="inline-flex items-center gap-1.5 rounded-md bg-inf px-4 py-2 text-sm font-medium text-white hover:bg-blue-600"
        >
          + Nova Cotação
        </button>
      </div>

      <Modal open={criandoNova} onClose={() => setCriandoNova(false)} title="Nova cotação">
        <NovaCotacaoForm onCriada={onNovaCotacaoCriada} autoFocus />
      </Modal>
    </div>
  );
}
