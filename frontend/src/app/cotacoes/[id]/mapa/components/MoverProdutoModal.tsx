"use client";

import { useState } from "react";
import Modal from "@/components/Modal";
import { moverItemMapa } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda, formatarQuantidade } from "@/lib/format";
import { ItemDistribuicao, PrecoFornecedor } from "@/lib/types";

// Ofertas de um item vêm do Comparativo (GET /cotacoes/{id}/comparativo), não do Mapa
// — o Mapa só devolve a distribuição vencedora por cenário, não todas as ofertas do
// item. "Válida" aqui usa o mesmo critério de MapaCompraService.ofertaValida:
// status OK e sem indicação de falta de estoque.
function ofertaValida(p: PrecoFornecedor): boolean {
  return p.status === "OK" && !p.semEstoque;
}

export default function MoverProdutoModal({
  open,
  onClose,
  cotacaoId,
  item,
  fornecedorAtualId,
  fornecedorAtualNome,
  ofertas,
  onMovido,
}: {
  open: boolean;
  onClose: () => void;
  cotacaoId: string;
  item: ItemDistribuicao;
  fornecedorAtualId: string;
  fornecedorAtualNome: string;
  ofertas: PrecoFornecedor[];
  onMovido: () => void;
}) {
  const [movendo, setMovendo] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  const candidatos = ofertas.filter((o) => o.fornecedorId !== fornecedorAtualId);

  async function mover(fornecedorId: string) {
    setMovendo(fornecedorId);
    setErro(null);
    try {
      await moverItemMapa(cotacaoId, item.cotacaoProdutoId, fornecedorId);
      onMovido();
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível mover o item."));
    } finally {
      setMovendo(null);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={`Mover "${item.produtoNome}"`}>
      <div className="space-y-3 text-sm">
        <p className="text-t2">
          {formatarQuantidade(item.quantidade)} {item.unidade} · Atual: <strong className="text-t1">{fornecedorAtualNome}</strong>{" "}
          · {formatarMoeda(item.precoUnitario)} → Total: {formatarMoeda(item.subtotal)}
        </p>
        {erro && <p className="text-er">{erro}</p>}

        {candidatos.length === 0 && (
          <p className="rounded-md border border-dashed border-bdr p-4 text-center text-t2">
            Nenhum outro fornecedor tem oferta válida para este item.
          </p>
        )}

        <ul className="space-y-2">
          {candidatos.map((o) => {
            const valida = ofertaValida(o);
            const totalCandidato = o.precoUnitarioCalculado * item.quantidade;
            const diffTotal = totalCandidato - item.subtotal;
            const igual = Math.abs(diffTotal) < 0.01;
            const impactoTexto = igual
              ? "Igual"
              : `${diffTotal > 0 ? "▲" : "▼"} ${diffTotal > 0 ? "+" : ""}${formatarMoeda(diffTotal)}`;
            const impactoClasses = igual ? "bg-surf text-t2" : diffTotal > 0 ? "bg-wa-d text-wa" : "bg-ok-d text-ok";

            return (
              <li key={o.fornecedorId}>
                <button
                  type="button"
                  disabled={!valida || movendo !== null}
                  onClick={() => mover(o.fornecedorId)}
                  className={`flex w-full items-center justify-between rounded-md border border-bdr px-4 py-3 text-left transition-colors ${
                    valida ? "hover:border-prx hover:bg-hov" : "cursor-not-allowed opacity-50"
                  }`}
                >
                  <span>
                    <span className="block font-medium text-t1">{o.nomeFornecedor}</span>
                    <span className="text-xs text-t2">
                      {formatarMoeda(o.precoUnitarioCalculado)} por {item.unidade} · Total: {formatarMoeda(totalCandidato)}
                      {!valida && " · sem oferta válida"}
                    </span>
                  </span>
                  <span className={`shrink-0 rounded px-1.5 py-0.5 text-xs font-semibold ${impactoClasses}`}>
                    {movendo === o.fornecedorId ? "Movendo..." : impactoTexto}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </Modal>
  );
}
