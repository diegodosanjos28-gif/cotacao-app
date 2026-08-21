"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Modal from "@/components/Modal";
import { buscarRespostaPersistida, cancelarRespostaFornecedor, enviarResposta, finalizarCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ConferenciaPatch, Cotacao, CotacaoFornecedorResponse, ItemListaResponse, PreviewRespostaResponse, Produto } from "@/lib/types";
import ConferenciaListaBaseTab from "./aprovacao/ConferenciaListaBaseTab";
import ConferenciaFornecedoresTab from "./aprovacao/ConferenciaFornecedoresTab";
import { RascunhoFornecedor, rascunhoVazio } from "./aprovacao/rascunhoFornecedor";

export interface SeedRascunho {
  cfId: string;
  texto: string;
  preview: PreviewRespostaResponse;
}

interface Props {
  open: boolean;
  onClose: () => void;
  cotacaoId: string;
  cotacao: Cotacao;
  itensLista: ItemListaResponse[];
  produtos: Produto[];
  onListaAtualizada: (itens: ItemListaResponse[]) => void;
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  onCotacaoFornecedoresAtualizados: () => Promise<void>;
  onCotacaoAtualizada: (cotacao: Cotacao) => void;
  abaInicial: 1 | 2;
  fornecedorFocoId?: string | null;
  seedRascunho?: SeedRascunho | null;
  onTextoLimpo: (cfId: string) => void;
}

