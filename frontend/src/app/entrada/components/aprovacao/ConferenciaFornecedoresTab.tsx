"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { adicionarFornecedorNaCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ConferenciaPatch, Cotacao, CotacaoFornecedorResponse, Fornecedor } from "@/lib/types";
import FornecedoresSidebar from "@/app/cotacoes/[id]/entrada/components/FornecedoresSidebar";
import ConferenciaConfirmadaReadOnly from "./ConferenciaConfirmadaReadOnly";
import ConferenciaPendente from "./ConferenciaPendente";
import FornecedorCapturaCard from "./FornecedorCapturaCard";
import { RascunhoFornecedor, rascunhoVazio } from "./rascunhoFornecedor";

const STATUS_DOT: Record<string, string> = {
  PENDENTE: "bg-t3",
  PROCESSADO: "bg-wa",
  CONFIRMADO: "bg-ok",
};

interface Props {
  cotacaoId: string;
  cotacao: Cotacao;
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  todosFornecedores: Fornecedor[];
  onFornecedorAtualizado: (fornecedor: Fornecedor) => void;
  onFornecedorInativado: (id: string) => void;
  onConferirResposta: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<boolean>;
  onCancelarConferencia: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<void>;
  onCotacaoFornecedoresAtualizados: () => Promise<void>;
  onProcessarResposta: (cotacaoFornecedor: CotacaoFornecedorResponse, texto: string) => Promise<void>;
  rascunhos: Record<string, RascunhoFornecedor>;
  onTextoChange: (cfId: string, texto: string) => void;
  onEstadoResolucaoChange: (cfId: string, patch: ConferenciaPatch) => void;
}

