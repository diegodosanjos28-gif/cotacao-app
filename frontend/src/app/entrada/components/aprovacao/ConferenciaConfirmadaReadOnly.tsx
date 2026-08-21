"use client";

import { useEffect, useState } from "react";
import StatusBadge from "@/components/StatusBadge";
import { buscarConferenciaConfirmada } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda } from "@/lib/format";
import { ItemRespostaResponse } from "@/lib/types";

interface Props {
  cotacaoId: string;
  fornecedorId: string;
  fornecedorNome: string;
}

// Visão somente-leitura da conferência já confirmada de um fornecedor (Prompt 26).
// Não existe severidade persistida (OK/ATENCAO/REVISAR só vive em memória durante o
// preview) — só o resultado final por item, por isso não há anel/chips/accordion
// aqui, apenas uma tabela simples. Nenhuma prop de mutação: esta tela nunca chama
// enviarResposta/confirmarResposta, só lê — reabrir um fornecedor CONFIRMADO pelo
// caminho de preview o rebaixaria pra PROCESSADO, o que esta tela existe pra evitar.
export default function ConferenciaConfirmadaReadOnly({ cotacaoId, fornecedorId, fornecedorNome }: Props) {
  const [itens, setItens] = useState<ItemRespostaResponse[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    let cancelado = false;
    // Reset síncrono do estado de "carregando" ao trocar de fornecedor, antes de
    // disparar a busca — mesmo padrão de carregar() em entrada/page.tsx.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setItens(null);
    setErro(null);
    buscarConferenciaConfirmada(cotacaoId, fornecedorId)
      .then((resultado) => {
        if (!cancelado) setItens(resultado);
      })
      .catch((err) => {
        if (!cancelado) setErro(getErrorMessage(err, "Não foi possível carregar a conferência confirmada."));
      });
    return () => {
      cancelado = true;
    };
  }, [cotacaoId, fornecedorId]);

  return (
    <div className="rounded-lg border border-bdr bg-card p-4">
      <div>
        <h3 className="font-medium text-t1">Conferência confirmada — {fornecedorNome}</h3>
        <p className="mt-0.5 text-sm text-t2">
          {itens ? `${itens.length} item(ns) confirmados nesta cotação.` : "Carregando..."}
        </p>
      </div>

      {erro && (
        <p role="alert" className="mt-3 rounded-md border border-er/35 bg-er-d p-3 text-sm font-medium text-er">
          {erro}
        </p>
      )}

      {itens && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-bdr text-left text-xs uppercase tracking-wide text-t3">
                <th className="pb-2 pr-3">Item</th>
                <th className="pb-2 pr-3">Resposta do fornecedor</th>
                <th className="pb-2 pr-3">Preço</th>
                <th className="pb-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {itens.length === 0 ? (
                <tr>
                  <td colSpan={4} className="py-6 text-center text-sm text-t2">
                    Nenhum item confirmado para este fornecedor.
                  </td>
                </tr>
              ) : (
                itens.map((item) => (
                  <tr key={item.id} className="border-b border-bdr align-top">
                    <td className="py-3 pr-3 text-t1">{item.nomeProduto ?? "—"}</td>
                    <td className="py-3 pr-3 text-t1">{item.textoOriginal}</td>
                    <td className="py-3 pr-3 font-mono text-t1">
                      {item.semEstoque ? (
                        <span className="text-t3">Sem estoque</span>
                      ) : (
                        formatarMoeda(item.precoUnitarioCalculado)
                      )}
                    </td>
                    <td className="py-3">
                      <StatusBadge status={item.status} />
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
