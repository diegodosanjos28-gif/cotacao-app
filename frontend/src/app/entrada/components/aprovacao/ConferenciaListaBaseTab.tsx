"use client";

import { useMemo, useState } from "react";
import { buscarLista } from "@/lib/api";
import { useEdicaoItemLista } from "@/hooks/useEdicaoItemLista";
import { ItemListaResponse, Produto } from "@/lib/types";

function PencilIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}

interface RascunhoEdicao {
  qtd: string;
  un: string;
  produto: string;
}

// Aba 1 do modal de aprovação — "o que foi pedido". Mais simples que
// GridProdutosSection: 3 campos de texto livre por linha, sem autocomplete/
// paginação/filtro (fonte visual: mockup_entrada_2.html, renderStep1). Edição de
// produto é sempre texto livre, mesmo quando o item já tem produtoIdEncontrado — o
// operador está corrigindo o que a AI leu, não recatalogando; produtoId é
// explicitamente zerado no commit pra não ser sobrescrito silenciosamente por
// editarItemCotacao (que prioriza produtoId sobre nomeProdutoLivre quando os dois
// vêm preenchidos).
export default function ConferenciaListaBaseTab({
  cotacaoId,
  itens,
  produtos,
  onListaAtualizada,
  cotacaoFinalizada,
}: {
  cotacaoId: string;
  itens: ItemListaResponse[];
  produtos: Produto[];
  onListaAtualizada: (itens: ItemListaResponse[]) => void;
  cotacaoFinalizada: boolean;
}) {
  const [editandoId, setEditandoId] = useState<string | null>(null);
  const [rascunhoEdicao, setRascunhoEdicao] = useState<RascunhoEdicao>({ qtd: "", un: "", produto: "" });

  const produtoNomePorId = useMemo(() => new Map(produtos.map((p) => [p.id, p.nome])), [produtos]);

  function nomeExibido(item: ItemListaResponse): string {
    if (item.produtoIdEncontrado) return produtoNomePorId.get(item.produtoIdEncontrado) ?? item.textoOriginal;
    return item.textoOriginal || "—";
  }

  function itemBloqueado(item: ItemListaResponse) {
    return item.temRespostaFornecedorConfirmada || cotacaoFinalizada;
  }

  const { salvando, erros, commitRow } = useEdicaoItemLista({
    cotacaoId,
    itemBloqueado,
    onSalvo: async () => {
      onListaAtualizada(await buscarLista(cotacaoId));
    },
  });

  function iniciarEdicao(item: ItemListaResponse) {
    if (itemBloqueado(item)) return;
    setEditandoId(item.id);
    setRascunhoEdicao({ qtd: String(item.quantidade), un: item.unidade, produto: nomeExibido(item) });
  }

  async function salvar(item: ItemListaResponse) {
    await commitRow(item, { quantidade: rascunhoEdicao.qtd, unidade: rascunhoEdicao.un, produtoId: null, nomeExibido: rascunhoEdicao.produto });
    setEditandoId(null);
  }

  return (
    <div>
      <div className="mb-4 flex items-start gap-3 rounded-xl border border-prx/22 bg-prx/[.06] p-3.5">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] bg-prx/[.12] text-prx-l">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 2h6a1 1 0 0 1 1 1v1h1a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h1V3a1 1 0 0 1 1-1z" />
            <path d="M9 12l2 2 4-4" />
          </svg>
        </span>
        <div>
          <div className="text-[15px] font-extrabold text-t1">Conferência da Lista Base</div>
          <p className="mt-0.5 text-xs leading-relaxed text-t2">
            Confira se a AI leu corretamente os produtos, quantidades e unidades. Use o lápis para corrigir uma linha.
          </p>
        </div>
      </div>

      <div className="overflow-hidden rounded-xl border border-bdr">
        <div className="grid grid-cols-[60px_52px_1fr_40px] gap-2.5 bg-surf px-3.5 py-2 text-[9.5px] font-bold uppercase tracking-wide text-t3">
          <span>Qtd</span>
          <span>Un</span>
          <span>Produto</span>
          <span />
        </div>
        {itens.map((item) => {
          const editando = editandoId === item.id;
          const erro = erros[item.id];
          return (
            <div key={item.id} className={`border-t border-t1/[.06] px-3.5 py-2.5 ${editando ? "bg-prx/[.05]" : ""}`}>
              {editando ? (
                <div className="grid grid-cols-[60px_52px_1fr_40px] items-center gap-2.5">
                  <input
                    value={rascunhoEdicao.qtd}
                    onChange={(e) => setRascunhoEdicao((r) => ({ ...r, qtd: e.target.value }))}
                    className="w-full min-w-0 rounded-md border-[1.5px] border-prx px-2 py-1 text-[12.5px] outline-none"
                  />
                  <input
                    value={rascunhoEdicao.un}
                    onChange={(e) => setRascunhoEdicao((r) => ({ ...r, un: e.target.value }))}
                    className="w-full min-w-0 rounded-md border-[1.5px] border-prx px-2 py-1 text-[12.5px] outline-none"
                  />
                  <input
                    value={rascunhoEdicao.produto}
                    onChange={(e) => setRascunhoEdicao((r) => ({ ...r, produto: e.target.value }))}
                    className="w-full min-w-0 rounded-md border-[1.5px] border-prx px-2 py-1 text-[12.5px] outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => salvar(item)}
                    disabled={!!salvando[item.id]}
                    title="Salvar"
                    className="flex h-7 w-7 items-center justify-center rounded-md border border-ok bg-ok text-white disabled:opacity-50"
                  >
                    <CheckIcon />
                  </button>
                </div>
              ) : (
                <div className="grid grid-cols-[60px_52px_1fr_40px] items-center gap-2.5 text-[13px]">
                  <span className="font-bold text-t1">{item.quantidade}</span>
                  <span className="text-xs text-t2">{item.unidade}</span>
                  <span className="text-t1">{nomeExibido(item)}</span>
                  <button
                    type="button"
                    onClick={() => iniciarEdicao(item)}
                    disabled={itemBloqueado(item)}
                    title="Corrigir"
                    className="flex h-7 w-7 items-center justify-center rounded-md border border-bdr text-t3 hover:border-prx hover:text-prx disabled:opacity-40"
                  >
                    <PencilIcon />
                  </button>
                </div>
              )}
              {erro && <p className="mt-1.5 text-xs text-er">{erro}</p>}
            </div>
          );
        })}
      </div>
    </div>
  );
}
