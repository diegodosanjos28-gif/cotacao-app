"use client";

import { useMemo } from "react";
import Card from "@/components/Card";
import StatCard from "@/components/StatCard";
import { cobertura, melhorContagem, todosFornecedores, totalSeTudoDeUmFornecedor, totalRecomendado } from "@/lib/comparativo";
import { formatarMoeda } from "@/lib/format";
import { ComparativoItemResponse, Fornecedor } from "@/lib/types";

export default function Fornecedores({
  itens,
  fornecedores,
}: {
  itens: ComparativoItemResponse[];
  fornecedores: Fornecedor[];
}) {
  const idsNaCotacao = useMemo(() => todosFornecedores(itens), [itens]);
  const fornecedoresNaCotacao = fornecedores.filter((f) => idsNaCotacao.has(f.id));

  const ranking = useMemo(
    () =>
      fornecedoresNaCotacao
        .map((f) => ({ fornecedor: f, contagem: melhorContagem(itens, f.id) }))
        .sort((a, b) => b.contagem - a.contagem),
    [fornecedoresNaCotacao, itens],
  );

  const maiorContagem = ranking[0]?.contagem || 1;
  const recomendado = totalRecomendado(itens);

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {fornecedoresNaCotacao.map((f) => {
          const cov = cobertura(itens, f.id);
          return (
            <StatCard
              key={f.id}
              label={f.nome}
              value={`${cov.quantidade}/${itens.length}`}
              hint={`${cov.percentual.toFixed(0)}% de cobertura`}
            />
          );
        })}
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <h2 className="font-semibold text-t1">Ranking geral</h2>
          <ul className="mt-4 space-y-3">
            {ranking.map(({ fornecedor, contagem }, i) => (
              <li key={fornecedor.id} className="flex items-center gap-3">
                <span className="w-5 text-sm font-semibold text-t3">{i + 1}º</span>
                <span className="flex-1 text-sm text-t1">{fornecedor.nome}</span>
                <span className="text-sm font-medium text-ok">{contagem} melhores preços</span>
              </li>
            ))}
            {ranking.length === 0 && <p className="text-sm text-t2">Nenhum fornecedor cotado ainda.</p>}
          </ul>
        </Card>

        <Card>
          <h2 className="font-semibold text-t1">Distribuição de melhores preços</h2>
          <div className="mt-4 space-y-2">
            {ranking.map(({ fornecedor, contagem }) => (
              <div key={fornecedor.id}>
                <div className="flex items-center justify-between text-xs text-t2">
                  <span>{fornecedor.nome}</span>
                  <span>{contagem}</span>
                </div>
                <div className="mt-0.5 h-2 w-full rounded-full bg-hov">
                  <div
                    className="h-2 rounded-full bg-prx"
                    style={{ width: `${(contagem / maiorContagem) * 100}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {fornecedoresNaCotacao.map((f) => {
          const totalSeTudo = totalSeTudoDeUmFornecedor(itens, f.id);
          return (
            <Card key={f.id}>
              <h3 className="font-medium text-t1">{f.nome}</h3>
              <dl className="mt-3 space-y-1 text-xs text-t2">
                <p>
                  <span className="font-medium">Se comprar tudo dele:</span> {formatarMoeda(totalSeTudo)}
                </p>
                <p>
                  <span className="font-medium">Total recomendado (misto):</span> {formatarMoeda(recomendado)}
                </p>
                <p>
                  <span className="font-medium">Prazo:</span> {f.prazoEntregaPadrao ?? "—"}
                </p>
                <p>
                  <span className="font-medium">Pagamento:</span> {f.condicaoPagamentoPadrao ?? "—"}
                </p>
                <p>
                  <span className="font-medium">Pedido mínimo:</span>{" "}
                  {f.pedidoMinimoPadrao != null ? formatarMoeda(f.pedidoMinimoPadrao) : "—"}
                </p>
                {f.observacoesPadrao && (
                  <p>
                    <span className="font-medium">Observações:</span> {f.observacoesPadrao}
                  </p>
                )}
              </dl>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
