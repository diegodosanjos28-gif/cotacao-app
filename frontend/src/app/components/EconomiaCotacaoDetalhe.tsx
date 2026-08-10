import { useMemo } from "react";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import { DetalheFornecedorItem, detalhamentoPorFornecedor } from "@/lib/comparativo";
import { exportarConferenciaNota } from "@/lib/conferenciaNotaPdf";
import { formatarMoeda, formatarQuantidade } from "@/lib/format";
import { Cotacao, ComparativoItemResponse } from "@/lib/types";

const DOT_COLORS = ["#FF8000", "#2563EB", "#16A34A", "#9333EA", "#0D9488", "#DB2777"];

const COLUNAS_ITENS_FORNECEDOR: ColumnDef<DetalheFornecedorItem>[] = [
  {
    id: "produto",
    header: "Produto",
    accessorFn: (item) => item.nomeProduto,
    meta: { headerClassName: "px-3 py-2 font-semibold", cellClassName: "px-3 py-2 text-t1" },
  },
  {
    id: "qtd",
    header: "Qtd",
    cell: ({ row }) => `${formatarQuantidade(row.original.quantidade)} ${row.original.unidade}`,
    meta: { headerClassName: "px-3 py-2 text-center font-semibold", cellClassName: "px-3 py-2 text-center text-t2" },
  },
  {
    id: "unitario",
    header: "Unit.",
    cell: ({ row }) => formatarMoeda(row.original.precoUnitario),
    meta: { headerClassName: "px-3 py-2 text-right font-semibold", cellClassName: "px-3 py-2 text-right text-t2" },
  },
  {
    id: "total",
    header: "Total",
    cell: ({ row }) => formatarMoeda(row.original.total),
    meta: {
      headerClassName: "px-3 py-2 text-right font-semibold",
      cellClassName: "px-3 py-2 text-right font-medium text-t1",
    },
  },
];

function TabelaItensFornecedor({ itens }: { itens: DetalheFornecedorItem[] }) {
  const table = useReactTable({
    data: itens,
    columns: COLUNAS_ITENS_FORNECEDOR,
    getRowId: (item) => item.cotacaoProdutoId,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <DataGrid
      table={table}
      tableClassName="w-full text-sm"
      theadClassName="text-left text-[10.5px] uppercase tracking-wide text-t2"
      tbodyClassName="divide-y divide-bdr"
    />
  );
}

// Conteúdo da linha expandida de "Conferência de Nota" no Dashboard — detalhamento por
// fornecedor + exportação em PDF. Extraído de EconomiaCotacaoLinha para ser usado como
// renderRowDetail do DataGrid, que já provê o <tr>/<td> de expansão.
export default function EconomiaCotacaoDetalhe({
  cotacao,
  itens,
}: {
  cotacao: Cotacao;
  itens: ComparativoItemResponse[];
}) {
  const detalhes = useMemo(() => detalhamentoPorFornecedor(itens), [itens]);

  if (detalhes.length === 0) {
    return <p className="text-sm text-t2">Nenhum fornecedor com oferta válida nesta cotação.</p>;
  }

  return (
    <>
      <div className="space-y-3">
        {detalhes.map((d, idx) => (
          <div key={d.fornecedorId} className="overflow-hidden rounded-lg border border-bdr bg-card">
            <div className="flex items-center justify-between border-b border-bdr px-3 py-2">
              <span className="flex items-center gap-2 text-sm font-semibold text-t1">
                <span
                  className="h-2 w-2 shrink-0 rounded-full"
                  style={{ background: DOT_COLORS[idx % DOT_COLORS.length] }}
                />
                {d.nomeFornecedor}
              </span>
              <span className="text-xs text-t2">
                {d.itens.length} {d.itens.length === 1 ? "item" : "itens"} ·{" "}
                <strong className="font-semibold text-prx">{formatarMoeda(d.totalFornecedor)}</strong>
              </span>
            </div>
            <TabelaItensFornecedor itens={d.itens} />
          </div>
        ))}
      </div>
      <div className="mt-3 flex justify-end">
        <button
          onClick={() => exportarConferenciaNota(cotacao, itens)}
          className="rounded-md border border-prx/30 bg-prx/10 px-3 py-1.5 text-xs font-semibold text-prx hover:bg-prx hover:text-white"
        >
          Exportar PDF
        </button>
      </div>
    </>
  );
}
