"use client";

import { useState } from "react";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import { historicoPrecos } from "@/lib/api";
import {
  precoMaisRecente,
  produtosAcimaDaUltimaReferencia,
  produtosComHistorico,
  produtosComOportunidade,
} from "@/lib/historicoPrecos";
import { formatarMoeda } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";
import { HistoricoPrecoProduto } from "@/lib/types";
import HistoricoPrecoDetalhe from "../components/HistoricoPrecoDetalhe";

function IconClock({ className }: { className?: string }) {
  return (
    <svg className={className} width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <polyline points="12 6 12 12 16 14" />
    </svg>
  );
}

function IconSearch() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8" />
      <path d="M21 21l-4.35-4.35" />
    </svg>
  );
}

function HistoricoPrecosContent() {
  const { data: produtos, erro } = useAsync(
    () => historicoPrecos(),
    [],
    "Não foi possível carregar o histórico de preços.",
  );
  const [busca, setBusca] = useState("");
  const [selecionado, setSelecionado] = useState<HistoricoPrecoProduto | null>(null);

  const todos = produtos ?? [];
  const comHistorico = produtosComHistorico(todos).length;
  const acima = produtosAcimaDaUltimaReferencia(todos).length;
  const oportunidades = produtosComOportunidade(todos).length;

  // Lista sempre visível — busca filtra em vez de ser pré-condição pra mostrar algo.
  const buscaNorm = busca.trim().toLowerCase();
  const listaExibida = buscaNorm ? todos.filter((p) => p.nomeProduto.toLowerCase().includes(buscaNorm)) : todos;

  function selecionar(produto: HistoricoPrecoProduto) {
    setBusca(produto.nomeProduto);
    setSelecionado(produto);
  }

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
        <div className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight text-t1">Histórico de Preços</h1>
          <p className="mt-1 text-sm text-t2">
            Consulte as últimas referências de preço e identifique se a cotação atual está cara, dentro do padrão ou
            em oportunidade.
          </p>
        </div>

        {/* KPIs — cores fiéis ao protótipo validado (#p-historico .hist-kpi-1/2/3) */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="rounded-xl border border-[#444] bg-[#323232] p-5 shadow-[0_2px_10px_rgba(0,0,0,.18)]">
            <p className="mb-2 text-[10.5px] font-semibold uppercase tracking-wide text-white/65">Produtos com Histórico</p>
            <p className="mb-1 text-2xl font-bold leading-none tracking-tight text-white sm:text-[26px]">{comHistorico}</p>
            <p className="text-[11.5px] text-white/45">produtos com referências anteriores</p>
          </div>

          <div className="rounded-xl border border-[#f5d4b0] bg-[#FFE7D0] p-5 shadow-[0_2px_10px_rgba(252,110,32,.1)]">
            <p className="mb-2 text-[10.5px] font-semibold uppercase tracking-wide text-[#323232]">Acima da Última Referência</p>
            <p className={`mb-1 text-2xl font-bold leading-none tracking-tight sm:text-[26px] ${acima > 0 ? "text-[#c45500]" : "text-[#323232]"}`}>
              {acima}
            </p>
            <p className="text-[11.5px] text-[#5a4a3a]">
              <span
                className={`mr-1 rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                  acima > 0 ? "bg-[rgba(252,110,32,.15)] text-[#d45a00]" : "bg-[rgba(16,185,129,.12)] text-[#0d9668]"
                }`}
              >
                {acima > 0 ? "▲ Atenção" : "✓ OK"}
              </span>
              preço atual superior à última cotação
            </p>
          </div>

          <div className="rounded-xl border border-white/[.15] bg-[#FC6E20] p-5 shadow-[0_2px_10px_rgba(252,110,32,.25)]">
            <p className="mb-2 text-[10.5px] font-semibold uppercase tracking-wide text-white/75">Oportunidades Identificadas</p>
            <p className="mb-1 text-2xl font-bold leading-none tracking-tight text-white sm:text-[26px]">{oportunidades}</p>
            <p className="text-[11.5px] text-white/55">
              <span className="mr-1 rounded bg-white/20 px-1.5 py-0.5 text-[10px] font-semibold text-white">▼ Compra</span>
              preço atual abaixo das referências anteriores
            </p>
          </div>
        </div>

        {erro && <p className="mt-6 text-sm text-er">{erro}</p>}

        <div className="relative mt-6 max-w-lg">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-t3">
            <IconSearch />
          </span>
          <input
            type="text"
            value={busca}
            onChange={(e) => {
              setBusca(e.target.value);
              setSelecionado(null);
            }}
            placeholder="Buscar produto, marca, descrição ou código interno..."
            className="w-full rounded-md border border-bdr bg-card py-2 pl-9 pr-3 text-sm text-t1 shadow-[0_1px_4px_rgba(37,34,32,.06)] placeholder:text-t3 focus:border-prx focus:outline-none focus:ring-2 focus:ring-prx/20"
          />
        </div>

        {!selecionado && todos.length === 0 && (
          <div className="mt-4 flex flex-col items-center gap-3.5 rounded-xl border border-bdr bg-card px-5 py-12 text-center">
            <IconClock className="text-bdr-m/60" />
            <p className="max-w-sm text-sm text-t2">
              Nenhum produto no catálogo ainda. Cole uma lista de produtos em alguma cotação pra começar.
            </p>
          </div>
        )}

        {!selecionado && todos.length > 0 && listaExibida.length === 0 && (
          <div className="mt-4 rounded-xl border border-bdr bg-card px-5 py-12 text-center">
            <p className="text-sm text-t2">Nenhum produto encontrado para esta busca.</p>
          </div>
        )}

        {!selecionado && listaExibida.length > 0 && (
          <div className="mt-4 flex flex-col gap-2">
            {listaExibida.map((p) => (
              <button
                key={p.produtoId}
                type="button"
                onClick={() => selecionar(p)}
                className="flex items-center justify-between gap-3 rounded-md border border-bdr bg-card px-4 py-3 text-left transition-all hover:translate-x-1 hover:border-prx hover:shadow-[0_2px_8px_rgba(255,128,0,.1)]"
              >
                <span className="text-sm font-medium text-t1">{p.nomeProduto}</span>
                <span className="whitespace-nowrap text-sm font-bold text-prx">{formatarMoeda(precoMaisRecente(p))}</span>
              </button>
            ))}
          </div>
        )}

        {selecionado && <HistoricoPrecoDetalhe produto={selecionado} />}
      </main>
    </>
  );
}

export default function HistoricoPrecosPage() {
  return (
    <AuthGuard>
      <HistoricoPrecosContent />
    </AuthGuard>
  );
}
