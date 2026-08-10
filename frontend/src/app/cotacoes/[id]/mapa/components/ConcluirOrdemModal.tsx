"use client";

import { useState } from "react";
import Modal from "@/components/Modal";
import { finalizarCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda } from "@/lib/format";
import { Cotacao, MapaCompraResponse } from "@/lib/types";

export default function ConcluirOrdemModal({
  open,
  onClose,
  cotacaoId,
  mapa,
  onFinalizada,
}: {
  open: boolean;
  onClose: () => void;
  cotacaoId: string;
  mapa: MapaCompraResponse;
  onFinalizada: (cotacao: Cotacao) => void;
}) {
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const abaixoDoMinimo = mapa.distribuicoes.filter((d) => d.abaixoDoPedidoMinimo);
  const [ciente, setCiente] = useState(false);

  async function onConfirmar() {
    setEnviando(true);
    setErro(null);
    try {
      const atualizada = await finalizarCotacao(cotacaoId, mapa.cenario);
      onFinalizada(atualizada);
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível finalizar a cotação."));
    } finally {
      setEnviando(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Concluir ordem de compra">
      <div className="space-y-4 text-sm">
        {erro && <p className="text-er">{erro}</p>}
        <div className="grid grid-cols-2 gap-3">
          <div className="rounded-md bg-surf p-3">
            <p className="text-xs text-t3">Valor final</p>
            <p className="font-semibold text-t1">{formatarMoeda(mapa.totalGeral)}</p>
          </div>
          <div className="rounded-md bg-surf p-3">
            <p className="text-xs text-t3">Economia vs. pior preço</p>
            <p className="font-semibold text-ok">{formatarMoeda(mapa.economiaComparadaAoPior)}</p>
          </div>
          <div className="rounded-md bg-surf p-3">
            <p className="text-xs text-t3">Fornecedores</p>
            <p className="font-semibold text-t1">{mapa.distribuicoes.length}</p>
          </div>
          <div className="rounded-md bg-surf p-3">
            <p className="text-xs text-t3">Produtos sem cotação</p>
            <p className="font-semibold text-t1">{mapa.produtosSemFornecedor.length}</p>
          </div>
        </div>

        {abaixoDoMinimo.length > 0 && (
          <div className="rounded-md border border-wa/30 bg-wa-d p-3">
            <label className="flex items-start gap-2 text-xs text-wa">
              <input type="checkbox" checked={ciente} onChange={(e) => setCiente(e.target.checked)} className="mt-0.5" />
              <span>
                Estou ciente que {abaixoDoMinimo.map((d) => d.fornecedorNome).join(", ")} ficará(ão) abaixo do pedido
                mínimo.
              </span>
            </label>
          </div>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
          >
            Cancelar
          </button>
          <button
            type="button"
            onClick={onConfirmar}
            disabled={enviando || (abaixoDoMinimo.length > 0 && !ciente)}
            className="rounded-md bg-ok px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {enviando ? "Finalizando..." : "Concluir ordem de compra"}
          </button>
        </div>
      </div>
    </Modal>
  );
}
