"use client";

import { useState } from "react";
import { editarItemCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ItemListaResponse } from "@/lib/types";

// Rascunho compartilhado por linha — extraído de GridProdutosSection (Prompt 12) para
// ser reaproveitado também pela aba "Lista Base" do modal de aprovação (Fase C do
// refactor da Entrada de Dados, 2026-08-20). quantidade/unidade/produtoId/nomeExibido
// sempre são enviadas juntas a editarItemCotacao mesmo quando só um campo mudou —
// produtoId e nomeExibido usam `undefined` pra "sem rascunho" (cai no valor original
// do item) e `null` é um valor de rascunho válido (produto explicitamente limpo).
export interface RascunhoLinha {
  quantidade?: string;
  unidade?: string;
  produtoId?: string | null;
  nomeExibido?: string | null;
}

// Plumbing de edição-e-commit de item de cotação, sem nenhuma preocupação de UI
// (autocomplete, paginação, filtro, ordenação continuam de responsabilidade de quem
// usa o hook). `onSalvo` é fornecido pelo chamador porque o que precisa ser
// recarregado depois de um commit difere: GridProdutosSection também re-resolve o
// catálogo de produtos (pode ter criado um Produto novo via resolver-ou-criar); a aba
// "Lista Base" do modal de aprovação só recarrega a lista.
export function useEdicaoItemLista({
  cotacaoId,
  itemBloqueado,
  onSalvo,
}: {
  cotacaoId: string;
  itemBloqueado: (item: ItemListaResponse) => boolean;
  onSalvo: () => Promise<void>;
}) {
  const [rascunhos, setRascunhos] = useState<Record<string, RascunhoLinha>>({});
  const [salvando, setSalvando] = useState<Record<string, boolean>>({});
  const [erros, setErros] = useState<Record<string, string>>({});

  function draftQuantidade(item: ItemListaResponse) {
    return rascunhos[item.id]?.quantidade ?? String(item.quantidade);
  }

  function draftUnidade(item: ItemListaResponse) {
    return rascunhos[item.id]?.unidade ?? item.unidade;
  }

  function draftProdutoId(item: ItemListaResponse) {
    const rascunho = rascunhos[item.id];
    return rascunho && "produtoId" in rascunho ? (rascunho.produtoId ?? null) : item.produtoIdEncontrado;
  }

  function draftNomeExibido(item: ItemListaResponse) {
    return rascunhos[item.id]?.nomeExibido ?? null;
  }

  function onCellEdit(rowId: string, campo: keyof RascunhoLinha, valor: RascunhoLinha[keyof RascunhoLinha]) {
    setRascunhos((r) => ({ ...r, [rowId]: { ...r[rowId], [campo]: valor } }));
  }

  // `overrides` permite commitar um valor recém-digitado na mesma tick de um outro
  // campo (ex.: trocar a Unidade via <select onChange> junto com uma Qtd ainda não
  // "desfocada") sem depender do rascunho já estar refletido em `rascunhos` — evita a
  // regressão "digitar Qtd sem blur + trocar Unidade envia a Qtd stale".
  async function commitRow(item: ItemListaResponse, overrides?: RascunhoLinha) {
    if (itemBloqueado(item)) return;
    const novaQuantidade = overrides?.quantidade ?? draftQuantidade(item);
    const novaUnidade = overrides?.unidade ?? draftUnidade(item);
    const novoProdutoId = overrides && "produtoId" in overrides ? (overrides.produtoId ?? null) : draftProdutoId(item);
    const novoNomeLivre =
      overrides && "nomeExibido" in overrides ? (overrides.nomeExibido ?? null) : draftNomeExibido(item);

    const qtdNum = Number(novaQuantidade.replace(",", "."));
    if (!qtdNum || qtdNum <= 0 || !novaUnidade.trim()) return;

    setSalvando((s) => ({ ...s, [item.id]: true }));
    setErros((e) => {
      const resto = { ...e };
      delete resto[item.id];
      return resto;
    });
    try {
      await editarItemCotacao(cotacaoId, item.id, {
        quantidade: qtdNum,
        unidade: novaUnidade.trim(),
        produtoId: novoProdutoId ?? undefined,
        nomeProdutoLivre: novoProdutoId ? undefined : (novoNomeLivre ?? undefined),
      });
      await onSalvo();
    } catch (err) {
      setErros((e) => ({ ...e, [item.id]: getErrorMessage(err, "Não foi possível salvar a alteração.") }));
      setRascunhos((r) => {
        const resto = { ...r };
        delete resto[item.id];
        return resto;
      });
    } finally {
      setSalvando((s) => ({ ...s, [item.id]: false }));
    }
  }

  return {
    // Exposto principalmente pra quem hospeda o hook conseguir depender dele em
    // memoizações próprias (ex.: recalcular colunas de uma tabela quando um rascunho
    // muda) — os draft* readers já leem a partir dele internamente.
    rascunhos,
    salvando,
    erros,
    // Exposto pra quem hospeda o hook também poder registrar/limpar erro de linha em
    // fluxos que não passam por commitRow (ex.: exclusão de item em
    // GridProdutosSection) sem duplicar o mapa de erros por linha.
    setErros,
    draftQuantidade,
    draftUnidade,
    draftProdutoId,
    draftNomeExibido,
    onCellEdit,
    commitRow,
  };
}