export default function AprovacaoModal({
  open,
  onClose,
  cotacaoId,
  cotacao,
  itensLista,
  produtos,
  onListaAtualizada,
  cotacaoFornecedores,
  onCotacaoFornecedoresAtualizados,
  onCotacaoAtualizada,
  abaInicial,
  fornecedorFocoId,
  seedRascunho,
  onTextoLimpo,
}: Props) {
  const router = useRouter();
  const [aprStep, setAprStep] = useState<1 | 2>(1);
  // Marcador 1 do stepper fica "done" (check verde) permanentemente depois de
  // confirmado, independente de o usuário voltar pra aba 1 depois — mesmo idioma do
  // mockup (step1Done, variável separada de aprStep).
  const [listaBaseConferida, setListaBaseConferida] = useState(false);
  const [rascunhos, setRascunhos] = useState<Record<string, RascunhoFornecedor>>({});
  const [lancando, setLancando] = useState(false);
  const [sucesso, setSucesso] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  // Reseta pra aba pedida sempre que o modal abre — mesmo idioma do mockup
  // (openApproval() sempre zera aprStep=1); a Fase D usa abaInicial=2 pro caminho
  // "processar já entrega pra conferência".
  useEffect(() => {
    if (!open) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setAprStep(abaInicial);
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setListaBaseConferida(false);
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSucesso(false);
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setErro(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, abaInicial]);

  // Seed one-shot: preview já resolvido no Passo 2 (onProcessar) — evita refazer a
  // chamada de rede que onConferirResposta faria (branch 1, "preview já em memória").
  useEffect(() => {
    if (!open || !seedRascunho) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRascunhos((prev) => ({
      ...prev,
      [seedRascunho.cfId]: { texto: seedRascunho.texto, preview: seedRascunho.preview, resolucoes: {}, spinOffs: {}, excluidos: {} },
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, seedRascunho]);

  function atualizarRascunho(id: string, patch: Partial<RascunhoFornecedor>) {
    setRascunhos((prev) => ({ ...prev, [id]: { ...(prev[id] ?? rascunhoVazio()), ...patch } }));
  }

  // Porte verbatim de onConferirResposta (entrada/page.tsx) — unifica os 3 caminhos
  // possíveis de origem do texto a conferir (preview já em memória / texto colado
  // ainda não processado / texto já persistido, caminho WhatsApp). Nunca lança:
  // ConferenciaPendente.confirmar() chama onConfirmado() sem await dentro do próprio
  // try, então uma rejeição vinda daqui escaparia como unhandled rejection.
  async function onConferirResposta(cf: CotacaoFornecedorResponse): Promise<boolean> {
    setErro(null);
    const rascunho = rascunhos[cf.id];

    if (rascunho?.preview) {
      return true;
    }

    try {
      let textoParaConferir = rascunho?.texto?.trim() ? rascunho.texto : "";
      if (!textoParaConferir) {
        const { texto: persistido } = await buscarRespostaPersistida(cotacaoId, cf.fornecedorId);
        textoParaConferir = persistido;
      }
      if (!textoParaConferir.trim()) {
        setErro(
          "Não há resposta registrada para conferir deste fornecedor. Cole a resposta no Passo 2 e clique em Processar Resposta Cotação.",
        );
        return false;
      }
      const resultado = await enviarResposta(cotacaoId, cf.fornecedorId, textoParaConferir);
      atualizarRascunho(cf.id, { texto: textoParaConferir, preview: resultado });
      await onCotacaoFornecedoresAtualizados();
      return true;
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar a resposta para conferência."));
      return false;
    }
  }

  // Porte verbatim de onCancelarConferencia (entrada/page.tsx) — apaga a resposta do
  // fornecedor de verdade no backend + limpa o rascunho local. Também limpa o texto
  // digitado do Passo 2 (onTextoLimpo), que agora vive fora deste componente.
  async function onCancelarConferencia(cf: CotacaoFornecedorResponse) {
    await cancelarRespostaFornecedor(cotacaoId, cf.fornecedorId);
    atualizarRascunho(cf.id, { texto: "", preview: null, resolucoes: {}, spinOffs: {}, excluidos: {} });
    onTextoLimpo(cf.id);
    await onCotacaoFornecedoresAtualizados();
  }

  function onEstadoResolucaoChange(cfId: string, patch: ConferenciaPatch) {
    atualizarRascunho(cfId, patch);
  }

  async function onLancar() {
    setLancando(true);
    setErro(null);
    try {
      const atualizada = await finalizarCotacao(cotacaoId, "MENOR_PRECO");
      onCotacaoAtualizada(atualizada);
      setSucesso(true);
      setTimeout(() => {
        onClose();
        router.push(`/cotacoes/${cotacaoId}/comparativo`);
      }, 2600);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível finalizar a cotação."));
    } finally {
      setLancando(false);
    }
  }

  const confirmados = cotacaoFornecedores.filter((cf) => cf.status === "CONFIRMADO").length;
  const total = cotacaoFornecedores.length;
  const podeLancar = total > 0 && confirmados === total;

  if (sucesso) {
    return (
      // Mesma caixa (~880px) do estado normal — o mockup só troca o conteúdo de
      // apr-body/esconde header e rodapé, nunca encolhe o modal em si (evita o "salto"
      // de layout de trocar pra uma caixa bem menor no instante do lançamento).
      <Modal open={open} onClose={onClose} className="flex w-full max-w-[880px] flex-col rounded-[18px] border border-bdr bg-card p-0 shadow-[0_30px_70px_rgba(0,0,0,.4)]">
        <div className="flex flex-col items-center gap-3.5 px-8 py-11 text-center">
          <span className="flex h-[74px] w-[74px] items-center justify-center rounded-full bg-ok-d text-ok">
            <svg width="38" height="38" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
              <path d="M20 6L9 17l-5-5" />
            </svg>
          </span>
          <h3 className="text-xl font-extrabold tracking-tight text-t1">Cotação aprovada e lançada!</h3>
          <p className="max-w-[380px] text-[13px] leading-relaxed text-t2">
            A lista base e as cotações dos {total} fornecedor{total === 1 ? "" : "es"} foram enviadas para o{" "}
            <b>Comparativo</b> e o <b>Mapa de Compra</b>.
          </p>
        </div>
      </Modal>
    );
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      className="flex max-h-[92vh] w-full max-w-[880px] flex-col rounded-[18px] border border-bdr bg-card shadow-[0_30px_70px_rgba(0,0,0,.4)]"
    >
      <div className="shrink-0 px-6 pt-5">
        <div className="mb-4 flex items-start justify-between gap-3.5">
          <div>
            <div className="text-lg font-extrabold tracking-tight text-t1">Aprovar cotação atual</div>
            <div className="mt-0.5 text-[12.5px] text-t2">{cotacao.titulo}</div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar"
            className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-[9px] border border-bdr text-t2 hover:border-prx hover:text-prx"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="mb-4 flex flex-wrap gap-2">
          <span className="inline-flex items-center gap-1 rounded-full border border-bdr bg-surf px-2.5 py-1 text-[11.5px] font-semibold text-t2">
            {confirmados} / {total} fornecedores
          </span>
          <span className="inline-flex items-center gap-1 rounded-full border border-bdr bg-surf px-2.5 py-1 text-[11.5px] font-semibold text-t2">
            {itensLista.length} itens na lista base
          </span>
        </div>

        <div className="flex border-b border-bdr">
          <button
            type="button"
            onClick={() => setAprStep(1)}
            aria-label="Conferência da Lista Base"
            className={`flex flex-1 items-center gap-2.5 border-b-[3px] px-1.5 pb-3 pt-1 text-left ${aprStep === 1 ? "border-prx" : listaBaseConferida ? "border-ok" : "border-transparent"}`}
          >
            <span
              className={`flex h-[27px] w-[27px] shrink-0 items-center justify-center rounded-full border-2 text-xs font-extrabold ${
                listaBaseConferida ? "border-ok bg-ok text-white" : aprStep === 1 ? "border-prx bg-prx/10 text-prx" : "border-bdr text-t3"
              }`}
            >
              {listaBaseConferida ? "✓" : 1}
            </span>
            <span className={`text-[13px] font-bold leading-tight ${aprStep === 1 ? "text-t1" : "text-t3"}`}>
              Conferência da Lista Base
              <small className="block text-[10.5px] font-medium text-t3">o que foi pedido</small>
            </span>
          </button>
          <button
            type="button"
            onClick={() => setAprStep(2)}
            aria-label="Conferência das Cotações"
            className={`flex flex-1 items-center gap-2.5 border-b-[3px] px-1.5 pb-3 pt-1 text-left ${aprStep === 2 ? "border-inf" : "border-transparent"}`}
          >
            <span className={`flex h-[27px] w-[27px] shrink-0 items-center justify-center rounded-full border-2 text-xs font-extrabold ${aprStep === 2 ? "border-inf bg-inf/10 text-inf" : "border-bdr text-t3"}`}>
              2
            </span>
            <span className={`text-[13px] font-bold leading-tight ${aprStep === 2 ? "text-t1" : "text-t3"}`}>
              Conferência das Cotações
              <small className="block text-[10.5px] font-medium text-t3">o que os fornecedores responderam</small>
            </span>
          </button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
        {erro && <p className="mb-3 text-sm text-er">{erro}</p>}
        {aprStep === 1 ? (
          <ConferenciaListaBaseTab
            cotacaoId={cotacaoId}
            itens={itensLista}
            produtos={produtos}
            onListaAtualizada={onListaAtualizada}
            cotacaoFinalizada={cotacao.status === "FINALIZADA"}
          />
        ) : (
          <ConferenciaFornecedoresTab
            cotacaoId={cotacaoId}
            cotacaoFornecedores={cotacaoFornecedores}
            onConferirResposta={onConferirResposta}
            onCancelarConferencia={onCancelarConferencia}
            onCotacaoFornecedoresAtualizados={onCotacaoFornecedoresAtualizados}
            fornecedorFocoId={fornecedorFocoId}
            rascunhos={rascunhos}
            onEstadoResolucaoChange={onEstadoResolucaoChange}
          />
        )}
      </div>

      <div className="flex shrink-0 items-center justify-between gap-3 border-t border-bdr px-6 py-4">
        {aprStep === 1 ? (
          <>
            <span className="text-[12.5px] text-t2">Etapa 1 de 2 · confira a lista base</span>
            <button
              type="button"
              onClick={() => {
                setListaBaseConferida(true);
                setAprStep(2);
              }}
              className="inline-flex items-center gap-2 rounded-md bg-prx px-4.5 py-2.5 text-[13px] font-bold text-white shadow-[0_3px_12px_rgba(255,128,0,.3)] hover:bg-prx-l"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
                <path d="M20 6L9 17l-5-5" />
              </svg>
              Lista base conferida
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              onClick={() => setAprStep(1)}
              className="rounded-md border-[1.5px] border-bdr px-4 py-2.5 text-[12.5px] font-semibold text-t2 hover:border-bdr-m hover:text-t1"
            >
              ← Voltar à lista base
            </button>
            <div className="flex items-center gap-3.5">
              <span className="text-[12.5px] text-t2">
                {confirmados}/{total} fornecedores conferidos
              </span>
              <button
                type="button"
                onClick={onLancar}
                disabled={!podeLancar || lancando}
                className="inline-flex items-center gap-2 rounded-md bg-gradient-to-b from-ok to-eco px-4.5 py-3 text-[13px] font-extrabold text-white shadow-[0_4px_16px_rgba(16,185,129,.32)] hover:enabled:-translate-y-px disabled:opacity-40 disabled:grayscale"
              >
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
                  <path d="M5 12l14-7-7 14-2-5-5-2z" />
                </svg>
                {lancando ? "Lançando..." : "Lançar para Comparativo e Mapa de Compra"}
              </button>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}
