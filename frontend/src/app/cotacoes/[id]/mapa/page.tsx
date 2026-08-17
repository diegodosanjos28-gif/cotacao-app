"use client";

import { use, useEffect, useRef, useState } from "react";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatCard from "@/components/StatCard";
import {
  buscarCotacao,
  comparativo,
  mapaCompra,
  mensagemFornecedor,
  removerItemMapa,
  restaurarCenarioMapa,
  restaurarItemMapa,
} from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda, formatarQuantidade } from "@/lib/format";
import { CenarioSelecionado, ItemDistribuicao, MapaCompraResponse } from "@/lib/types";
import { useAsync } from "@/hooks/useAsync";
import CenarioCard from "./components/CenarioCard";
import ConcluirOrdemModal from "./components/ConcluirOrdemModal";
import MoverProdutoModal from "./components/MoverProdutoModal";

const CENARIOS: CenarioSelecionado[] = ["MENOR_PRECO", "EQUILIBRADA", "MELHOR_PRAZO"];

const NOME_CENARIO: Record<CenarioSelecionado, string> = {
  MENOR_PRECO: "Menor Preço",
  EQUILIBRADA: "Compra Equilibrada",
  MELHOR_PRAZO: "Melhor Prazo",
};

type Mapas = Record<CenarioSelecionado, MapaCompraResponse>;

// Sem persistência entre cotações (módulo de Histórico de Preços ainda não existe —
// ver doc técnica seção 11), então a coluna Variação sempre reflete esse estado.
function BadgeVariacao() {
  return <span className="rounded bg-surf px-1.5 py-0.5 text-[11px] font-medium text-t3">Sem histórico</span>;
}

function WhatsAppIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden className="shrink-0">
      <path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347z" />
      <path d="M12.001 2C6.478 2 2 6.477 2 12c0 1.9.542 3.746 1.564 5.35L2 22l4.797-1.548A9.955 9.955 0 0012 22c5.524 0 10-4.478 10-10S17.524 2 12.001 2zm0 18.166a8.15 8.15 0 01-4.155-1.135l-.298-.177-3.084.995 1.017-3.013-.194-.311A8.164 8.164 0 013.834 12c0-4.5 3.665-8.166 8.167-8.166 4.502 0 8.166 3.666 8.166 8.166 0 4.5-3.664 8.166-8.166 8.166z" />
    </svg>
  );
}

export default function MapaPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <AuthGuard>
      <MapaContent cotacaoId={id} />
    </AuthGuard>
  );
}

