"use client";

import { useMemo, useRef, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { ColumnDef, getCoreRowModel, getExpandedRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import Pagination from "@/components/Pagination";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatCard from "@/components/StatCard";
import StatusBadge from "@/components/StatusBadge";
import { CanalIcon } from "@/components/icons/CanalIcons";
import { comparativoLote, economiaResumo, listarCotacoes } from "@/lib/api";
import { itensSemCotacao, totalEconomia } from "@/lib/comparativo";
import { formatarData, formatarMoeda, formatarPercentual } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { ComparativoItemResponse, Cotacao } from "@/lib/types";
import CotacaoResumoExpandido from "./components/CotacaoResumoExpandido";
import CompetenciaSelector from "./components/dashboard/CompetenciaSelector";
import { mesAtual } from "./components/dashboard/competencia";
import ConcentracaoFornecedoresPanel from "./components/dashboard/ConcentracaoFornecedoresPanel";
import EconomiaCarrossel from "./components/dashboard/EconomiaCarrossel";
import FornecedoresPainel from "./components/dashboard/FornecedoresPainel";
import VariacaoPrecoPanel from "./components/dashboard/VariacaoPrecoPanel";

const TH_COTACOES = "px-4 py-3 font-medium";

const TAMANHO_PAGINA_COTACOES = 20;

// Fallback estável pra `data` do tableCotacoes enquanto `cotacoes` ainda é null (mesmo
// motivo do EXCLUIDOS_VAZIO em ConferenciaModal) — um array literal novo a cada render
// vira uma referência de `data` diferente a cada render aos olhos do TanStack Table,
// que reage a isso resetando estado interno (ex.: expanded) e causa outro render, que
// cria outro array novo, ad infinitum. Combinado com getExpandedRowModel (que depende
// desse estado interno), isso trava a aba antes mesmo dos dados carregarem.
const COTACOES_VAZIO: Cotacao[] = [];

function DashboardContent() {
  // KPIs do topo ("Economia de Cotações") agregados no backend sobre TODAS as
  // cotações FINALIZADA do tenant (GET /cotacoes/economia-resumo) — achado do
  // usuário 08-20: antes eram calculados no frontend sobre, no máximo, as 20
  // cotações mais recentes de QUALQUER status, não "todas as finalizadas".
  const { data: resumo, erro } = useAsync(() => economiaResumo(), [], "Não foi possível carregar os KPIs de economia.");

  // Competência (mês) dos dois painéis de insight (Variação de Preço, Concentração
  // de Fornecedores) — estado único aqui, repassado como prop pros dois pra nunca
  // dessincronizar qual mês cada um mostra.
  const [mes, setMes] = useState(mesAtual());

  // "Todas as cotações" (Prompt 24): fetch próprio, sensível à busca — filtra por
  // título/canal no servidor (CotacaoRepository.buscar), independente do fetch de
  // `resumo`/`cotacoesFinalizadasPagina` acima, que alimenta só os KPIs e a grid
  // "Economia de Cotações" (sempre FINALIZADA, sem busca por texto).
  const [termoBusca, setTermoBusca] = useState("");
  const termoBuscaDebounced = useDebouncedValue(termoBusca.trim(), 300);

  // Paginação server-side real (o backend já pagina desde sempre — só o frontend
  // ignorava totalPages/number e pedia uma página maior de uma vez).
  const [pageIndexCotacoes, setPageIndexCotacoes] = useState(0);

  // Buscar de novo com o termo mudando deixaria a tabela "presa" numa página vazia se o
  // total de páginas do novo filtro for menor que a página atual (mesmo motivo do
  // reset em GridProdutosSection).
  const termoBuscaAnterior = useRef(termoBuscaDebounced);
  if (termoBuscaAnterior.current !== termoBuscaDebounced) {
    termoBuscaAnterior.current = termoBuscaDebounced;
    if (pageIndexCotacoes !== 0) setPageIndexCotacoes(0);
  }

  const { data: paginaCotacoesTabela } = useAsync(
    () => listarCotacoes({ q: termoBuscaDebounced || undefined, page: pageIndexCotacoes, size: TAMANHO_PAGINA_COTACOES }),
    [termoBuscaDebounced, pageIndexCotacoes],
    "Não foi possível carregar as cotações.",
  );
  const cotacoesTabela = paginaCotacoesTabela?.content ?? null;

  const { data: itensPorCotacaoTabela } = useAsync(async () => {
    if (!cotacoesTabela || cotacoesTabela.length === 0) return new Map<string, ComparativoItemResponse[]>();
    const porId = await comparativoLote(cotacoesTabela.map((c) => c.id));
    return new Map(cotacoesTabela.map((c) => [c.id, porId[c.id] ?? []]));
  }, [cotacoesTabela], "");

  // Lembrete de cotações aguardando conferência: contagem e lista vêm de um fetch
  // dedicado filtrado por status no servidor (não de `cotacoes`, que só cobre a 1ª
  // página) — garante que a contagem exibida seja real em qualquer escala de tenant.
  const { data: emAndamentoPagina } = useAsync(
    () => listarCotacoes({ status: "EM_ANDAMENTO", size: 50, sort: "ultimaAtividadeEm,desc" }),
    [],
    "",
  );
  const emAndamentoLista = emAndamentoPagina?.content ?? [];
  const emAndamentoTotal = emAndamentoPagina?.totalElements ?? 0;

  // KPIs vêm prontos do backend (economiaResumo) — agregados sobre TODAS as
  // finalizadas do tenant, não só uma janela paginada.
  const cotacoesProcessadas = resumo?.cotacoesProcessadas ?? 0;
  const totalEconomiaAcumulada = resumo?.economiaAcumulada ?? 0;
  const mediaEconomiaPct = resumo?.mediaEconomiaPct ?? 0;
  const melhorFornecedorGeral =
    resumo?.fornecedorMaisCompetitivoNome != null
      ? { nome: resumo.fornecedorMaisCompetitivoNome, contagem: resumo.fornecedorMaisCompetitivoContagem ?? 0 }
      : null;

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
              <span className="inline-flex items-center gap-1.5">
                <CanalIcon canal={c.canalOrigem} />
                {c.canalOrigem}
              </span>
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
        id: "progresso",
        header: "Progresso",
        cell: ({ row }) => {
          const itens = itensPorCotacaoTabela?.get(row.original.id);
          if (!itens) return "…";
          const total = itens.length;
          const conferidos = total - itensSemCotacao(itens).length;
          const pct = total > 0 ? Math.round((conferidos / total) * 100) : 0;
          const completo = total > 0 && conferidos === total;
          const corBarra = total === 0 ? "bg-bdr-m" : completo ? "bg-ok" : "bg-wa";
          const corTexto = total === 0 ? "text-t3" : completo ? "text-ok" : "text-t2";
          return (
            <div className="flex items-center gap-2">
              <div className="h-1.5 w-16 overflow-hidden rounded-full bg-surf">
                <div className={`h-full rounded-full ${corBarra}`} style={{ width: `${pct}%` }} />
              </div>
              <span className={`text-xs font-medium ${corTexto}`}>
                {conferidos}/{total}
              </span>
            </div>
          );
        },
        meta: { headerClassName: TH_COTACOES, cellClassName: "px-4 py-3" },
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
          const itens = itensPorCotacaoTabela?.get(row.original.id);
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
    [itensPorCotacaoTabela],
  );

  const tableCotacoes = useReactTable({
    data: cotacoesTabela ?? COTACOES_VAZIO,
    columns: colunasCotacoes,
    getRowId: (c) => c.id,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
  });

  const dataHoje = new Date().toLocaleDateString("pt-BR", { day: "2-digit", month: "long", year: "numeric" });

  return (
    <>
      <NavBar />
      {/* Sem max-width (full-bleed) — o mockup do Dashboard usa max-width:1400px, mas
          o usuário pediu pra preencher a largura inteira do navegador mesmo em
          monitores muito largos (feedback visual 2026-08-20, depois de já ter testado
          1400px). Só um nível de padding horizontal (px-6), sem duplicar com o de
          nenhum componente filho. */}
      <main className="w-full flex-1 px-6 py-8">
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

        {/* Linha 1 — Cotações Registradas (75%) + Fornecedores (25%). Breakpoint único
            900px (handoff §10.1) — min-[900px] em vez de lg: (1024px por padrão no
            Tailwind) pra bater exatamente com o valor especificado. */}
        <div className="mt-8 grid grid-cols-1 gap-[18px] min-[900px]:grid-cols-[75%_1fr]">
          <EconomiaCarrossel />
          <FornecedoresPainel />
        </div>

        {/* Linha 2 — Variação de Preço (50%) + Concentração de Fornecedores (50%) */}
        <CompetenciaSelector mes={mes} onChange={setMes} />
        <div className="mt-3 grid grid-cols-1 gap-[18px] min-[900px]:grid-cols-2">
          <VariacaoPrecoPanel mes={mes} />
          <ConcentracaoFornecedoresPanel mes={mes} />
        </div>

        {/* Todas as cotações */}
        <section className="mt-10">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <h2 className="text-lg font-semibold tracking-tight text-t1">Todas as cotações</h2>
            <input
              type="text"
              value={termoBusca}
              onChange={(e) => setTermoBusca(e.target.value)}
              placeholder="Buscar por título ou canal"
              className="w-full max-w-xs rounded-md border border-bdr bg-card px-3 py-1.5 text-sm text-t1 placeholder:text-t3 focus:border-prx focus:outline-none"
            />
          </div>

          {emAndamentoTotal > 0 && (
            <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-wa/30 bg-wa-d px-4 py-3">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden
                className="shrink-0 text-wa-txt"
              >
                <circle cx="12" cy="12" r="10" />
                <polyline points="12 6 12 12 16 14" />
              </svg>
              <span className="text-sm font-medium text-wa-txt">
                {emAndamentoTotal} cotaç{emAndamentoTotal === 1 ? "ão" : "ões"} aguardando conferência
              </span>
              <div className="flex flex-wrap gap-2 sm:ml-auto">
                {emAndamentoLista.map((c) => (
                  <Link
                    key={c.id}
                    href={`/cotacoes/${c.id}/entrada`}
                    className="rounded-full border border-wa/40 bg-card px-3 py-1 text-xs font-medium text-wa-txt hover:bg-hov"
                  >
                    {c.titulo}
                  </Link>
                ))}
              </div>
            </div>
          )}

          <DataGrid
            table={tableCotacoes}
            wrapperClassName="mt-4 overflow-hidden rounded-lg border border-bdr"
            tableClassName="w-full text-sm"
            theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
            tbodyClassName="divide-y divide-bdr"
            rowClassName="hover:bg-hov"
            renderRowDetail={(row) => (
              <CotacaoResumoExpandido cotacao={row.original} itens={itensPorCotacaoTabela?.get(row.original.id)} />
            )}
            rowDetailClassName="bg-surf"
            rowDetailCellClassName="px-4 py-4"
            loading={cotacoesTabela === null}
            loadingContent={
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-t2">
                  Carregando...
                </td>
              </tr>
            }
            emptyContent={
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-t2">
                  {termoBuscaDebounced
                    ? "Nenhuma cotação encontrada para essa busca."
                    : "Nenhuma cotação ainda. Clique em qualquer aba de navegação para criar a primeira."}
                </td>
              </tr>
            }
          />

          {paginaCotacoesTabela && (
            <Pagination
              className="mt-3"
              pageIndex={paginaCotacoesTabela.number}
              pageSize={paginaCotacoesTabela.size}
              total={paginaCotacoesTabela.totalElements}
              onPageChange={setPageIndexCotacoes}
            />
          )}
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
