import { useMemo } from "react";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import StatCard from "@/components/StatCard";
import StatusBadge from "@/components/StatusBadge";
import { tendencia, variacaoEntrePontos } from "@/lib/historicoPrecos";
import { formatarData, formatarMoeda, formatarPercentual, formatarQuantidade } from "@/lib/format";
import { HistoricoPrecoProduto, PontoReferenciaPreco } from "@/lib/types";

const LABELS_CARD = ["Cotação Atual", "Última Cotação", "Penúltima Cotação"] as const;

const TH_CLASSE = "px-3.5 py-2 font-semibold";
const TH_CLASSE_DIREITA = "px-3.5 py-2 text-right font-semibold";
const TD_CLASSE = "px-3.5 py-2.5 text-t2";
const TD_CLASSE_DIREITA = "px-3.5 py-2.5 text-right text-t2";

// Painel de detalhe exibido abaixo da busca ao selecionar um produto — fiel ao
// #hist-detail-area do protótipo validado (COTA&TESTA - 14.07 - V5.html):
// cabeçalho com badge de tendência, 3 cards de referência e a tabela de histórico.
export default function HistoricoPrecoDetalhe({ produto }: { produto: HistoricoPrecoProduto }) {
  const tend = tendencia(produto);

  const colunas = useMemo<ColumnDef<PontoReferenciaPreco>[]>(
    () => [
      {
        id: "data",
        header: "Data",
        accessorFn: (ponto) => ponto.finalizadaEm,
        cell: (info) => formatarData(info.getValue<string>()),
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      {
        id: "preco",
        header: "Preço",
        accessorFn: (ponto) => ponto.preco,
        cell: (info) => formatarMoeda(info.getValue<number>()),
        meta: { headerClassName: TH_CLASSE_DIREITA, cellClassName: "px-3.5 py-2.5 text-right font-medium text-t1" },
      },
      {
        id: "fornecedor",
        header: "Fornecedor",
        accessorFn: (ponto) => ponto.nomeFornecedor,
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      {
        id: "variacao",
        header: "Variação",
        cell: ({ row }) => formatarPercentual(variacaoEntrePontos(produto, row.index)),
        meta: { headerClassName: TH_CLASSE_DIREITA, cellClassName: TD_CLASSE_DIREITA },
      },
    ],
    [produto],
  );

  const table = useReactTable({
    data: produto.pontos,
    columns: colunas,
    getRowId: (ponto) => ponto.cotacaoId,
    getCoreRowModel: getCoreRowModel(),
  });

  if (produto.pontos.length === 0) {
    return (
      <div className="mt-4 rounded-xl border border-bdr bg-card p-6">
        <p className="text-sm text-t2">Sem histórico de preços para este produto.</p>
      </div>
    );
  }

  return (
    <div className="mt-4 rounded-xl border border-bdr bg-card p-5">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-base font-bold text-t1">{produto.nomeProduto}</div>
          {produto.quantidadeReferencia !== null && (
            <div className="text-xs text-t2">
              {formatarQuantidade(produto.quantidadeReferencia)} {produto.unidadeReferencia}
            </div>
          )}
        </div>
        {tend && <StatusBadge status={tend} />}
      </div>

      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
        {[0, 1, 2].map((indice) => {
          const ponto = produto.pontos[indice];
          return (
            <StatCard
              key={indice}
              label={LABELS_CARD[indice]}
              value={ponto ? formatarMoeda(ponto.preco) : "—"}
              hint={ponto ? (indice === 0 ? ponto.nomeFornecedor : `${ponto.nomeFornecedor} · ${formatarData(ponto.finalizadaEm)}`) : undefined}
            />
          );
        })}
      </div>

      <div className="mt-4 overflow-hidden rounded-lg border border-bdr">
        <div className="border-b border-bdr bg-surf px-3.5 py-2">
          <span className="text-[10.5px] font-semibold uppercase tracking-wide text-t3">Histórico de Referências</span>
        </div>
        <DataGrid
          table={table}
          tableClassName="w-full text-sm"
          theadClassName="text-left text-[10px] uppercase tracking-wide text-t3"
          tbodyClassName="divide-y divide-bdr"
        />
      </div>
    </div>
  );
}
