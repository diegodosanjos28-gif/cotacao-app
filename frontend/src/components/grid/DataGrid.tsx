import { Fragment, ReactNode } from "react";
import { flexRender, Row, Table } from "@tanstack/react-table";
import "./types";

export interface DataGridProps<TData> {
  table: Table<TData>;

  /** Classes exatas da tela, repassadas verbatim — DataGrid não define nenhuma. */
  wrapperClassName?: string;
  tableClassName?: string;
  theadClassName?: string;
  /** Classe da(s) <tr> de cabeçalho — algumas telas estilizam a linha em vez do <thead>. */
  theadRowClassName?: string;
  tbodyClassName?: string;

  rowClassName?: string | ((row: TData, index: number) => string);
  onRowClick?: (row: TData) => void;

  /** Linhas extras (ex: linha "nova/pendente") renderizadas antes das linhas mapeadas. */
  extraRows?: ReactNode;

  /** Conteúdo de uma linha expandida — chamado só quando row.getIsExpanded() é true. */
  renderRowDetail?: (row: Row<TData>) => ReactNode;
  rowDetailClassName?: string;
  rowDetailCellClassName?: string;

  loading?: boolean;
  loadingContent?: ReactNode;
  emptyContent?: ReactNode;
}

function resolveRowClassName<TData>(
  rowClassName: DataGridProps<TData>["rowClassName"],
  row: TData,
  index: number,
): string | undefined {
  if (typeof rowClassName === "function") return rowClassName(row, index);
  return rowClassName;
}

function resolveCellClassName<TData>(
  cellClassName: string | ((row: TData) => string) | undefined,
  row: TData,
): string | undefined {
  if (typeof cellClassName === "function") return cellClassName(row);
  return cellClassName;
}

export default function DataGrid<TData>({
  table,
  wrapperClassName,
  tableClassName,
  theadClassName,
  theadRowClassName,
  tbodyClassName,
  rowClassName,
  onRowClick,
  extraRows,
  renderRowDetail,
  rowDetailClassName,
  rowDetailCellClassName,
  loading,
  loadingContent,
  emptyContent,
}: DataGridProps<TData>) {
  const rows = table.getRowModel().rows;
  const colSpan = table.getVisibleLeafColumns().length;

  // Sem <div> wrapper quando wrapperClassName não é passado — muitas telas já têm seu
  // próprio container ao redor da tabela (ex: uma legenda acima), e um <div> extra sem
  // classe alteraria a estrutura DOM (afeta seletores de irmão/filho direto e o
  // ancestral esperado por `position: sticky` no thead).
  const grid = (
    <table className={tableClassName}>
      <thead className={theadClassName}>
        {table.getHeaderGroups().map((headerGroup) => (
          <tr key={headerGroup.id} className={theadRowClassName}>
            {headerGroup.headers.map((header) => (
              <th key={header.id} className={header.column.columnDef.meta?.headerClassName}>
                {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
              </th>
            ))}
          </tr>
        ))}
      </thead>
      <tbody className={tbodyClassName}>
        {extraRows}
        {loading && loadingContent}
        {!loading && rows.length === 0 && emptyContent}
        {!loading &&
          rows.map((row, index) => (
            <Fragment key={row.id}>
              <tr
                className={resolveRowClassName(rowClassName, row.original, index)}
                onClick={onRowClick ? () => onRowClick(row.original) : undefined}
              >
                {row.getVisibleCells().map((cell) => (
                  <td
                    key={cell.id}
                    className={resolveCellClassName(cell.column.columnDef.meta?.cellClassName, row.original)}
                  >
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
              {renderRowDetail && row.getIsExpanded() && (
                <tr className={rowDetailClassName}>
                  <td colSpan={colSpan} className={rowDetailCellClassName}>
                    {renderRowDetail(row)}
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
      </tbody>
    </table>
  );

  return wrapperClassName ? <div className={wrapperClassName}>{grid}</div> : grid;
}
