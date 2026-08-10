"use client";

import { useMemo } from "react";
import Image from "next/image";
import Link from "next/link";
import { ColumnDef, getCoreRowModel, getExpandedRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatCard from "@/components/StatCard";
import StatusBadge from "@/components/StatusBadge";
import { comparativo, listarCotacoes } from "@/lib/api";
import {
  fornecedorMaisCompetitivo,
  itensSemCotacao,
  percentualEconomia,
  todosFornecedores,
  totalEconomia,
  totalRecomendado,
} from "@/lib/comparativo";
import { formatarData, formatarMoeda, formatarPercentual } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";
import { ComparativoItemResponse, Cotacao } from "@/lib/types";
import CotacaoResumoExpandido from "./components/CotacaoResumoExpandido";
import EconomiaCotacaoDetalhe from "./components/EconomiaCotacaoDetalhe";

const TH_ECONOMIA_ESQUERDA = "px-4 py-3 font-semibold";
const TH_ECONOMIA_CENTRO = "px-4 py-3 text-center font-semibold";
const TH_ECONOMIA_DIREITA = "px-4 py-3 text-right font-semibold";
const TH_COTACOES = "px-4 py-3 font-medium";

// Fallback estável pra `data` do tableCotacoes enquanto `cotacoes` ainda é null (mesmo
// motivo do EXCLUIDOS_VAZIO em ConferenciaModal) — um array literal novo a cada render
// vira uma referência de `data` diferente a cada render aos olhos do TanStack Table,
// que reage a isso resetando estado interno (ex.: expanded) e causa outro render, que
// cria outro array novo, ad infinitum. Combinado com getExpandedRowModel (que depende
// desse estado interno), isso trava a aba antes mesmo dos dados carregarem.
const COTACOES_VAZIO: Cotacao[] = [];

function DashboardContent() {
  const { data: cotacoes, erro } = useAsync(
    () => listarCotacoes().then((pagina) => pagina.content),
    [],
    "Não foi possível carregar as cotações.",
  );

  // Resumo/comparativo de cada cotação da lista, buscado uma vez em paralelo — alimenta
  // tanto o resumo agregado no topo quanto o detalhe expansível de cada linha, sem
  // exigir um fetch adicional ao expandir (lista de pequeno varejo, poucas cotações
  // por página — Promise.allSettled em paralelo é aceitável aqui).
  const { data: itensPorCotacao } = useAsync(async () => {
    if (!cotacoes || cotacoes.length === 0) return new Map<string, ComparativoItemResponse[]>();
    const resultados = await Promise.allSettled(cotacoes.map((c) => comparativo(c.id)));
    const mapa = new Map<string, ComparativoItemResponse[]>();
    cotacoes.forEach((c, i) => {
      const r = resultados[i];
      mapa.set(c.id, r.status === "fulfilled" ? r.value : []);
    });
    return mapa;
  }, [cotacoes], "");

  const carregando = cotacoes === null || itensPorCotacao === null;
  const emAndamento = cotacoes?.filter((c) => c.status === "EM_ANDAMENTO").length ?? 0;

  // Memoizado: `data` do tableEconomia — um array/objetos novos a cada render (mesmo
  // problema descrito em COTACOES_VAZIO acima) trava a aba num loop de render infinito
  // assim que getExpandedRowModel entra em cena (achado ao testar manualmente no
  // browser — não pego pelos testes automatizados, que não rodam render() repetido o
  // suficiente pra expor o feedback loop).
  const finalizadas = useMemo(
    () =>
      (cotacoes ?? [])
        .filter((c) => c.status === "FINALIZADA")
        .map((c) => ({ cotacao: c, itens: itensPorCotacao?.get(c.id) ?? [] }))
        .sort((a, b) => (b.cotacao.finalizadaEm ?? "").localeCompare(a.cotacao.finalizadaEm ?? "")),
    [cotacoes, itensPorCotacao],
  );

  const cotacoesProcessadas = finalizadas.length;
  const totalEconomiaAcumulada = finalizadas.reduce((soma, f) => soma + totalEconomia(f.itens), 0);
  const mediaEconomiaPct =
    cotacoesProcessadas > 0
      ? finalizadas.reduce((soma, f) => soma + percentualEconomia(f.itens), 0) / cotacoesProcessadas
      : 0;
  const melhorFornecedorGeral = fornecedorMaisCompetitivo(finalizadas.flatMap((f) => f.itens));

  const colunasEconomia = useMemo<ColumnDef<{ cotacao: Cotacao; itens: ComparativoItemResponse[] }>[]>(
    () => [
      {
        id: "data",
        header: "Data",
        cell: ({ row }) => {
          const { cotacao: c } = row.original;
          const dt = c.finalizadaEm ? new Date(c.finalizadaEm) : null;
          return (
            <>
              <Link href={`/cotacoes/${c.id}/mapa`} className="font-medium text-t1 hover:underline">
                {dt ? dt.toLocaleDateString("pt-BR") : "—"}
              </Link>
              {dt && (
                <div className="mt-0.5 text-[10.5px] text-t3">
                  {dt.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })}
                </div>
              )}
            </>
          );
        },
        meta: { headerClassName: TH_ECONOMIA_ESQUERDA, cellClassName: "px-4 py-3" },
      },
      {
        id: "itens",
        header: "Itens",
        cell: ({ row }) => {
          const { itens } = row.original;
          const semCotacao = itensSemCotacao(itens);
          return (
            <span className="rounded-full border border-bdr bg-surf px-2 py-0.5 text-xs text-t2">
              {itens.length - semCotacao.length} / {itens.length}
            </span>
          );
        },
        meta: { headerClassName: TH_ECONOMIA_CENTRO, cellClassName: "px-4 py-3 text-center" },
      },
      {
        id: "fornecedores",
        header: "Fornecedores",
        cell: ({ row }) => todosFornecedores(row.original.itens).size,
        meta: { headerClassName: TH_ECONOMIA_CENTRO, cellClassName: "px-4 py-3 text-center text-t2" },
      },
      {
        id: "totalRecomendado",
        header: "Total Recomendado",
        cell: ({ row }) => formatarMoeda(totalRecomendado(row.original.itens)),
        meta: { headerClassName: TH_ECONOMIA_DIREITA, cellClassName: "px-4 py-3 text-right font-medium text-t1" },
      },
      {
        id: "economia",
        header: "Economia Potencial",
        cell: ({ row }) => (
          <span className="font-semibold text-ok">{formatarMoeda(totalEconomia(row.original.itens))}</span>
        ),
        meta: { headerClassName: TH_ECONOMIA_DIREITA, cellClassName: "px-4 py-3 text-right" },
      },
      {
        id: "acao",
        header: "Ação",
        cell: ({ row }) => (
          <button
            onClick={() => row.toggleExpanded()}
            aria-expanded={row.getIsExpanded()}
            className="whitespace-nowrap rounded-full border border-prx/30 bg-prx/10 px-2.5 py-1 text-[11px] font-semibold text-prx hover:bg-prx hover:text-white"
          >
            Conferência de Nota
          </button>
        ),
        meta: { headerClassName: TH_ECONOMIA_CENTRO, cellClassName: "px-4 py-3 text-center" },
      },
    ],
    [],
  );

  const tableEconomia = useReactTable({
    data: finalizadas,
    columns: colunasEconomia,
    getRowId: (f) => f.cotacao.id,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
  });

  const colunasCotacoes = useMemo<ColumnDef<Cotacao>[]>(
    () => [
      {
        id: "titulo",
        header: "Título",
        cell: ({ row }) => {
          const c = row.original;
          return (
            <>
              <button
                onClick={() => row.toggleExpanded()}
                aria-expanded={row.getIsExpanded()}
                aria-label={row.getIsExpanded() ? "Recolher detalhes" : "Expandir detalhes"}
                className="mr-2 inline-flex h-5 w-5 items-center justify-center rounded text-t2 hover:bg-hov hover:text-t1"
              >
                <span className={`inline-block transition-transform ${row.getIsExpanded() ? "rotate-90" : ""}`}>›</span>
              </button>
              <Link href={`/cotacoes/${c.id}/entrada`} className="font-medium text-t1 hover:underline">
                {c.titulo}
              </Link>
            </>
          );
        },
        meta: { headerClassName: TH_COTACOES, cellClassName: "px-4 py-3" },
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        meta: { headerClassName: TH_COTACOES, cellClassName: "px-4 py-3" },
      },
      {
        id: "canal",
        header: "Canal",
        cell: ({ row }) => {
          const c = row.original;
          const precisaAjuste = c.canalOrigem === "WHATSAPP" && !c.listaRevisada;
          return (
            <>
              {c.canalOrigem}
              {precisaAjuste && (
                <div className="mt-1">
                  <StatusBadge status="AJUSTE_PENDENTE" />
                </div>
              )}
            </>
          );
        },
        meta: { headerClassName: TH_COTACOES, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "ultimaAtividade",
        header: "Última atividade",
        cell: ({ row }) => formatarData(row.original.ultimaAtividadeEm),
        meta: { headerClassName: TH_COTACOES, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "economiaPotencial",
        header: "Economia potencial",
        cell: ({ row }) => {
          const itens = itensPorCotacao?.get(row.original.id);
          const economia = itens ? totalEconomia(itens) : 0;
          return itens ? (
            <span className={economia > 0 ? "font-medium text-ok" : ""}>{formatarMoeda(economia)}</span>
          ) : (
            "…"
          );
        },
        meta: { headerClassName: TH_COTACOES, cellClassName: "px-4 py-3 text-t2" },
      },
    ],
    [itensPorCotacao],
  );

  const tableCotacoes = useReactTable({
    data: cotacoes ?? COTACOES_VAZIO,
    columns: colunasCotacoes,
    getRowId: (c) => c.id,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
  });

  const dataHoje = new Date().toLocaleDateString("pt-BR", { day: "2-digit", month: "long", year: "numeric" });

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
        {/* Hero */}
        <div className="relative overflow-hidden rounded-[20px] shadow-[0_20px_50px_rgba(13,11,10,.30),0_5px_16px_rgba(13,11,10,.18)]">
          <Image src="/dash-banner.jpg" alt="" fill priority sizes="100vw" className="object-cover object-[center_50%]" />
          <div
            className="absolute inset-0"
            style={{
              background:
                "linear-gradient(101deg,rgba(13,11,10,.95) 0%,rgba(13,11,10,.86) 36%,rgba(13,11,10,.5) 66%,rgba(13,11,10,.28) 100%)",
            }}
          />
          <div
            className="absolute inset-0"
            style={{ background: "linear-gradient(0deg,rgba(13,11,10,.62) 0%,rgba(13,11,10,0) 40%)" }}
          />

          <div className="absolute right-4 top-5 z-10 flex items-center gap-2.5 sm:right-8 sm:top-7">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-prx/40 bg-prx/20 px-3 py-1.5 text-xs font-bold text-[#FFB266] backdrop-blur-sm">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <rect x="3" y="4" width="18" height="18" rx="2" />
                <path d="M16 2v4M8 2v4M3 10h18" />
              </svg>
              {dataHoje}
            </span>
            <span className="rounded-[9px] bg-white px-3 py-2 text-base font-extrabold leading-none tracking-tight text-[#16130F] shadow-[0_5px_16px_rgba(0,0,0,.32)]">
              PR<span className="text-prx">X</span>
            </span>
          </div>

          <div className="relative z-[2] max-w-md px-7 pb-8 pt-8 sm:px-12 sm:pb-9 sm:pt-8">
            <div className="mb-3 flex items-center gap-2.5 text-[10px] font-bold uppercase tracking-[1.3px] text-white/70">
              <span className="h-0.5 w-6 shrink-0 rounded-full bg-prx" />
              PRX Compras Inteligentes · Visão geral
            </div>
            <h1 className="mb-3 text-2xl font-bold leading-tight tracking-tight text-white sm:text-[25px]">
              Visão geral <span className="text-prx">das suas compras</span>
            </h1>
            <p className="max-w-[440px] text-[13px] leading-relaxed text-white/60">
              Acompanhe a economia gerada, o histórico de cotações finalizadas e o desempenho dos fornecedores ao longo do
              tempo.
            </p>
          </div>
        </div>

        {/* KPIs sobrepostos à base do banner */}
        <div className="relative z-[3] -mt-8 grid grid-cols-2 gap-3.5 px-1 sm:grid-cols-4 sm:px-2">
          <StatCard
            destaque
            label="Economia Potencial Acumulada"
            value={formatarMoeda(totalEconomiaAcumulada)}
            tag="▲ Acumulado"
            hint={`em ${cotacoesProcessadas} cotaç${cotacoesProcessadas === 1 ? "ão" : "ões"}`}
          />
          <StatCard
            label="Cotações Processadas"
            value={cotacoesProcessadas}
            tom="prx"
            hint="total de análises registradas"
          />
          <StatCard label="Economia Média por Cotação" value={formatarPercentual(mediaEconomiaPct)} hint="média histórica" />
          <StatCard
            label="Fornecedor Mais Competitivo"
            value={melhorFornecedorGeral ? melhorFornecedorGeral.nome : "—"}
            tom="prx"
            hint={
              melhorFornecedorGeral
                ? `${melhorFornecedorGeral.contagem} ${melhorFornecedorGeral.contagem === 1 ? "item vencido" : "itens vencidos"}`
                : "sem dados suficientes"
            }
          />
        </div>

        {erro && <p className="mt-6 text-sm text-er">{erro}</p>}

        {/* Economia de Cotações */}
        <section className="mt-8 overflow-hidden rounded-xl border border-bdr bg-card">
          <div className="flex items-center justify-between border-b border-bdr px-5 py-4">
            <h2 className="text-[11.5px] font-semibold uppercase tracking-wide text-t2">
              Economia de Cotações
            </h2>
            <span className="rounded-full border border-bdr bg-surf px-2.5 py-1 text-[10.5px] font-semibold text-t2">
              {cotacoesProcessadas} cotaç{cotacoesProcessadas === 1 ? "ão" : "ões"}
            </span>
          </div>
          <div className="max-h-[420px] overflow-y-auto">
            <DataGrid
              table={tableEconomia}
              tableClassName="w-full text-sm"
              theadClassName="sticky top-0 z-10 bg-surf text-left text-[10.5px] uppercase tracking-wide text-t2"
              tbodyClassName="divide-y divide-bdr"
              rowClassName="hover:bg-hov"
              renderRowDetail={(row) => (
                <EconomiaCotacaoDetalhe cotacao={row.original.cotacao} itens={row.original.itens} />
              )}
              rowDetailClassName="bg-surf"
              rowDetailCellClassName="px-4 py-4"
              loading={carregando}
              loadingContent={
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-t2">
                    Carregando...
                  </td>
                </tr>
              }
              emptyContent={
                <tr>
                  <td colSpan={5} className="px-4 py-10 text-center text-t2">
                    Nenhuma cotação finalizada ainda. Finalize uma ordem de compra no Mapa de Compra para começar a
                    acompanhar sua economia.
                  </td>
                </tr>
              }
            />
          </div>
        </section>

        {/* Todas as cotações */}
        <section className="mt-10">
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-lg font-semibold tracking-tight text-t1">Todas as cotações</h2>
            {emAndamento > 0 && (
              <span className="text-sm text-t2">{emAndamento} em andamento</span>
            )}
          </div>

          <DataGrid
            table={tableCotacoes}
            wrapperClassName="mt-4 overflow-hidden rounded-lg border border-bdr"
            tableClassName="w-full text-sm"
            theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
            tbodyClassName="divide-y divide-bdr"
            rowClassName="hover:bg-hov"
            renderRowDetail={(row) => (
              <CotacaoResumoExpandido cotacao={row.original} itens={itensPorCotacao?.get(row.original.id)} />
            )}
            rowDetailClassName="bg-surf"
            rowDetailCellClassName="px-4 py-4"
            loading={cotacoes === null}
            loadingContent={
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-t2">
                  Carregando...
                </td>
              </tr>
            }
            emptyContent={
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-t2">
                  Nenhuma cotação ainda. Clique em qualquer aba de navegação para criar a primeira.
                </td>
              </tr>
            }
          />
        </section>
      </main>
    </>
  );
}

export default function DashboardPage() {
  return (
    <AuthGuard>
      <DashboardContent />
    </AuthGuard>
  );
}