function MapaContent({ cotacaoId }: { cotacaoId: string }) {
  const [cenarioAtivo, setCenarioAtivo] = useState<CenarioSelecionado>("MENOR_PRECO");
  const [fornecedoresAbertos, setFornecedoresAbertos] = useState<Set<string>>(new Set());
  const [modalAberto, setModalAberto] = useState(false);
  const [copiado, setCopiado] = useState<string | null>(null);
  const copiadoTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [acaoEmAndamento, setAcaoEmAndamento] = useState<string | null>(null);
  const [moverModalState, setMoverModalState] = useState<{
    item: ItemDistribuicao;
    fornecedorAtualId: string;
    fornecedorAtualNome: string;
  } | null>(null);

  useEffect(() => {
    return () => {
      if (copiadoTimeoutRef.current) clearTimeout(copiadoTimeoutRef.current);
    };
  }, []);

  const { data: cotacao, setData: setCotacao } = useAsync(
    () => buscarCotacao(cotacaoId),
    [cotacaoId],
    "Não foi possível carregar a cotação.",
  );

  // Cotação FINALIZADA: o backend devolve o snapshot congelado no momento da
  // finalização pra qualquer cenário pedido (ver MapaCompraService.gerar) — os 3
  // slots ficam com o mesmo conteúdo, então não precisamos de lógica de fetch
  // diferente aqui, só de não deixar o usuário trocar de cenário na UI.
  const {
    data: mapas,
    erro,
    setErro,
  } = useAsync<Mapas>(
    async () => {
      const resultados = await Promise.all(CENARIOS.map((c) => mapaCompra(cotacaoId, c)));
      return Object.fromEntries(CENARIOS.map((c, i) => [c, resultados[i]])) as Mapas;
    },
    [cotacaoId, refreshKey],
    "Não foi possível gerar o mapa de compra.",
  );

  // Ofertas por item (todas, não só a vencedora do cenário) pro modal de mover —
  // o Mapa só devolve a distribuição escolhida, o Comparativo já tem todas.
  const { data: comparativoData } = useAsync(
    () => comparativo(cotacaoId),
    [cotacaoId, refreshKey],
    "Não foi possível carregar as ofertas dos fornecedores.",
  );

  function recarregarMapas() {
    setRefreshKey((k) => k + 1);
  }

  function toggleFornecedor(fornecedorId: string) {
    setFornecedoresAbertos((prev) => {
      const next = new Set(prev);
      if (next.has(fornecedorId)) {
        next.delete(fornecedorId);
      } else {
        next.add(fornecedorId);
      }
      return next;
    });
  }

  async function removerItem(cotacaoProdutoId: string) {
    setAcaoEmAndamento(cotacaoProdutoId);
    try {
      await removerItemMapa(cotacaoId, cotacaoProdutoId);
      recarregarMapas();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível remover o item da ordem."));
    } finally {
      setAcaoEmAndamento(null);
    }
  }

  async function restaurarItem(cotacaoProdutoId: string) {
    setAcaoEmAndamento(cotacaoProdutoId);
    try {
      await restaurarItemMapa(cotacaoId, cotacaoProdutoId);
      recarregarMapas();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível restaurar o item."));
    } finally {
      setAcaoEmAndamento(null);
    }
  }

  async function restaurarCenario() {
    setAcaoEmAndamento("__cenario__");
    try {
      await restaurarCenarioMapa(cotacaoId);
      recarregarMapas();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível restaurar o cenário."));
    } finally {
      setAcaoEmAndamento(null);
    }
  }

  async function copiarPedido(fornecedorId: string, cenario: CenarioSelecionado) {
    if (typeof navigator === "undefined" || !navigator.clipboard) {
      setErro("Este navegador não permite copiar automaticamente. Copie o texto manualmente.");
      return;
    }
    try {
      const { mensagem } = await mensagemFornecedor(cotacaoId, fornecedorId, cenario);
      await navigator.clipboard.writeText(mensagem);
      setCopiado(fornecedorId);
      if (copiadoTimeoutRef.current) clearTimeout(copiadoTimeoutRef.current);
      copiadoTimeoutRef.current = setTimeout(() => setCopiado(null), 2000);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível gerar a mensagem."));
    }
  }

  const finalizada = cotacao?.status === "FINALIZADA";
  const cenarioFinal = cotacao?.cenarioSelecionado ?? "MENOR_PRECO";
  const cenarioEfetivo = finalizada ? cenarioFinal : cenarioAtivo;

  const mapaAtivo = mapas?.[cenarioEfetivo];
  const totalItensAtivos = mapaAtivo ? mapaAtivo.distribuicoes.reduce((s, d) => s + d.itens.length, 0) : 0;
  const diffVsMenorPreco = mapas ? mapas[cenarioEfetivo].totalGeral - mapas.MENOR_PRECO.totalGeral : 0;
  const fornecedoresUnicosTotal = mapas
    ? new Set(CENARIOS.flatMap((c) => mapas[c].distribuicoes.map((d) => d.fornecedorId))).size
    : 0;
  const minimosAbaixo = mapaAtivo ? mapaAtivo.distribuicoes.filter((d) => d.abaixoDoPedidoMinimo) : [];

  const tooltipAcaoDesabilitada = finalizada ? "Ordem finalizada — ajuste não disponível" : undefined;

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-5xl flex-1 px-6 pb-28 pt-8">
        <h1 className="text-2xl font-semibold tracking-tight text-t1">Mapa de Compra</h1>
        <p className="mt-1 text-sm text-t2">
          {finalizada
            ? "Distribuição congelada no momento em que a ordem de compra foi concluída."
            : "Visualize a distribuição recomendada, ajuste produtos entre fornecedores e finalize sua ordem diretamente."}
        </p>

        {finalizada && (
          <div className="mt-4 rounded-lg border border-ok/30 bg-ok-d p-4 text-sm">
            <p className="font-semibold text-ok">✓ Ordem de compra finalizada</p>
          </div>
        )}

        {erro && <p className="mt-4 text-sm text-er">{erro}</p>}
        {!mapas && !erro && <p className="mt-6 text-sm text-t2">Calculando...</p>}

        {mapas && mapas[cenarioEfetivo].distribuicoes.length === 0 && (
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
            {mapas[cenarioEfetivo].fornecedoresComDadosPendentes.length > 0 ? (
              <>
                <p className="mt-3 font-medium text-wa">⚠ Fornecedores com cadastro incompleto</p>
                <p className="mt-1 text-sm text-t2">
                  {mapas[cenarioEfetivo].fornecedoresComDadosPendentes.map((f) => f.fornecedorNome).join(", ")} já
                  respondeu(ram), mas o cadastro comercial está incompleto — este cenário não inclui fornecedores
                  sem prazo de entrega, condição de pagamento e pedido mínimo confirmados. Complete esses dados em
                  &quot;Meus Fornecedores&quot; para incluí-los aqui, ou veja o preço deles em Menor Preço/Melhor
                  Prazo.
                </p>
              </>
            ) : (
              <>
                <p className="mt-3 font-medium text-t1">Aguardando cotação</p>
                <p className="mt-1 text-sm text-t2">
                  Nenhum fornecedor confirmou uma resposta ainda. Processe e confirme a Conferência de pelo menos um
                  fornecedor na Entrada de Dados para gerar o mapa de compra.
                </p>
              </>
            )}
          </div>
        )}

        {mapas && mapas[cenarioEfetivo].distribuicoes.length > 0 && (
          <>
            {finalizada ? (
              <p className="mt-6 text-sm text-t2">
                Cenário utilizado: <strong className="text-t1">{NOME_CENARIO[cenarioFinal]}</strong>
              </p>
            ) : (
              <div className="mt-6 grid gap-4 sm:grid-cols-3">
                {CENARIOS.map((c) => (
                  <CenarioCard
                    key={c}
                    valor={c}
                    ativo={cenarioAtivo === c}
                    total={mapas[c].totalGeral}
                    totalFornecedores={mapas[c].distribuicoes.length}
                    totalProdutos={mapas[c].distribuicoes.reduce((s, d) => s + d.itens.length, 0)}
                    abaixoMinimoCount={mapas[c].distribuicoes.filter((d) => d.abaixoDoPedidoMinimo).length}
                    diffVsMenorPreco={mapas[c].totalGeral - mapas.MENOR_PRECO.totalGeral}
                    fornecedoresReduzidos={mapas.MENOR_PRECO.distribuicoes.length - mapas[c].distribuicoes.length}
                    onClick={() => setCenarioAtivo(c)}
                  />
                ))}
              </div>
            )}

            {mapaAtivo && mapaAtivo.distribuicoes.length > 0 && (
              <>
                <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
                  <StatCard
                    label="Total Recomendado"
                    value={formatarMoeda(mapaAtivo.totalGeral)}
                    tom="prx"
                    hint="compra otimizada"
                  />
                  <StatCard
                    label="Economia Total"
                    value={formatarMoeda(mapaAtivo.economiaComparadaAoPior)}
                    tom="ok"
                    hint="vs. compra não otimizada"
                  />
                  <StatCard
                    label="Fornecedores Ativos"
                    value={mapaAtivo.distribuicoes.length}
                    hint={`de ${fornecedoresUnicosTotal} disponíveis`}
                  />
                  <StatCard
                    label="Mínimos Abaixo"
                    value={minimosAbaixo.length}
                    tom={minimosAbaixo.length > 0 ? "wa" : "ok"}
                    hint={minimosAbaixo.length > 0 ? minimosAbaixo.map((d) => d.fornecedorNome).join(", ") : "tudo ok!"}
                  />
                </div>

                {mapaAtivo.fornecedoresComDadosPendentes.length > 0 && (
                  <div className="mt-4 flex items-start gap-2 rounded-md border border-wa/30 bg-wa-d px-4 py-3 text-sm text-t2">
                    <span className="text-wa" aria-hidden>
                      ⚠
                    </span>
                    <span>
                      <strong className="text-wa">
                        {mapaAtivo.fornecedoresComDadosPendentes.length} fornecedor(es) com cadastro incompleto
                      </strong>{" "}
                      não {mapaAtivo.fornecedoresComDadosPendentes.length === 1 ? "entra" : "entram"} na Compra
                      Equilibrada ({mapaAtivo.fornecedoresComDadosPendentes.map((f) => f.fornecedorNome).join(", ")}) —
                      complete prazo de entrega, condição de pagamento e pedido mínimo em &quot;Meus
                      Fornecedores&quot; para incluí-los.
                    </span>
                  </div>
                )}

                <div className="mt-6 space-y-6">
                  {mapaAtivo.distribuicoes.map((d) => {
                    const aberto = fornecedoresAbertos.has(d.fornecedorId);
                    return (
                    <div key={d.fornecedorId} className="rounded-lg border border-bdr p-6">
                      <div
                        role="button"
                        tabIndex={0}
                        aria-expanded={aberto}
                        aria-label={aberto ? "Recolher fornecedor" : "Expandir fornecedor"}
                        onClick={() => toggleFornecedor(d.fornecedorId)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            toggleFornecedor(d.fornecedorId);
                          }
                        }}
                        className="flex cursor-pointer items-start justify-between gap-3"
                      >
                        <div>
                          <div className="flex items-center gap-2">
                            <h2 className="font-semibold text-t1">{d.fornecedorNome}</h2>
                            <span
                              className={`rounded px-1.5 py-0.5 text-[11px] font-semibold ${
                                d.abaixoDoPedidoMinimo ? "bg-wa-d text-wa" : "bg-ok-d text-ok"
                              }`}
                            >
                              {d.abaixoDoPedidoMinimo ? "⚠ Abaixo do Mínimo" : "✓ Mínimo OK"}
                            </span>
                            {d.dadosPendentes && (
                              <span
                                className="rounded bg-wa-d px-1.5 py-0.5 text-[11px] font-semibold text-wa"
                                title="Cadastro comercial incompleto — prazo/pedido mínimo podem estar ausentes ou desatualizados"
                              >
                                ⚠ Cadastro incompleto
                              </span>
                            )}
                          </div>
                          {d.abaixoDoPedidoMinimo && (
                            <p className="mt-1 text-xs text-wa">
                              Faltam {formatarMoeda(d.faltaParaMinimo)} para o pedido mínimo de{" "}
                              {formatarMoeda(d.pedidoMinimo)}
                            </p>
                          )}
                        </div>
                        <div className="flex items-center gap-3">
                          <span className="font-semibold text-t1">{formatarMoeda(d.totalFornecedor)}</span>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              copiarPedido(d.fornecedorId, cenarioEfetivo);
                            }}
                            className="flex items-center gap-1.5 rounded-md border border-bdr px-3 py-1.5 text-xs font-medium hover:bg-hov"
                          >
                            <WhatsAppIcon />
                            {copiado === d.fornecedorId ? "Copiado!" : "Copiar pedido WhatsApp"}
                          </button>
                          <span aria-hidden className={`inline-block text-t3 transition-transform ${aberto ? "rotate-90" : ""}`}>
                            ›
                          </span>
                        </div>
                      </div>

                      {aberto && (
                        <>
                          <div className="mt-4 grid grid-cols-[1fr_110px_90px_90px_100px_70px] gap-2 border-b border-bdr pb-2 text-[11px] font-semibold uppercase tracking-wide text-t3">
                            <span>Produto</span>
                            <span className="text-center">Variação</span>
                            <span className="text-center">Qtd</span>
                            <span className="text-right">Unitário</span>
                            <span className="text-right">Total</span>
                            <span className="text-right">Ações</span>
                          </div>
                          <ul className="divide-y divide-bdr text-sm">
                            {d.itens.map((item) => (
                              <li
                                key={item.cotacaoProdutoId}
                                className="grid grid-cols-[1fr_110px_90px_90px_100px_70px] items-center gap-2 py-2"
                              >
                                <span className="flex min-w-0 items-center gap-1.5">
                                  {item.ajustadoManualmente && (
                                    <span
                                      className="shrink-0 rounded bg-wa-d px-1.5 py-0.5 text-[10px] font-bold text-wa"
                                      title="Ajustado manualmente"
                                    >
                                      ✎
                                    </span>
                                  )}
                                  <span className="truncate">{item.produtoNome}</span>
                                  {item.menorPreco && (
                                    <span className="shrink-0 rounded bg-ok-d px-1.5 py-0.5 text-[10px] font-bold text-ok">
                                      ★ Menor
                                    </span>
                                  )}
                                </span>
                                <span className="text-center">
                                  <BadgeVariacao />
                                </span>
                                <span className="text-center text-t2">
                                  {formatarQuantidade(item.quantidade)} {item.unidade}
                                </span>
                                <span className="text-right text-t2">{formatarMoeda(item.precoUnitario)}</span>
                                <span className="text-right font-medium text-t1">{formatarMoeda(item.subtotal)}</span>
                                <span className="flex justify-end gap-1">
                                  <button
                                    type="button"
                                    disabled={finalizada}
                                    onClick={() =>
                                      setMoverModalState({
                                        item,
                                        fornecedorAtualId: d.fornecedorId,
                                        fornecedorAtualNome: d.fornecedorNome,
                                      })
                                    }
                                    title={tooltipAcaoDesabilitada ?? "Mover para outro fornecedor"}
                                    className="rounded px-1.5 py-0.5 text-xs text-t2 hover:bg-hov hover:text-prx disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-t2"
                                  >
                                    ⇄
                                  </button>
                                  <button
                                    type="button"
                                    disabled={finalizada || acaoEmAndamento === item.cotacaoProdutoId}
                                    onClick={() => removerItem(item.cotacaoProdutoId)}
                                    title={tooltipAcaoDesabilitada ?? "Remover da ordem"}
                                    className="rounded px-1.5 py-0.5 text-xs text-t2 hover:bg-hov hover:text-er disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-t2"
                                  >
                                    ×
                                  </button>
                                </span>
                              </li>
                            ))}
                          </ul>

                          <p className="mt-3 text-xs text-t2">
                            Entrega: <strong className="text-t1">{d.prazoEntregaPadrao ?? "—"}</strong> · Pagamento:{" "}
                            <strong className="text-t1">{d.condicaoPagamentoPadrao ?? "—"}</strong> · Mínimo:{" "}
                            <strong className="text-t1">
                              {d.pedidoMinimo != null ? formatarMoeda(d.pedidoMinimo) : "—"}
                            </strong>
                            {d.abaixoDoPedidoMinimo && (
                              <span className="ml-1 font-semibold text-wa">
                                Concentre mais itens ou negocie exceção
                              </span>
                            )}
                          </p>
                        </>
                      )}
                    </div>
                    );
                  })}
                </div>

                {mapaAtivo.produtosSemFornecedor.length > 0 && (
                  <div className="mt-6 rounded-lg border-l-[3px] border-wa border-y border-r border-bdr p-6">
                    <div className="flex items-center gap-2">
                      <h2 className="font-semibold text-t1">Produtos Sem Cotação</h2>
                      <span className="rounded bg-wa-d px-1.5 py-0.5 text-[11px] font-semibold text-wa">
                        {mapaAtivo.produtosSemFornecedor.length} item(s)
                      </span>
                    </div>
                    <ul className="mt-3 divide-y divide-bdr text-sm">
                      {mapaAtivo.produtosSemFornecedor.map((p) => (
                        <li key={p.cotacaoProdutoId} className="flex items-center justify-between py-2">
                          <span>{p.produtoNome}</span>
                          <span className="flex items-center gap-3 text-t2">
                            <span>
                              {formatarQuantidade(p.quantidade)} {p.unidade}
                            </span>
                            <span className="font-medium text-wa">Sem cotação</span>
                          </span>
                        </li>
                      ))}
                    </ul>
                    <p className="mt-3 text-xs font-medium text-wa">
                      Ação urgente: contatar fornecedores para cotar estes itens.
                    </p>
                  </div>
                )}

                {mapaAtivo.itensRemovidosManualmente.length > 0 && (
                  <div className="mt-6 rounded-lg border border-dashed border-bdr-m bg-surf p-4">
                    <div className="flex items-center gap-2">
                      <h2 className="font-semibold text-t1">Itens removidos da ordem</h2>
                      <span className="rounded bg-card px-1.5 py-0.5 text-[11px] font-semibold text-t2">
                        {mapaAtivo.itensRemovidosManualmente.length}
                      </span>
                    </div>
                    <ul className="mt-3 divide-y divide-bdr-m text-sm">
                      {mapaAtivo.itensRemovidosManualmente.map((item) => (
                        <li key={item.cotacaoProdutoId} className="flex items-center justify-between py-2">
                          <span>
                            {item.produtoNome}{" "}
                            <span className="text-xs text-t3">
                              {formatarQuantidade(item.quantidade)} {item.unidade}
                            </span>
                          </span>
                          <button
                            type="button"
                            disabled={finalizada || acaoEmAndamento === item.cotacaoProdutoId}
                            onClick={() => restaurarItem(item.cotacaoProdutoId)}
                            title={tooltipAcaoDesabilitada}
                            className="rounded-md border border-bdr px-3 py-1 text-xs font-medium hover:bg-hov disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
                          >
                            Restaurar
                          </button>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {mapaAtivo.temAjustesManuais && (
                  <div className="mt-6 flex items-center gap-2 rounded-md border border-wa/30 bg-wa-d px-4 py-3 text-sm text-t2">
                    <span className="text-wa-txt" aria-hidden>
                      ✎
                    </span>
                    <span>
                      <strong className="text-wa-txt">Ordem ajustada manualmente</strong> —{" "}
                      {mapaAtivo.distribuicoes.reduce((s, d) => s + d.itens.filter((i) => i.ajustadoManualmente).length, 0)}{" "}
                      movimentação(ões), {mapaAtivo.itensRemovidosManualmente.length} item(ns) removido(s)
                    </span>
                  </div>
                )}
              </>
            )}
          </>
        )}
      </main>

      {!finalizada && mapaAtivo && mapaAtivo.distribuicoes.length > 0 && (
        <div className="fixed inset-x-0 bottom-0 z-30 border-t-2 border-prx bg-card/95 backdrop-blur">
          <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-6 px-6 py-3 text-sm">
            <div>
              <p className="text-[10.5px] font-semibold uppercase tracking-wide text-t3">Total da ordem</p>
              <p className="font-semibold text-t1">{formatarMoeda(mapaAtivo.totalGeral)}</p>
            </div>
            <div>
              <p className="text-[10.5px] font-semibold uppercase tracking-wide text-t3">Itens ativos</p>
              <p className="font-semibold text-t1">{totalItensAtivos}</p>
            </div>
            <div>
              <p className="text-[10.5px] font-semibold uppercase tracking-wide text-t3">Fornecedores</p>
              <p className="font-semibold text-t1">{mapaAtivo.distribuicoes.length}</p>
            </div>
            <div>
              <p className="text-[10.5px] font-semibold uppercase tracking-wide text-t3">vs Menor Preço</p>
              <p className={`font-semibold ${diffVsMenorPreco > 0 ? "text-wa" : "text-ok"}`}>
                {diffVsMenorPreco > 0 ? "+" : ""}
                {formatarMoeda(diffVsMenorPreco)}
              </p>
            </div>
            <div className="ml-auto flex items-center gap-2">
              {mapaAtivo.temAjustesManuais && (
                <button
                  onClick={restaurarCenario}
                  disabled={acaoEmAndamento === "__cenario__"}
                  className="rounded-md border border-bdr px-4 py-2.5 text-sm font-medium hover:bg-hov disabled:opacity-50"
                >
                  Restaurar Cenário
                </button>
              )}
              <button
                onClick={() => setModalAberto(true)}
                className="rounded-md bg-ok px-5 py-2.5 font-medium text-white hover:opacity-90"
              >
                Concluir Ordem de Compra
              </button>
            </div>
          </div>
        </div>
      )}

      {!finalizada && mapaAtivo && mapaAtivo.distribuicoes.length > 0 && (
        <ConcluirOrdemModal
          open={modalAberto}
          onClose={() => setModalAberto(false)}
          cotacaoId={cotacaoId}
          mapa={mapaAtivo}
          onFinalizada={setCotacao}
        />
      )}

      {!finalizada && moverModalState && (
        <MoverProdutoModal
          open={moverModalState !== null}
          onClose={() => setMoverModalState(null)}
          cotacaoId={cotacaoId}
          item={moverModalState.item}
          fornecedorAtualId={moverModalState.fornecedorAtualId}
          fornecedorAtualNome={moverModalState.fornecedorAtualNome}
          ofertas={
            comparativoData?.find((c) => c.cotacaoProdutoId === moverModalState.item.cotacaoProdutoId)
              ?.precosPorFornecedor ?? []
          }
          onMovido={recarregarMapas}
        />
      )}
    </>
  );
}
