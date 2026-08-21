"use client";

import { useState } from "react";
import Modal from "@/components/Modal";
import { comparativo } from "@/lib/api";
import { detalhamentoPorFornecedor } from "@/lib/comparativo";
import { exportarConferenciaNota } from "@/lib/conferenciaNotaPdf";
import { corFornecedor } from "@/lib/coresFornecedor";
import { formatarData, formatarMoeda, formatarQuantidade } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";
import { CotacaoFinalizadaCursorResponse } from "@/lib/types";

// Modal de Conferência do carrossel — somente leitura (handoff, §5.1/Fora de escopo
// do prompt original: não é um segundo caminho de confirmação/persistência, esse já
// existe na Conferência sequencial de Entrada de Dados). Agrupamento por fornecedor
// via detalhamentoPorFornecedor (lib/comparativo.ts), já usado noutras telas — não
// reimplementado aqui.
export default function ConferenciaNotaModal({
  cotacao,
  onClose,
}: {
  cotacao: CotacaoFinalizadaCursorResponse | null;
  onClose: () => void;
}) {
  const cotacaoId = cotacao?.id ?? null;
  const { data: itens, carregando } = useAsync(
    () => (cotacaoId ? comparativo(cotacaoId) : Promise.resolve([])),
    [cotacaoId],
    "Não foi possível carregar o detalhamento por fornecedor.",
  );
  const [erroExportar, setErroExportar] = useState(false);

  const grupos = itens ? detalhamentoPorFornecedor(itens) : [];

  function onExportar() {
    if (!cotacao || !itens) return;
    setErroExportar(!exportarConferenciaNota(cotacao, itens));
  }

  return (
    <Modal
      open={cotacao !== null}
      onClose={onClose}
      className="flex max-h-[88vh] w-full max-w-[min(760px,100%)] flex-col overflow-hidden rounded-2xl border border-bdr bg-card shadow-[0_24px_60px_rgba(0,0,0,.35)]"
    >
      <div className="flex items-start justify-between gap-3 border-b border-bdr px-5 py-4">
        <div>
          <h2 className="text-lg font-bold tracking-tight text-t1">Conferência de Nota</h2>
          {cotacao && (
            <p className="mt-0.5 text-xs text-t2">
              Cotação de {formatarData(cotacao.finalizadaEm)} — {cotacao.itensCotados} itens ·{" "}
              {cotacao.fornecedoresCount} fornecedores
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Fechar"
          className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-md border border-bdr text-t2 hover:border-prx hover:text-prx"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div className="overflow-y-auto px-5 py-5">
        {cotacao && (
          <div className="mb-4 grid grid-cols-3 gap-2.5">
            <div className="rounded-md border border-bdr bg-surf px-3 py-2.5 text-center">
              <div className="text-base font-medium text-t1">{cotacao.itensCotados}</div>
              <div className="mt-0.5 text-[9.5px] uppercase tracking-wide text-t3">Itens cotados</div>
            </div>
            <div className="rounded-md border border-bdr bg-surf px-3 py-2.5 text-center">
              <div className="text-base font-medium text-t1">{formatarMoeda(cotacao.valorTotalComprado)}</div>
              <div className="mt-0.5 text-[9.5px] uppercase tracking-wide text-t3">Total comprado</div>
            </div>
            <div className="rounded-md border border-bdr bg-surf px-3 py-2.5 text-center">
              <div className="text-base font-medium text-eco-strong">{formatarMoeda(cotacao.economizado)}</div>
              <div className="mt-0.5 text-[9.5px] uppercase tracking-wide text-t3">Economizado</div>
            </div>
          </div>
        )}

        {carregando && <p className="text-sm text-t2">Carregando...</p>}
        {!carregando && grupos.length === 0 && (
          <p className="text-sm text-t2">Nenhum fornecedor com oferta válida nesta cotação.</p>
        )}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {grupos.map((g, i) => (
            <div key={g.fornecedorId} className="flex flex-col overflow-hidden rounded-[10px] border border-bdr">
              <div className="flex items-center gap-2 bg-[#1B3F73] px-3 py-2.5">
                <span
                  className="h-2 w-2 shrink-0 rounded-full shadow-[0_0_0_2px_rgba(255,255,255,.3)]"
                  style={{ background: corFornecedor(i) }}
                />
                <span className="min-w-0 flex-1 truncate text-[12.5px] font-medium text-white">{g.nomeFornecedor}</span>
                <span className="shrink-0 whitespace-nowrap font-light text-[12.5px] text-[#FFB266]">
                  {formatarMoeda(g.totalFornecedor)}
                </span>
              </div>
              <div className="grid grid-cols-[1fr_40px_66px_72px] gap-1.5 bg-[#EAF1F9] px-3 py-1.5 text-[9.5px] font-medium uppercase tracking-wide text-[#5B7290]">
                <span>Produto</span>
                <span className="text-right">Qtd</span>
                <span className="text-right">Unit.</span>
                <span className="text-right">Total</span>
              </div>
              {g.itens.map((it) => (
                <div
                  key={it.cotacaoProdutoId}
                  className="grid grid-cols-[1fr_40px_66px_72px] gap-1.5 border-b border-t1/[.05] px-3 py-1.5 text-[11px] text-t2 last:border-b-0"
                >
                  <span className="truncate">{it.nomeProduto}</span>
                  <span className="text-right">{formatarQuantidade(it.quantidade)}</span>
                  <span className="text-right">{formatarMoeda(it.precoUnitario)}</span>
                  <span className="text-right font-medium text-t1">{formatarMoeda(it.total)}</span>
                </div>
              ))}
            </div>
          ))}
        </div>

        {cotacao && itens && grupos.length > 0 && (
          <div className="mt-3 flex items-center justify-end gap-3">
            {erroExportar && <p className="text-xs text-er">Permita pop-ups para exportar o PDF.</p>}
            <button
              type="button"
              onClick={onExportar}
              className="rounded-md border border-prx/30 bg-prx/10 px-3 py-1.5 text-xs font-medium text-prx hover:bg-prx hover:text-white"
            >
              Exportar PDF
            </button>
          </div>
        )}
      </div>
    </Modal>
  );
}
