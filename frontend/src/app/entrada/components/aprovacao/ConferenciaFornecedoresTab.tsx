"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ConferenciaPatch, CotacaoFornecedorResponse } from "@/lib/types";
import ConferenciaConfirmadaReadOnly from "./ConferenciaConfirmadaReadOnly";
import ConferenciaPendente from "./ConferenciaPendente";
import { RascunhoFornecedor, rascunhoVazio } from "./rascunhoFornecedor";

const STATUS_DOT: Record<string, string> = {
  PENDENTE: "bg-t3",
  PROCESSADO: "bg-wa",
  CONFIRMADO: "bg-ok",
};

interface Props {
  cotacaoId: string;
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  onConferirResposta: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<boolean>;
  onCancelarConferencia: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<void>;
  onCotacaoFornecedoresAtualizados: () => Promise<void>;
  // Sugestão de seleção inicial ao a aba virar visível (ex.: fornecedor recém-
  // processado no Passo 2) — só aplicada uma vez, mesmo idioma de ConferenciaPanel.
  fornecedorFocoId?: string | null;
  // Mapa completo (dono é o AprovacaoModal) — este componente resolve sozinho o
  // rascunho do fornecedor que está selecionado agora (`atual`), evitando um
  // round-trip via onAtivoAlterado que o ConferenciaPanel original precisava (lá,
  // texto/preview eram donos da página, compartilhados com o Passo 2).
  rascunhos: Record<string, RascunhoFornecedor>;
  onEstadoResolucaoChange: (cfId: string, patch: ConferenciaPatch) => void;
}

// Aba 2 do modal de aprovação — porte de ConferenciaPanel.tsx (Prompt 26), adaptado
// para operar sobre o rascunho por fornecedor do AprovacaoModal em vez de props vindas
// de entrada/page.tsx. Fornecedor PROCESSADO abre a Conferência editável
// (ConferenciaPendente); CONFIRMADO abre a visão somente-leitura
// (ConferenciaConfirmadaReadOnly), sem nunca reprocessar a resposta. Diferente do
// ConferenciaPanel original: ao confirmar o último pendente, NÃO navega pro
// Comparativo — fica na aba mostrando o fornecedor recém-confirmado; a navegação só
// acontece depois de "Lançar", no rodapé do modal.
export default function ConferenciaFornecedoresTab({
  cotacaoId,
  cotacaoFornecedores,
  onConferirResposta,
  onCancelarConferencia,
  onCotacaoFornecedoresAtualizados,
  fornecedorFocoId,
  rascunhos,
  onEstadoResolucaoChange,
}: Props) {
  const [activeId, setActiveId] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(false);

  const sequencia = useMemo(() => {
    const pendentes = cotacaoFornecedores.filter((cf) => cf.status !== "CONFIRMADO");
    const confirmados = cotacaoFornecedores.filter((cf) => cf.status === "CONFIRMADO");
    return [...pendentes, ...confirmados];
  }, [cotacaoFornecedores]);

  const atual = cotacaoFornecedores.find((cf) => cf.id === activeId) ?? sequencia[0];
  const rascunhoAtual = atual ? (rascunhos[atual.id] ?? rascunhoVazio()) : rascunhoVazio();
  const { texto, preview } = rascunhoAtual;

  // Ver ConferenciaPanel original: distingue troca real de fornecedor de o próprio
  // efeito de auto-fetch se re-disparando porque ele mesmo acabou de gravar `preview`.
  const atualIdRef = useRef(atual?.id);
  useEffect(() => {
    atualIdRef.current = atual?.id;
  }, [atual?.id]);

  useEffect(() => {
    if (!fornecedorFocoId) return;
    if (cotacaoFornecedores.some((cf) => cf.id === fornecedorFocoId)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setActiveId(fornecedorFocoId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fornecedorFocoId]);

  // Abre a Conferência automaticamente ao selecionar um fornecedor PROCESSADO sem
  // preview ainda em memória.
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

  // Encadeamento pós-confirmação: avança pro próximo pendente; se não sobrar nenhum,
  // fica no fornecedor recém-confirmado (agora somente-leitura) — quem decide "e
  // agora?" é o rodapé do modal (botão Lançar), não este componente.
  async function onFornecedorConfirmado(cotacaoFornecedorId: string) {
    await onCotacaoFornecedoresAtualizados();
    const restantes = sequencia.filter((cf) => cf.id !== cotacaoFornecedorId && cf.status !== "CONFIRMADO");
    if (restantes.length > 0) setActiveId(restantes[0].id);
  }

  if (!atual) {
    return (
      <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">
        Nenhum fornecedor adicionado a esta cotação ainda — volte ao Passo 2 pra adicionar um.
      </div>
    );
  }

  return (
    <div className="space-y-3">
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

      <div className="flex flex-wrap gap-2">
        {sequencia.map((cf) => (
          <button
            key={cf.id}
            type="button"
            onClick={() => setActiveId(cf.id)}
            disabled={carregando}
            className={`inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border-[1.5px] px-3 py-1.5 text-[12.5px] font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
              cf.id === atual.id ? "border-inf bg-inf/[.06] text-t1" : "border-bdr text-t2 hover:bg-hov"
            }`}
          >
            <span className={`h-2.5 w-2.5 rounded-full ${STATUS_DOT[cf.status] ?? "bg-t3"}`} />
            {cf.nomeFornecedor ?? "Fornecedor"}
            {cf.status === "CONFIRMADO" && <span className="font-bold text-ok">✓</span>}
          </button>
        ))}
      </div>

      {atual.status === "CONFIRMADO" ? (
        <ConferenciaConfirmadaReadOnly
          key={atual.id}
          cotacaoId={cotacaoId}
          fornecedorId={atual.fornecedorId}
          fornecedorNome={atual.nomeFornecedor ?? ""}
        />
      ) : atual.status === "PENDENTE" ? (
        <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">
          Este fornecedor ainda não respondeu — volte ao Passo 2 pra colar a resposta dele.
        </div>
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
