"use client";

import { use, useState } from "react";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatCard from "@/components/StatCard";
import TabPill from "@/components/TabPill";
import { comparativo, listarFornecedores } from "@/lib/api";
import { useAsync } from "@/hooks/useAsync";
import { todosFornecedores, totalEconomia, totalRecomendado } from "@/lib/comparativo";
import { formatarMoeda } from "@/lib/format";
import TabelaComparativa from "./components/TabelaComparativa";
import PorProduto from "./components/PorProduto";
import Fornecedores from "./components/Fornecedores";

type Aba = "tabela" | "produtos" | "fornecedores";

function ComparativoContent({ cotacaoId }: { cotacaoId: string }) {
  const [aba, setAba] = useState<Aba>("tabela");
  const { data: itens, setData: setItens, erro } = useAsync(
    () => comparativo(cotacaoId),
    [cotacaoId],
    "Não foi possível carregar o comparativo.",
  );
  const { data: fornecedores } = useAsync(() => listarFornecedores(), [], "Não foi possível carregar os fornecedores.");

  async function recarregarComparativo() {
    setItens(await comparativo(cotacaoId));
  }

  const itensComOferta = itens?.filter((i) => i.precosPorFornecedor.length > 0) ?? [];

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
        <h1 className="text-2xl font-semibold tracking-tight text-t1">Comparativo</h1>

        {itens !== null && itensComOferta.length > 0 && (
          <div className="mt-4 grid grid-cols-2 gap-3.5 sm:grid-cols-4">
            <StatCard
              destaque
              label="Economia Gerada"
              value={formatarMoeda(totalEconomia(itens))}
              hint="Cotação atual"
            />
            <StatCard label="Total Cotado" value={formatarMoeda(totalRecomendado(itens))} hint="Valor analisado" />
            <StatCard
              label="Fornecedores"
              value={todosFornecedores(itens).size}
              tom="prx"
              hint="Participando da cotação"
            />
            <StatCard label="Itens Comparados" value={itensComOferta.length} tom="prx" hint="Produtos na análise" />
          </div>
        )}

        <div className="mt-4 flex gap-2">
          <TabPill active={aba === "tabela"} onClick={() => setAba("tabela")}>
            Tabela Comparativa
          </TabPill>
          <TabPill active={aba === "produtos"} onClick={() => setAba("produtos")}>
            Por Produto
          </TabPill>
          <TabPill active={aba === "fornecedores"} onClick={() => setAba("fornecedores")}>
            Fornecedores
          </TabPill>
        </div>

        {erro && <p className="mt-4 text-sm text-er">{erro}</p>}
        {itens === null && !erro && <p className="mt-4 text-sm text-t2">Carregando...</p>}

        {itens !== null && itens.every((i) => i.precosPorFornecedor.length === 0) && (
          <div className="mt-6 rounded-lg border border-dashed border-bdr p-10 text-center">
            <svg
              width="44"
              height="44"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={1.5}
              className="mx-auto text-t3"
            >
              <path d="M9 3h6a2 2 0 012 2v1H7V5a2 2 0 012-2z" />
              <rect x="5" y="6" width="14" height="15" rx="2" />
              <path d="M9 12h6M9 16h4" />
            </svg>
            <p className="mt-3 font-medium text-t1">Aguardando cotação</p>
            <p className="mt-1 text-sm text-t2">
              Nenhum fornecedor confirmou uma resposta ainda. Processe e confirme a Conferência de pelo menos um
              fornecedor na Entrada de Dados para ver o comparativo aqui.
            </p>
          </div>
        )}

        {itens !== null && itens.some((i) => i.precosPorFornecedor.length > 0) && (
          <div className="mt-6">
            {aba === "tabela" && (
              <TabelaComparativa itens={itens} cotacaoId={cotacaoId} onItemEditado={recarregarComparativo} />
            )}
            {aba === "produtos" && <PorProduto itens={itens} />}
            {aba === "fornecedores" && <Fornecedores itens={itens} fornecedores={fornecedores ?? []} />}
          </div>
        )}
      </main>
    </>
  );
}

export default function ComparativoPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <AuthGuard>
      <ComparativoContent cotacaoId={id} />
    </AuthGuard>
  );
}
