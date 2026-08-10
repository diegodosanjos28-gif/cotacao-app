import Link from "next/link";
import { fornecedorMaisCompetitivo, itensSemCotacao, totalEconomia } from "@/lib/comparativo";
import { formatarMoeda } from "@/lib/format";
import { Cotacao, ComparativoItemResponse } from "@/lib/types";

// Conteúdo da linha expandida de "Todas as cotações" no Dashboard — resumo rápido +
// atalho pra abrir a cotação. Extraído de CotacaoLinha para ser usado como
// renderRowDetail do DataGrid, que já provê o <tr>/<td> de expansão.
export default function CotacaoResumoExpandido({
  cotacao,
  itens,
}: {
  cotacao: Cotacao;
  itens: ComparativoItemResponse[] | undefined;
}) {
  const semCotacao = itens ? itensSemCotacao(itens) : [];
  const economia = itens ? totalEconomia(itens) : 0;
  const melhorFornecedor = itens ? fornecedorMaisCompetitivo(itens) : null;
  const destino = `/cotacoes/${cotacao.id}/entrada`;

  return (
    <Link href={destino} className="block rounded-md p-3 transition-colors hover:bg-hov">
      {!itens ? (
        <p className="text-sm text-t2">Carregando resumo...</p>
      ) : itens.length === 0 ? (
        <p className="text-sm text-t2">Nenhum produto adicionado a esta cotação ainda.</p>
      ) : (
        <div className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
          <div>
            <p className="text-xs uppercase tracking-wide text-t3">Economia potencial</p>
            <p className="mt-0.5 font-semibold text-ok">{formatarMoeda(economia)}</p>
          </div>
          <div>
            <p className="text-xs uppercase tracking-wide text-t3">Produtos cotados</p>
            <p className={`mt-0.5 font-semibold ${semCotacao.length > 0 ? "text-wa" : "text-t1"}`}>
              {itens.length - semCotacao.length}/{itens.length}
            </p>
          </div>
          <div>
            <p className="text-xs uppercase tracking-wide text-t3">Fornecedor mais competitivo</p>
            <p className="mt-0.5 font-semibold text-prx">{melhorFornecedor ? melhorFornecedor.nome : "—"}</p>
          </div>
          <div className="flex items-end justify-end text-xs font-medium text-prx">Abrir cotação →</div>
        </div>
      )}
    </Link>
  );
}
