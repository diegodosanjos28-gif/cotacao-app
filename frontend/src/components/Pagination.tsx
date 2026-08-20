interface Props {
  pageIndex: number;
  pageSize: number;
  total: number;
  onPageChange: (pageIndex: number) => void;
  className?: string;
}

// Controles de paginação server-side reutilizáveis — extraído do bloco que já existia
// em GridProdutosSection.tsx (paginação client-side sobre TanStack Table). Aqui não
// depende de uma instância de `table`: recebe só os números e devolve o índice de
// página pedido, pra servir tanto grids (DataGrid + manualPagination) quanto listas
// que não usam tabela nenhuma (ex: Histórico de Preços).
export default function Pagination({ pageIndex, pageSize, total, onPageChange, className }: Props) {
  const pageCount = Math.max(Math.ceil(total / pageSize), 1);
  const podeAnterior = pageIndex > 0;
  const podeProxima = pageIndex + 1 < pageCount;

  if (total === 0) return null;

  return (
    <div className={`flex shrink-0 items-center justify-between gap-3 text-sm text-t2 ${className ?? ""}`}>
      <span>
        {pageIndex * pageSize + 1}–{Math.min((pageIndex + 1) * pageSize, total)} de {total}
      </span>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => onPageChange(pageIndex - 1)}
          disabled={!podeAnterior}
          className="rounded-md border border-bdr px-2.5 py-1 text-xs font-medium hover:bg-hov disabled:opacity-40"
        >
          Anterior
        </button>
        <span className="text-xs text-t3">
          Página {pageIndex + 1} de {pageCount}
        </span>
        <button
          type="button"
          onClick={() => onPageChange(pageIndex + 1)}
          disabled={!podeProxima}
          className="rounded-md border border-bdr px-2.5 py-1 text-xs font-medium hover:bg-hov disabled:opacity-40"
        >
          Próxima
        </button>
      </div>
    </div>
  );
}