// Aba 2 do modal de aprovação — dona de todo o fluxo de fornecedores, do cadastro à
// confirmação (refactor 2026-08-20: absorveu o que antes era o painel "Fornecedores e
// cotações" atrás do modal, FornecedoresCotacoesSection, removido). "+ Adicionar
// Fornecedor" só existe para cotações canalOrigem WEB — cotações WHATSAPP recebem
// fornecedores automaticamente pelo webhook, sem ponto de adição manual.
export default function ConferenciaFornecedoresTab({
  cotacaoId,
  cotacao,
  cotacaoFornecedores,
  todosFornecedores,
  onFornecedorAtualizado,
  onFornecedorInativado,
  onConferirResposta,
  onCancelarConferencia,
  onCotacaoFornecedoresAtualizados,
  onProcessarResposta,
  rascunhos,
  onTextoChange,
  onEstadoResolucaoChange,
}: Props) {
  const podeAdicionarFornecedor = cotacao.canalOrigem === "WEB";
  const cotacaoFinalizada = cotacao.status === "FINALIZADA";

  const [activeId, setActiveId] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(false);
  const [painelAberto, setPainelAberto] = useState(podeAdicionarFornecedor && cotacaoFornecedores.length === 0);
  const [adicionando, setAdicionando] = useState(false);
  const [erroAdicionar, setErroAdicionar] = useState<string | null>(null);

  const sequencia = useMemo(() => {
    const pendentes = cotacaoFornecedores.filter((cf) => cf.status !== "CONFIRMADO");
    const confirmados = cotacaoFornecedores.filter((cf) => cf.status === "CONFIRMADO");
    return [...pendentes, ...confirmados];
  }, [cotacaoFornecedores]);

  const atual = cotacaoFornecedores.find((cf) => cf.id === activeId) ?? sequencia[0];
  const rascunhoAtual = atual ? (rascunhos[atual.id] ?? rascunhoVazio()) : rascunhoVazio();
  const { texto, preview } = rascunhoAtual;

  const ultimo = cotacaoFornecedores[cotacaoFornecedores.length - 1];
  const podeAdicionarProximo = cotacaoFornecedores.length === 0 || ultimo?.status === "CONFIRMADO";

  // Ver versão original (ConferenciaPanel): distingue troca real de fornecedor de o
  // próprio efeito de auto-fetch se re-disparando porque ele mesmo acabou de gravar
  // `preview`.
  const atualIdRef = useRef(atual?.id);
  useEffect(() => {
    atualIdRef.current = atual?.id;
  }, [atual?.id]);

  // Abre a Conferência automaticamente ao selecionar um fornecedor PROCESSADO sem
  // preview ainda em memória — cobre o caminho WhatsApp (texto já persistido, nunca
  // passou por preview) e o caso de reabrir o modal depois de fechar.
  useEffect(() => {
    if (!atual || atual.status !== "PROCESSADO" || preview) return;
    const cotacaoFornecedorIdCarregando = atual.id;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCarregando(true);
    onConferirResposta(atual).finally(() => {
      if (atualIdRef.current === cotacaoFornecedorIdCarregando) setCarregando(false);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual?.id, atual?.status, preview]);

  async function onFornecedorConfirmado(cotacaoFornecedorId: string) {
    await onCotacaoFornecedoresAtualizados();
    const restantes = sequencia.filter((cf) => cf.id !== cotacaoFornecedorId && cf.status !== "CONFIRMADO");
    if (restantes.length > 0) setActiveId(restantes[0].id);
  }

  async function onAdicionarFornecedor(fornecedor: Fornecedor) {
    setAdicionando(true);
    setErroAdicionar(null);
    try {
      const cf = await adicionarFornecedorNaCotacao(cotacaoId, { fornecedorId: fornecedor.id });
      await onCotacaoFornecedoresAtualizados();
      setPainelAberto(false);
      setActiveId(cf.id);
    } catch (err) {
      setErroAdicionar(getErrorMessage(err, "Não foi possível adicionar o fornecedor à cotação."));
    } finally {
      setAdicionando(false);
    }
  }

  const banner = (
    <div className="flex items-start gap-3 rounded-xl border border-inf/18 bg-inf/[.06] p-3.5">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] bg-inf/[.12] text-inf">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 9l1.5-5h15L21 9" />
          <path d="M4 9v11a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V9" />
          <path d="M9 21v-6h6v6" />
        </svg>
      </span>
      <div>
        <div className="text-[15px] font-extrabold text-t1">Conferência das Cotações dos Fornecedores</div>
        <p className="mt-0.5 text-xs leading-relaxed text-t2">
          Confira, fornecedor por fornecedor, os itens e preços que a AI capturou da resposta no WhatsApp.
        </p>
      </div>
    </div>
  );

  // Nenhum fornecedor ainda: WEB mostra a sidebar de cadastro direto; WHATSAPP só
  // espera a primeira resposta chegar sozinha pelo webhook (sem ponto de adição manual).
  if (cotacaoFornecedores.length === 0) {
    return (
      <div className="space-y-3">
        {banner}
        {podeAdicionarFornecedor ? (
          <div className="rounded-xl border border-bdr bg-card p-4">
            {erroAdicionar && <p className="mb-2 text-xs text-er">{erroAdicionar}</p>}
            <FornecedoresSidebar
              fornecedores={todosFornecedores}
              fornecedoresJaAdicionadosIds={[]}
              onFornecedorSalvo={onFornecedorAtualizado}
              onFornecedorInativado={onFornecedorInativado}
              onAdicionarCotacao={onAdicionarFornecedor}
              adicionando={adicionando}
              podeAdicionar={true}
              somenteLeitura={cotacaoFinalizada}
            />
          </div>
        ) : (
          <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">
            Nenhuma resposta de fornecedor via WhatsApp recebida ainda. Assim que um fornecedor responder pelo
            WhatsApp, a resposta aparecerá aqui automaticamente.
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {banner}

      <div className="flex flex-wrap items-center gap-2">
        {sequencia.map((cf) => (
          <button
            key={cf.id}
            type="button"
            onClick={() => {
              setPainelAberto(false);
              setActiveId(cf.id);
            }}
            disabled={carregando}
            className={`inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
              !painelAberto && cf.id === atual?.id ? "border-inf bg-inf/[.06] text-t1" : "border-bdr text-t2 hover:bg-hov"
            }`}
          >
            <span className={`h-2.5 w-2.5 rounded-full ${STATUS_DOT[cf.status] ?? "bg-t3"}`} />
            {cf.nomeFornecedor ?? "Fornecedor"}
            {cf.status === "CONFIRMADO" && <span className="font-bold text-ok">✓</span>}
          </button>
        ))}
        {podeAdicionarFornecedor && !cotacaoFinalizada && (
          <button
            type="button"
            onClick={() => setPainelAberto((v) => !v)}
            title={!podeAdicionarProximo ? "Confirme a Conferência do fornecedor atual para liberar o próximo" : undefined}
            className={`inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border border-dashed px-3 py-1.5 text-xs font-medium transition-colors ${
              painelAberto ? "border-prx bg-prx/[.06] text-prx" : "border-bdr text-t2 hover:bg-hov"
            }`}
          >
            {painelAberto ? "✕ Fechar" : "+ Adicionar Fornecedor"}
          </button>
        )}
      </div>

      {painelAberto ? (
        <div className="rounded-xl border border-bdr bg-card p-4">
          {erroAdicionar && <p className="mb-2 text-xs text-er">{erroAdicionar}</p>}
          <FornecedoresSidebar
            fornecedores={todosFornecedores}
            fornecedoresJaAdicionadosIds={cotacaoFornecedores.map((cf) => cf.fornecedorId)}
            onFornecedorSalvo={onFornecedorAtualizado}
            onFornecedorInativado={onFornecedorInativado}
            onAdicionarCotacao={onAdicionarFornecedor}
            adicionando={adicionando}
            podeAdicionar={podeAdicionarProximo}
            somenteLeitura={cotacaoFinalizada}
          />
        </div>
      ) : !atual ? null : atual.status === "CONFIRMADO" ? (
        <ConferenciaConfirmadaReadOnly
          key={atual.id}
          cotacaoId={cotacaoId}
          fornecedorId={atual.fornecedorId}
          fornecedorNome={atual.nomeFornecedor ?? ""}
        />
      ) : atual.status === "PENDENTE" ? (
        podeAdicionarFornecedor ? (
          <FornecedorCapturaCard
            key={atual.id}
            cotacaoFornecedor={atual}
            fornecedor={todosFornecedores.find((f) => f.id === atual.fornecedorId)}
            onFornecedorAtualizado={onFornecedorAtualizado}
            texto={texto}
            onTextoChange={(novoTexto) => onTextoChange(atual.id, novoTexto)}
            onProcessar={() => onProcessarResposta(atual, texto)}
          />
        ) : (
          <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">
            Aguardando a resposta deste fornecedor via WhatsApp.
          </div>
        )
      ) : carregando || !preview ? (
        <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">Carregando...</div>
      ) : (
        <ConferenciaPendente
          key={atual.id}
          onConfirmado={() => onFornecedorConfirmado(atual.id)}
          onCancelarConferencia={() => onCancelarConferencia(atual)}
          cotacaoId={cotacaoId}
          fornecedorId={atual.fornecedorId}
          fornecedorNome={atual.nomeFornecedor ?? ""}
          textoOriginal={texto}
          preview={preview}
          estadoResolucao={{ resolucoes: rascunhoAtual.resolucoes, spinOffs: rascunhoAtual.spinOffs, excluidos: rascunhoAtual.excluidos }}
          onEstadoResolucaoChange={(patch) => onEstadoResolucaoChange(atual.id, patch)}
        />
      )}
    </div>
  );
}
