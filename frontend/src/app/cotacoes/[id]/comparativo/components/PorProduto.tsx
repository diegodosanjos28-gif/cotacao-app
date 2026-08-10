"use client";

import { useMemo, useState } from "react";
import Card from "@/components/Card";
import { diferencaPercentual, maiorOferta, menorOferta, ofertasValidas } from "@/lib/comparativo";
import { formatarMoeda } from "@/lib/format";
import { ComparativoItemResponse } from "@/lib/types";

function recomendacao(item: ComparativoItemResponse): string {
  const menor = menorOferta(item);
  const diff = diferencaPercentual(item);
  if (!menor) return "Nenhum fornecedor com preço válido para este item ainda.";
  if (diff !== null && diff > 20) {
    return `Diferença de ${diff.toFixed(0)}% entre fornecedores — vale confirmar antes de fechar.`;
  }
  return `Comprar de ${menor.nomeFornecedor} ao preço de ${formatarMoeda(menor.preco)}.`;
}

export default function PorProduto({ itens }: { itens: ComparativoItemResponse[] }) {
  const [busca, setBusca] = useState("");

  const filtrados = useMemo(
    () => itens.filter((item) => item.nomeProduto.toLowerCase().includes(busca.toLowerCase())),
    [itens, busca],
  );

  return (
    <div>
      <div className="flex items-center gap-3">
        <input
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          placeholder="Buscar produto..."
          className="rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
        <span className="text-sm text-t2">{filtrados.length} produtos</span>
      </div>

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {filtrados.map((item) => {
          const ofertas = ofertasValidas(item).sort((a, b) => a.preco - b.preco);
          const menor = ofertas[0];
          const maior = maiorOferta(item);
          const maxPreco = maior?.preco ?? 1;
          return (
            <Card key={item.cotacaoProdutoId}>
              <h3 className="font-medium text-t1">{item.nomeProduto}</h3>
              <div className="mt-3 space-y-2">
                {ofertas.map((oferta) => (
                  <div key={oferta.fornecedorId}>
                    <div className="flex items-center justify-between text-xs text-t2">
                      <span>{oferta.nomeFornecedor}</span>
                      <span className={oferta.fornecedorId === menor?.fornecedorId ? "font-semibold text-ok" : ""}>
                        {formatarMoeda(oferta.preco)}
                      </span>
                    </div>
                    <div className="mt-0.5 h-1.5 w-full rounded-full bg-hov">
                      <div
                        className={`h-1.5 rounded-full ${oferta.fornecedorId === menor?.fornecedorId ? "bg-ok" : "bg-prx"}`}
                        style={{ width: `${Math.max(8, (oferta.preco / maxPreco) * 100)}%` }}
                      />
                    </div>
                  </div>
                ))}
                {ofertas.length === 0 && <p className="text-xs text-t2">Sem cotação ainda.</p>}
              </div>

              {ofertas.length > 1 && (
                <div className="mt-3 grid grid-cols-3 gap-2 border-t border-bdr pt-2 text-center text-xs">
                  <div>
                    <p className="text-t3">Mín.</p>
                    <p className="font-medium text-t1">{formatarMoeda(ofertas[0].preco)}</p>
                  </div>
                  <div>
                    <p className="text-t3">Máx.</p>
                    <p className="font-medium text-t1">{formatarMoeda(ofertas[ofertas.length - 1].preco)}</p>
                  </div>
                  <div>
                    <p className="text-t3">Dif.</p>
                    <p className="font-medium text-t1">
                      {diferencaPercentual(item)?.toFixed(0) ?? 0}%
                    </p>
                  </div>
                </div>
              )}

              <p className="mt-3 text-xs text-t2">{recomendacao(item)}</p>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
