"use client";

import { variacaoPreco } from "@/lib/api";
import { formatarMoeda, formatarPercentual } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";

// Painel "Variação de Preço por Produto" (Dashboard, linha 2 — 50%) — Top 5 produtos
// por spread dentro da competência selecionada. Cálculo 100% no backend
// (VariacaoPrecoService), aqui só formata e desenha a barra proporcional ao maior
// spread da lista recebida.
export default function VariacaoPrecoPanel({ mes }: { mes: string }) {
  const { data, carregando } = useAsync(() => variacaoPreco(mes), [mes], "Não foi possível carregar a variação de preço.");

  const produtos = data?.produtos ?? [];
  const maiorSpread = produtos.length > 0 ? produtos[0].spreadPct : 0;

  return (
    <div
      className="flex flex-col rounded-2xl border border-bdr p-5 sm:p-6"
      style={{
        background:
          "radial-gradient(130% 150% at 100% 0%, rgba(254,118,65,.12), transparent 55%), linear-gradient(158deg, #FCFBF8, #F3F0EA 60%, #ECE8DF)",
        boxShadow: "0 10px 30px rgba(37,34,32,.09), 0 2px 6px rgba(37,34,32,.05)",
      }}
    >
      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-[15px] font-medium text-t1">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="#FE7641" strokeWidth={2.1} strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 6h18M7 12h10M11 18h2" />
            <path d="M3 6l3 3-3 3" />
          </svg>
          Variação de Preço por Produto
        </div>
        <span className="rounded-full border border-prx/30 bg-prx/10 px-2.5 py-1 text-[10.5px] font-medium text-[#9a4d00]">
          {data ? `${data.totalProdutosComparados} produto${data.totalProdutosComparados === 1 ? "" : "s"}` : "—"}
        </span>
      </div>

      <p className="mb-4 text-[11px] leading-relaxed text-t2">
        Onde negociar primeiro — itens com maior diferença entre o menor e o maior preço cotado no período.
      </p>

      {carregando && <p className="text-sm text-t2">Carregando...</p>}
      {!carregando && produtos.length === 0 && (
        <p className="text-sm text-t2">Nenhum produto cotado por 2 ou mais fornecedores nesta competência.</p>
      )}

      <div className="mt-auto flex flex-col gap-4">
        {produtos.map((p) => (
          <div key={p.produtoId} className="flex flex-col gap-1.5">
            <div className="flex items-baseline justify-between gap-2">
              <span className="truncate text-[13px] text-t1">{p.nomeProduto}</span>
              <span className="shrink-0 font-light text-[22px] tracking-tight text-prx-l">
                +{formatarPercentual(p.spreadPct)}
              </span>
            </div>
            <div className="flex items-center gap-2.5">
              <span className="shrink-0 whitespace-nowrap text-[11.5px] font-medium text-[#0a7a4a]">
                {formatarMoeda(p.menorPreco)}
              </span>
              <span className="relative h-[7px] flex-1 overflow-hidden rounded-full bg-t1/[.09]">
                <span
                  className="absolute left-0 top-0 h-full rounded-full"
                  style={{
                    width: maiorSpread > 0 ? `${(p.spreadPct / maiorSpread) * 100}%` : "0%",
                    background: "linear-gradient(90deg, #FE7641, #FFB25E)",
                  }}
                />
              </span>
              <span className="shrink-0 whitespace-nowrap text-[11.5px] font-medium text-[#c0392b]">
                {formatarMoeda(p.maiorPreco)}
              </span>
            </div>
            <p className="text-[10.5px] text-t3">
              Δ <b className="font-medium text-t1">{formatarMoeda(p.maiorPreco - p.menorPreco)}</b>/un · melhor:{" "}
              <b className="font-medium text-t1">{p.fornecedorMenorNome}</b> · mais caro: {p.fornecedorMaiorNome}
            </p>
          </div>
        ))}
      </div>

      {produtos.length > 0 && (
        <p className="mt-3.5 border-t border-bdr pt-3 text-[10px] text-t3">
          Comprando sempre pelo menor preço, o maior spread é o {produtos[0].nomeProduto} (+
          {formatarPercentual(produtos[0].spreadPct)}).
        </p>
      )}
    </div>
  );
}
