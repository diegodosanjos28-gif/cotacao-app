"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import Modal from "@/components/Modal";
import { confirmarResposta } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda } from "@/lib/format";
import { comecaComQuantidadeEUnidade } from "@/lib/validarLista";
import {
  CandidatoResposta,
  ConferenciaPatch,
  EstadoResolucao,
  ItemConferenciaResponse,
  MotivoConferencia,
  PreviewRespostaResponse,
  ResolucaoItemRequest,
  TipoResolucao,
} from "@/lib/types";

function TrashIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6h18" />
      <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
      <path d="M10 11v6" />
      <path d="M14 11v6" />
    </svg>
  );
}

function PlusIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 5v14" />
      <path d="M5 12h14" />
    </svg>
  );
}

function UndoIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 14 4 9l5-5" />
      <path d="M20 20v-7a4 4 0 0 0-4-4H4" />
    </svg>
  );
}

const MOTIVO_LABEL: Record<MotivoConferencia, string> = {
  BRAND_CHANGED: "Marca diferente da lista base",
  WEIGHT_CHANGED: "Gramagem diferente da solicitada",
  WEIGHT_ADDED: "Fornecedor informou gramagem não especificada na lista base",
  VOLUME_ADDED: "Fornecedor informou volume não especificado na lista base",
  PACKAGE_QTY_ADDED: "Fornecedor informou quantidade por embalagem não especificada na lista base",
  PACKAGE_QTY_CHANGED: "Quantidade por embalagem diferente da lista base",
  PACKAGE_PRICE_SUSPECTED: "Possível preço de caixa/fardo",
  MULTIPLE_OPTIONS: "Fornecedor enviou múltiplas opções",
  EXTRA_ITEM:
    "Não encontrado na lista base desta cotação — verifique se é um erro de digitação ou produto fora do pedido",
  LOW_CONFIDENCE_MATCH: "Conferir correspondência do produto",
};

// Espelha .sr-badge-ok/attention/review do protótipo — fundo translúcido + texto
// escurecido (não os tokens wa/er puros) para contraste em fundo claro. A opacidade vem
// do token (bg-wa/10) e não de rgba() inline, seguindo o padrão já usado em
// TabelaComparativa.
const STATUS_BADGE: Record<string, string> = {
  OK: "bg-ok-d text-ok",
  ATENCAO: "bg-wa/10 text-wa-txt",
  REVISAR: "bg-er/8 text-er-txt",
};

const STATUS_LABEL: Record<string, string> = { OK: "OK", ATENCAO: "Atenção", REVISAR: "Revisar" };

// Botões de ação da Conferência — portes de .sr-act / .sr-act.sel / .sr-act-reject do
// protótipo. Eram links de texto soltos; viraram botões de verdade, com hierarquia
// (primária preenchida, neutra com borda, destrutiva em er).
const BTN_ACAO =
  "rounded-md border border-bdr bg-card px-2.5 py-1 text-xs font-medium text-t1 transition-colors hover:border-prx hover:text-prx";
const BTN_ACAO_PRIMARIA =
  "rounded-md border border-prx bg-prx px-2.5 py-1 text-xs font-semibold text-white transition-colors hover:border-prx-l hover:bg-prx-l";
const BTN_ACAO_RECUSAR =
  "rounded-md border border-er/35 bg-card px-2.5 py-1 text-xs font-medium text-er transition-colors hover:border-er hover:bg-er-d";

// Valores com que um formulário de edição manual abre preenchido. Um CandidatoResposta
// serve como BaseEdicaoManual, mas uma resolução já editada também.
interface BaseEdicaoManual {
  textoOriginal: string;
  precoInformado: number | null;
}

function precoParaInput(preco: number | null | undefined): string {
  return preco != null ? String(preco) : "";
}

// Preço digitado pelo operador: aceita vírgula (como ele digita) e ponto. Retorna null
// quando não é um valor utilizável — quem chama decide se bloqueia o Salvar.
function precoDigitado(texto: string): number | null {
  const preco = Number(texto.replace(",", "."));
  return Number.isFinite(preco) && preco > 0 ? preco : null;
}

// Fallback estável para excluidosDoItem quando o item não tem nenhuma exclusão ainda —
// evita recriar um Set novo (referência diferente) a cada render.
const EXCLUIDOS_VAZIO: ReadonlySet<string> = new Set();

// Item extra (itemBaseId null) não tem chave estável na lista base — usa o texto
// original do candidato, mesmo casamento que o backend usa (textoOriginalExtra).
// Fora do componente porque só depende do item: evita entrar como dependência de hook.
function chaveResolucao(item: ItemConferenciaResponse): string | null {
  if (item.itemBaseId) return item.itemBaseId;
  const texto = item.candidatos[0]?.textoOriginal;
  return texto ? `extra:${texto}` : null;
}

// Status efetivo + destaque de linha, extraído de LinhaConferencia pra ser reaproveitado
// tanto pelo `cell` da coluna Status quanto pelo `rowClassName` do DataGrid — os dois
// precisam do mesmo cálculo (resolvido vira "Resolvido"/sem destaque de cor).
function statusEfetivoDoItem(item: ItemConferenciaResponse, resolvido: boolean): string {
  return item.preservado ? item.status : resolvido ? "RESOLVIDO" : item.status;
}

// Espelha .sr-row-attention/.sr-row-review do protótipo: fundo sutil + borda esquerda de
// 3px na cor do status, não um fill translúcido de linha inteira. A opacidade vem do
// token (bg-er/5) em vez de rgba() inline.
function corLinhaDoStatus(statusEfetivo: string): string {
  return statusEfetivo === "REVISAR"
    ? "border-l-[3px] border-l-er bg-er/5"
    : statusEfetivo === "ATENCAO"
      ? "border-l-[3px] border-l-wa bg-wa/6"
      : "";
}

interface Props {
  open: boolean;
  onClose: () => void;
  // Reporta "este fornecedor acabou de ser confirmado" — o modal não decide navegação
  // (isso depende de quantos outros fornecedores da cotação ainda estão pendentes,
  // algo que só o componente pai conhece; ver Fase 4).
  onConfirmado: () => void;
  // "Cancelar Conferência" (achado do usuário, 2026-08-04): diferente de Fechar/onClose,
  // isto apaga a resposta do fornecedor de verdade (backend + rascunho local) — ver
  // onCancelarConferencia (entrada/page.tsx). O modal só pede a confirmação e mostra
  // erro se a chamada falhar; quem decide o que "cancelar" significa é o pai.
  onCancelarConferencia: () => Promise<void>;
  cotacaoId: string;
  fornecedorId: string;
  fornecedorNome: string;
  textoOriginal: string;
  preview: PreviewRespostaResponse;
  // Resoluções/spinOffs/excluídos vivem no rascunho por fornecedor do componente pai
  // (não em useState local) — trocar de fornecedor ou fechar o modal (Fechar/backdrop)
  // não pode perder o que ainda não foi confirmado (Fase 4.1).
  estadoResolucao: EstadoResolucao;
  onEstadoResolucaoChange: (patch: ConferenciaPatch) => void;
}

export default function ConferenciaModal({
  open,
  onClose,
  onConfirmado,
  onCancelarConferencia,
  cotacaoId,
  fornecedorId,
  fornecedorNome,
  textoOriginal,
  preview,
  estadoResolucao,
  onEstadoResolucaoChange,
}: Props) {
  const { resolucoes, spinOffs, excluidos } = estadoResolucao;
  const [confirmando, setConfirmando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [mostrarAvisoCancelar, setMostrarAvisoCancelar] = useState(false);
  const [cancelando, setCancelando] = useState(false);

  // O erro nasce no topo da lista de itens, que pode estar rolada 60 linhas abaixo —
  // sem isto o operador clica "Confirmar e Processar", nada visível acontece e a
  // mensagem fica fora da tela.
  const erroRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (erro) erroRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [erro]);

  const itensRevisarPendentes = useMemo(
    () =>
      preview.itens.filter(
        (item) => item.itemBaseId != null && item.status === "REVISAR" && !resolucoes[item.itemBaseId],
      ),
    [preview.itens, resolucoes],
  );

  // Contadores ao vivo: todo item resolvido pelo operador — Revisar OU Atenção — conta
  // como OK aqui, espelhando o feedback do protótipo (_srAccept/_srReject mutam ri.st
  // para 'ok' e buildSupplierReview/_srRender recalcula rv.stats) — não é o snapshot
  // estático de preview.contadores.
  const contadores = useMemo(() => {
    let ok = 0;
    let atencao = 0;
    let revisar = 0;
    for (const item of preview.itens) {
      const chave = chaveResolucao(item);
      const resolvido = chave != null && resolucoes[chave] != null;
      if (item.status === "OK" || resolvido) ok++;
      else if (item.status === "ATENCAO") atencao++;
      else revisar++;
    }
    return { total: preview.itens.length, ok, atencao, revisar };
  }, [preview.itens, resolucoes]);

  function setResolucao(chave: string, resolucao: ResolucaoItemRequest) {
    onEstadoResolucaoChange({ resolucoes: { ...resolucoes, [chave]: resolucao } });
  }

  function limparResolucao(chave: string) {
    const proximo = { ...resolucoes };
    delete proximo[chave];
    onEstadoResolucaoChange({ resolucoes: proximo });
  }

  function adicionarSpinOff(chave: string, r: ResolucaoItemRequest) {
    onEstadoResolucaoChange({ spinOffs: { ...spinOffs, [chave]: [...(spinOffs[chave] ?? []), r] } });
  }

  function removerSpinOff(chave: string, textoOriginalExtra: string) {
    onEstadoResolucaoChange({
      spinOffs: {
        ...spinOffs,
        [chave]: (spinOffs[chave] ?? []).filter((r) => r.textoOriginalExtra !== textoOriginalExtra),
      },
    });
  }

  function toggleExcluido(chave: string, textoOriginal: string) {
    const atual = new Set(excluidos[chave] ?? []);
    if (atual.has(textoOriginal)) atual.delete(textoOriginal);
    else atual.add(textoOriginal);
    onEstadoResolucaoChange({ excluidos: { ...excluidos, [chave]: atual } });
  }

  const itensBaseParaAssociar = useMemo(
    () => preview.itens.filter((i) => i.itemBaseId != null),
    [preview.itens],
  );

  const colunas = useMemo<ColumnDef<ItemConferenciaResponse>[]>(
    () => [
      {
        id: "itemBase",
        header: "Item Base",
        cell: ({ row }) => row.original.nomeItemBase ?? "—",
        // pl-3 afasta o texto da borda de status de 3px da linha — sem isto o nome do
        // item fica grudado na borda colorida.
        meta: { headerClassName: "pb-2 pl-3 pr-3", cellClassName: "py-3 pl-3 pr-3 text-t1" },
      },
      {
        id: "resposta",
        header: "Resposta do Fornecedor",
        cell: ({ row }) => {
          const item = row.original;
          const chave = chaveResolucao(item);
          const resolucao = chave ? resolucoes[chave] : undefined;
          const isRevisar = item.status === "REVISAR";
          const podeResolver = (isRevisar || item.status === "ATENCAO") && item.itemBaseId != null && !item.preservado;
          const podeResolverExtra = item.itemBaseId == null && item.candidatos.length > 0;
          const resolvido = !!resolucao;
          return (
            <>
              <div className="text-t1">
                {resolucao?.textoOriginalSelecionado ?? item.candidatos[0]?.textoOriginal ?? "— (sem resposta)"}
              </div>
              {item.motivos.length > 0 && !resolvido && (
                <div className={`mt-1 text-xs ${item.status === "REVISAR" ? "text-er" : "text-wa-txt"}`}>
                  {item.motivos.map((m) => MOTIVO_LABEL[m]).join(" · ")}
                </div>
              )}
              {podeResolver && (
                <div className="mt-2">
                  <ResolucaoInline
                    item={item}
                    resolucao={resolucao}
                    onResolver={(r) => chave && setResolucao(chave, r)}
                    onLimpar={() => chave && limparResolucao(chave)}
                    spinOffsDoItem={(chave && spinOffs[chave]) || []}
                    excluidosDoItem={(chave && excluidos[chave]) || EXCLUIDOS_VAZIO}
                    onAdicionarSpinOff={(r) => chave && adicionarSpinOff(chave, r)}
                    onRemoverSpinOff={(texto) => chave && removerSpinOff(chave, texto)}
                    onToggleExcluido={(texto) => chave && toggleExcluido(chave, texto)}
                  />
                </div>
              )}
              {podeResolverExtra && (
                <div className="mt-2">
                  <ResolucaoExtraInline
                    item={item}
                    resolucao={resolucao}
                    itensBaseParaAssociar={itensBaseParaAssociar}
                    onResolver={(r) => chave && setResolucao(chave, r)}
                    onLimpar={() => chave && limparResolucao(chave)}
                  />
                </div>
              )}
            </>
          );
        },
        meta: { headerClassName: "pb-2 pr-3", cellClassName: "py-3 pr-3" },
      },
      {
        id: "preco",
        header: "Preço",
        cell: ({ row }) => {
          const item = row.original;
          const chave = chaveResolucao(item);
          const resolucao = chave ? resolucoes[chave] : undefined;
          const precoExibido =
            resolucao?.tipo === "SEM_OFERTA"
              ? null
              : (resolucao?.precoInformado ?? item.candidatos[0]?.precoInformado ?? null);
          const temAtualizacao =
            !item.preservado &&
            item.precoAnteriorConfirmado != null &&
            precoExibido != null &&
            Number(item.precoAnteriorConfirmado) !== Number(precoExibido);
          return temAtualizacao ? (
            <div>
              <span className="mb-1 mr-1 inline-flex items-center rounded bg-wa/10 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-wa-txt">
                Atualização
              </span>
              <div>
                <span className="text-t3 line-through">{formatarMoeda(item.precoAnteriorConfirmado ?? undefined)}</span>
                {" → "}
                <span>{formatarMoeda(precoExibido ?? undefined)}</span>
              </div>
            </div>
          ) : (
            formatarMoeda(precoExibido ?? undefined)
          );
        },
        meta: { headerClassName: "pb-2 pr-3", cellClassName: "py-3 pr-3 font-mono text-t1" },
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => {
          const item = row.original;
          const chave = chaveResolucao(item);
          const resolucao = chave ? resolucoes[chave] : undefined;
          const resolvido = !!resolucao;
          const statusEfetivo = statusEfetivoDoItem(item, resolvido);
          return (
            <>
              <span
                className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-semibold ${
                  item.preservado
                    ? "bg-surf text-t3"
                    : statusEfetivo === "RESOLVIDO"
                      ? "bg-ok-d text-ok"
                      : STATUS_BADGE[item.status]
                }`}
              >
                {item.preservado ? "Confirmado" : statusEfetivo === "RESOLVIDO" ? "Resolvido" : STATUS_LABEL[item.status]}
              </span>
              {resolvido && (
                <button
                  type="button"
                  onClick={() => chave && limparResolucao(chave)}
                  className="ml-2 text-xs text-t3 underline hover:text-t2"
                >
                  desfazer
                </button>
              )}
            </>
          );
        },
        meta: { headerClassName: "pb-2", cellClassName: "py-3" },
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [resolucoes, spinOffs, excluidos, itensBaseParaAssociar],
  );

  const table = useReactTable({
    data: preview.itens,
    columns: colunas,
    getRowId: (item, index) => item.itemBaseId ?? `extra-${index}`,
    getCoreRowModel: getCoreRowModel(),
  });

  // Fechar (X / clicar fora) só esconde o modal — as resoluções em andamento deste
  // fornecedor continuam no rascunho do pai, pra reabrir exatamente como foi deixado
  // (via "Conferir resposta do fornecedor" em FornecedoresCotacoesSection). Decisão
  // explícita do usuário na Fase 4.1: aceita o mesmo comportamento que já existe hoje
  // ao reprocessar sem fechar (uma resolução antiga pode reaplicar a um item
  // recalculado). Diferente de "Cancelar Conferência" (abaixo), que descarta o
  // rascunho — Fechar é só "deixo pra depois", Cancelar é "desisto desta rodada".
  function fechar() {
    setErro(null);
    onClose();
  }

  // Cancelar Conferência (achado do usuário, 2026-08-04, refinado no mesmo dia):
  // diferente de Fechar, apaga a resposta do fornecedor de verdade — no backend
  // (DELETE .../resposta, cotacao_fornecedor volta pra PENDENTE) e no rascunho local
  // (texto colado, preview, resoluções). Não é só "descartar as decisões marcadas":
  // sem apagar no backend também, "Conferir resposta do fornecedor" reconstruiria a
  // MESMA resposta cancelada de textoPersistido no próximo clique (achado do
  // usuário) — cancelar precisa fazer essa resposta deixar de existir pro fluxo de
  // conferência, não só limpar a tela. O aviso é um modal in-app (não
  // window.confirm — achado do usuário) reaproveitando o componente genérico
  // components/Modal.tsx, mesmo já usado por FornecedorFormModal. Não chama
  // onConfirmado() — por construção, isso é o que impede o encadeamento automático
  // de FornecedoresCotacoesSection.onFornecedorConfirmado de avançar sozinho pro
  // próximo fornecedor pendente quando o operador cancela.
  function pedirCancelamento() {
    setMostrarAvisoCancelar(true);
  }

  async function confirmarCancelamento() {
    setCancelando(true);
    setErro(null);
    try {
      await onCancelarConferencia();
      setMostrarAvisoCancelar(false);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível cancelar a conferência."));
      setMostrarAvisoCancelar(false);
    } finally {
      setCancelando(false);
    }
  }

  async function confirmar() {
    setConfirmando(true);
    setErro(null);
    try {
      await confirmarResposta(cotacaoId, fornecedorId, {
        texto: textoOriginal,
        resolucoes: [...Object.values(resolucoes), ...Object.values(spinOffs).flat()],
      });
      onEstadoResolucaoChange({ resolucoes: {}, spinOffs: {}, excluidos: {}, preview: null, modalAberto: false });
      onConfirmado();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível confirmar a resposta."));
    } finally {
      setConfirmando(false);
    }
  }

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={fechar}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Conferência do Fornecedor — ${fornecedorNome}`}
        onClick={(e) => e.stopPropagation()}
        className="flex max-h-[90vh] w-full max-w-4xl flex-col overflow-hidden rounded-lg border border-bdr bg-card shadow-xl"
      >
        <div className="border-b border-bdr px-6 py-4">
          <h2 className="text-lg font-semibold text-t1">
            Conferência do Fornecedor — {fornecedorNome}
          </h2>
          <p className="mt-0.5 text-sm text-t2">Compare a resposta do fornecedor com sua lista base</p>

          {/* .sr-stat/.sr-stat-v/.sr-stat-l do protótipo: fundo surf sem borda,
              centralizado, e VALOR antes do rótulo — o número é o que o operador
              escaneia primeiro (a versão anterior invertia essa ordem). */}
          <div className="mt-4 grid grid-cols-4 gap-2">
            {[
              { rotulo: "Total", valor: contadores.total, cor: "text-t1" },
              { rotulo: "OK", valor: contadores.ok, cor: "text-ok" },
              { rotulo: "Atenção", valor: contadores.atencao, cor: "text-wa" },
              { rotulo: "Revisar", valor: contadores.revisar, cor: "text-er" },
            ].map(({ rotulo, valor, cor }) => (
              <div key={rotulo} className="rounded-md bg-surf px-2 py-3 text-center">
                <p className={`text-2xl font-bold ${cor}`}>{valor}</p>
                <p className="mt-1 text-[10px] font-semibold uppercase tracking-wide text-t3">{rotulo}</p>
              </div>
            ))}
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-4">
          {erro && (
            <p ref={erroRef} role="alert" className="mb-3 rounded-md border border-er/35 bg-er-d p-3 text-sm font-medium text-er">
              {erro}
            </p>
          )}
          <DataGrid
            table={table}
            tableClassName="w-full text-sm"
            theadRowClassName="border-b border-bdr text-left text-xs uppercase tracking-wide text-t3"
            rowClassName={(item) => {
              const chave = chaveResolucao(item);
              const resolucao = chave ? resolucoes[chave] : undefined;
              const statusEfetivo = statusEfetivoDoItem(item, !!resolucao);
              return `border-b border-bdr align-top ${corLinhaDoStatus(statusEfetivo)}`;
            }}
            emptyContent={
              <tr>
                <td colSpan={colunas.length} className="py-6 text-center text-sm text-t2">
                  Nenhum item da resposta casou com a lista base desta cotação.
                </td>
              </tr>
            }
          />
        </div>

        <div className="flex items-center justify-between border-t border-bdr px-6 py-4">
          <p className="text-xs text-t3">
            {itensRevisarPendentes.length > 0
              ? `${itensRevisarPendentes.length} item(ns) em Revisar precisam de resolução.`
              : ""}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={fechar}
              className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
            >
              Fechar
            </button>
            <button
              type="button"
              onClick={pedirCancelamento}
              className="rounded-md border border-er/40 px-4 py-2 text-sm font-medium text-er hover:bg-er-d"
            >
              Cancelar Conferência
            </button>
            <button
              type="button"
              onClick={confirmar}
              disabled={confirmando || itensRevisarPendentes.length > 0}
              className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
            >
              {confirmando ? "Confirmando..." : "Confirmar e Processar"}
            </button>
          </div>
        </div>
      </div>

      <Modal
        open={mostrarAvisoCancelar}
        onClose={() => setMostrarAvisoCancelar(false)}
        title="Cancelar conferência deste fornecedor?"
        footer={
          <>
            <button
              type="button"
              onClick={() => setMostrarAvisoCancelar(false)}
              disabled={cancelando}
              className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov disabled:opacity-50"
            >
              Voltar
            </button>
            <button
              type="button"
              onClick={confirmarCancelamento}
              disabled={cancelando}
              className="rounded-md bg-er px-4 py-2 text-sm font-medium text-white hover:bg-er-txt disabled:opacity-50"
            >
              {cancelando ? "Cancelando..." : "Cancelar conferência"}
            </button>
          </>
        }
      >
        <p className="text-sm text-t2">
          A resposta deste fornecedor será apagada — texto, preview e qualquer resolução feita nesta tela. O
          fornecedor volta a aguardar uma nova resposta.
        </p>
      </Modal>
    </div>
  );
}

function ResolucaoExtraInline({
  item,
  resolucao,
  itensBaseParaAssociar,
  onResolver,
  onLimpar,
}: {
  item: ItemConferenciaResponse;
  resolucao: ResolucaoItemRequest | undefined;
  itensBaseParaAssociar: ItemConferenciaResponse[];
  onResolver: (r: ResolucaoItemRequest) => void;
  onLimpar: () => void;
}) {
  const [associando, setAssociando] = useState(false);
  const [itemBaseEscolhido, setItemBaseEscolhido] = useState("");
  const [editando, setEditando] = useState(false);
  const [textoManual, setTextoManual] = useState("");
  const [precoManual, setPrecoManual] = useState("");
  // Recusar não tem TipoResolucao próprio no backend: um item extra sem resolução já é
  // descartado por padrão na confirmação (ConfirmacaoRespostaService só persiste
  // ADICIONAR_A_LISTA/ASSOCIAR_A_ITEM) e nunca bloqueia "Confirmar e Processar"
  // (itensRevisarPendentes só olha itens com itemBaseId). "Recusado" aqui é só feedback
  // visual local, igual ao _srReject do protótipo (que também não altera o que é
  // persistido na confirmação).
  const [recusado, setRecusado] = useState(false);
  const candidato = item.candidatos[0];
  if (!candidato) return null;
  const textoOriginalExtra = candidato.textoOriginal;

  function adicionarALista() {
    // Texto bruto do fornecedor raramente já começa com quantidade + unidade (ex.:
    // "Maionese salada 500g 4,99") — sem essa validação, o item entraria na lista
    // base sem poder ser reparseado depois (um reenvio da lista trataria a linha
    // inteira como nome do produto). Em vez de só bloquear, já abre o formulário
    // manual pré-preenchido — o operador só precisa ajustar o começo da linha.
    if (!comecaComQuantidadeEUnidade(candidato.textoOriginal)) {
      setTextoManual(candidato.textoOriginal);
      setPrecoManual(precoParaInput(candidato.precoInformado));
      setEditando(true);
      return;
    }
    setAssociando(false);
    onResolver({
      textoOriginalExtra,
      tipo: "ADICIONAR_A_LISTA",
      textoOriginalSelecionado: candidato.textoOriginal,
      precoInformado: candidato.precoInformado,
    });
  }

  function associar() {
    if (!itemBaseEscolhido) return;
    onResolver({
      textoOriginalExtra,
      tipo: "ASSOCIAR_A_ITEM",
      associarAItemBaseId: itemBaseEscolhido,
      textoOriginalSelecionado: candidato.textoOriginal,
      precoInformado: candidato.precoInformado,
    });
    setAssociando(false);
  }

  // "Editar manualmente" não tem destino próprio (sem item base pra vincular) — reaproveita
  // ADICIONAR_A_LISTA, só que com a descrição/preço que o operador digitou em vez dos
  // valores brutos da resposta do fornecedor.
  function salvarEdicaoManual() {
    const preco = precoDigitado(precoManual);
    if (!textoManual.trim() || preco == null || !comecaComQuantidadeEUnidade(textoManual)) return;
    onResolver({
      textoOriginalExtra,
      tipo: "ADICIONAR_A_LISTA",
      textoOriginalSelecionado: textoManual.trim(),
      precoInformado: preco,
    });
    setEditando(false);
  }

  function recusar() {
    setRecusado(true);
  }

  if (recusado) {
    return (
      <p className="text-xs italic text-t3">
        Item recusado — não entrará na cotação.{" "}
        <button type="button" onClick={() => setRecusado(false)} className="underline">
          desfazer
        </button>
      </p>
    );
  }

  if (resolucao?.tipo === "ADICIONAR_A_LISTA") {
    return (
      <p className="text-xs italic text-t3">
        Será adicionado à lista como novo produto.{" "}
        <button type="button" onClick={onLimpar} className="underline">
          desfazer
        </button>
      </p>
    );
  }
  if (resolucao?.tipo === "ASSOCIAR_A_ITEM") {
    const nomeAlvo = itensBaseParaAssociar.find((i) => i.itemBaseId === resolucao.associarAItemBaseId)?.nomeItemBase;
    return (
      <p className="text-xs italic text-t3">
        Associado a &quot;{nomeAlvo ?? resolucao.associarAItemBaseId}&quot;.{" "}
        <button type="button" onClick={onLimpar} className="underline">
          desfazer
        </button>
      </p>
    );
  }

  if (associando) {
    return (
      <div className="flex items-center gap-1.5">
        <select
          value={itemBaseEscolhido}
          onChange={(e) => setItemBaseEscolhido(e.target.value)}
          className="rounded-md border border-bdr px-2 py-1 text-xs"
        >
          <option value="">Selecionar item da lista base...</option>
          {itensBaseParaAssociar.map((i) => (
            <option key={i.itemBaseId} value={i.itemBaseId ?? ""}>
              {i.nomeItemBase}
            </option>
          ))}
        </select>
        <button type="button" onClick={associar} className="rounded-md bg-prx px-2 py-1 text-xs font-medium text-white hover:bg-prx-l">
          Associar
        </button>
        <button type="button" onClick={() => setAssociando(false)} className="rounded-md border border-bdr px-2 py-1 text-xs hover:bg-hov">
          Cancelar
        </button>
      </div>
    );
  }

  if (editando) {
    const textoValido = comecaComQuantidadeEUnidade(textoManual);
    return (
      <div className="space-y-1.5 rounded-md border border-bdr bg-card p-2">
        <input
          value={textoManual}
          onChange={(e) => setTextoManual(e.target.value)}
          placeholder="Descrição do produto"
          className="w-full rounded-md border border-bdr px-2 py-1 text-xs"
        />
        {/* Item novo na lista base precisa seguir o mesmo formato que a lista de
            produtos exige — sem isso, um reenvio da lista trataria a linha inteira
            como nome do produto (prejudica o matching). */}
        {textoManual.trim().length > 0 && !textoValido && (
          <p className="text-[10px] text-er">
            Precisa começar com quantidade + unidade (ex.: 5un, 2cx, 1fardo...).
          </p>
        )}
        <div className="flex gap-1.5">
          <input
            value={precoManual}
            onChange={(e) => setPrecoManual(e.target.value)}
            placeholder="Preço"
            className="w-24 rounded-md border border-bdr px-2 py-1 text-xs"
          />
          <button
            type="button"
            onClick={salvarEdicaoManual}
            disabled={!textoManual.trim() || precoDigitado(precoManual) == null || !textoValido}
            className="rounded-md bg-prx px-2 py-1 text-xs font-medium text-white hover:bg-prx-l disabled:opacity-50"
          >
            Salvar
          </button>
          <button type="button" onClick={() => setEditando(false)} className="rounded-md border border-bdr px-2 py-1 text-xs hover:bg-hov">
            Cancelar
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <button type="button" onClick={adicionarALista} className={BTN_ACAO_PRIMARIA}>
        + Adicionar à lista
      </button>
      <button type="button" onClick={() => setAssociando(true)} className={BTN_ACAO}>
        Associar a outro item
      </button>
      <button
        type="button"
        onClick={() => {
          setTextoManual(candidato.textoOriginal);
          setPrecoManual(precoParaInput(candidato.precoInformado));
          setEditando(true);
        }}
        className={BTN_ACAO}
      >
        Editar manualmente
      </button>
      <button type="button" onClick={recusar} className={BTN_ACAO_RECUSAR}>
        Recusar
      </button>
    </div>
  );
}

function ResolucaoInline({
  item,
  resolucao,
  onResolver,
  onLimpar,
  spinOffsDoItem,
  excluidosDoItem,
  onAdicionarSpinOff,
  onRemoverSpinOff,
  onToggleExcluido,
}: {
  item: ItemConferenciaResponse;
  resolucao: ResolucaoItemRequest | undefined;
  onResolver: (r: ResolucaoItemRequest) => void;
  onLimpar: () => void;
  spinOffsDoItem: ResolucaoItemRequest[];
  excluidosDoItem: ReadonlySet<string>;
  onAdicionarSpinOff: (r: ResolucaoItemRequest) => void;
  onRemoverSpinOff: (textoOriginalExtra: string) => void;
  onToggleExcluido: (textoOriginal: string) => void;
}) {
  // Base de preenchimento do formulário manual: a edição manual já feita (se houver),
  // senão a opção atualmente selecionada, senão a primeira opção do fornecedor. O
  // formulário nunca abre vazio — o operador está corrigindo/completando uma resposta
  // que já existe, não digitando do zero (mesmo comportamento de ResolucaoExtraInline
  // acima e do protótipo).
  const baseEdicao: BaseEdicaoManual =
    resolucao?.tipo === "EDITAR_MANUAL"
      ? { textoOriginal: resolucao.textoOriginalSelecionado ?? "", precoInformado: resolucao.precoInformado ?? null }
      : (item.candidatos.find((c) => c.textoOriginal === resolucao?.textoOriginalSelecionado) ??
        item.candidatos[0] ?? { textoOriginal: item.nomeItemBase ?? "", precoInformado: null });

  const [editando, setEditando] = useState(false);
  const [textoManual, setTextoManual] = useState(baseEdicao.textoOriginal);
  const [precoManual, setPrecoManual] = useState(precoParaInput(baseEdicao.precoInformado));
  const precisaEmbalagem = item.motivos.includes("PACKAGE_PRICE_SUSPECTED");
  const [embalagemQtd, setEmbalagemQtd] = useState(resolucao?.embalagemQtd != null ? String(resolucao.embalagemQtd) : "");

  // MULTIPLE_OPTIONS (marcas diferentes do mesmo item): opção NÃO selecionada ganha
  // ação explícita — Excluir (a opção já seria descartada por padrão; isto só torna a
  // decisão visível) ou Adicionar como novo item na lista base (mesmo destino de
  // "Adicionar à lista" de um item extra, ver ResolucaoExtraInline, mas partindo de um
  // candidato específico dentro de item.candidatos()).
  const temMultiplasOpcoes = item.candidatos.length > 1;
  const [editandoSpinOffTexto, setEditandoSpinOffTexto] = useState<string | null>(null);
  const [textoSpinOff, setTextoSpinOff] = useState("");
  const [precoSpinOff, setPrecoSpinOff] = useState("");

  function abrirSpinOff(c: CandidatoResposta) {
    setTextoSpinOff(c.textoOriginal);
    setPrecoSpinOff(precoParaInput(c.precoInformado));
    setEditandoSpinOffTexto(c.textoOriginal);
  }

  function salvarSpinOff(c: CandidatoResposta) {
    const preco = precoDigitado(precoSpinOff);
    if (!textoSpinOff.trim() || preco == null || !comecaComQuantidadeEUnidade(textoSpinOff)) return;
    onAdicionarSpinOff({
      itemBaseId: item.itemBaseId as string,
      textoOriginalExtra: c.textoOriginal,
      tipo: "ADICIONAR_CANDIDATO_A_LISTA",
      textoOriginalSelecionado: textoSpinOff.trim(),
      precoInformado: preco,
    });
    setEditandoSpinOffTexto(null);
  }

  function abrirEdicaoManual(base: BaseEdicaoManual = baseEdicao) {
    setTextoManual(base.textoOriginal);
    setPrecoManual(precoParaInput(base.precoInformado));
    setEditando(true);
  }

  function aplicarEmbalagem(base: ResolucaoItemRequest): ResolucaoItemRequest {
    const qtd = Number(embalagemQtd);
    return qtd > 0 ? { ...base, embalagemQtd: qtd } : base;
  }

  function selecionarCandidato(c: CandidatoResposta) {
    // Opção sem preço não pode virar resolução direta (o preço iria null e o item
    // sairia da cotação sem valor), mas também não pode ser inselecionável — era o
    // beco sem saída da Conferência. Selecionar abre o formulário manual já
    // preenchido com o texto dessa opção, faltando só o preço.
    if (c.precoInformado == null) {
      abrirEdicaoManual({ textoOriginal: c.textoOriginal, precoInformado: null });
      return;
    }
    setEditando(false);
    onResolver(
      aplicarEmbalagem({
        itemBaseId: item.itemBaseId as string,
        tipo: (item.candidatos.length > 1 ? "SELECIONAR_CANDIDATO" : "ACEITAR_SUGESTAO") as TipoResolucao,
        textoOriginalSelecionado: c.textoOriginal,
        precoInformado: c.precoInformado,
      }),
    );
  }

  function salvarManual() {
    const preco = precoDigitado(precoManual);
    if (!textoManual.trim() || preco == null) return;
    onResolver(
      aplicarEmbalagem({
        itemBaseId: item.itemBaseId as string,
        tipo: "EDITAR_MANUAL",
        textoOriginalSelecionado: textoManual.trim(),
        precoInformado: preco,
      }),
    );
    setEditando(false);
  }

  function semOferta() {
    setEditando(false);
    onResolver({ itemBaseId: item.itemBaseId as string, tipo: "SEM_OFERTA" });
  }

  if (editando) {
    return (
      <div className="space-y-1.5 rounded-md border border-bdr bg-card p-2">
        <input
          value={textoManual}
          onChange={(e) => setTextoManual(e.target.value)}
          placeholder="Descrição do produto"
          className="w-full rounded-md border border-bdr px-2 py-1 text-xs"
        />
        <div className="flex gap-1.5">
          <input
            value={precoManual}
            onChange={(e) => setPrecoManual(e.target.value)}
            placeholder="Preço"
            className="w-24 rounded-md border border-bdr px-2 py-1 text-xs"
          />
          <button
            type="button"
            onClick={salvarManual}
            disabled={!textoManual.trim() || precoDigitado(precoManual) == null}
            className="rounded-md bg-prx px-2 py-1 text-xs font-medium text-white hover:bg-prx-l disabled:opacity-50"
          >
            Salvar
          </button>
          <button type="button" onClick={() => setEditando(false)} className="rounded-md border border-bdr px-2 py-1 text-xs hover:bg-hov">
            Cancelar
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      {/* Cartões selecionáveis (.sr-mopt/.sr-mopt.sel/.sr-mrad/.sr-mlbl/.sr-mprc do
          protótipo): o cartão inteiro é clicável, o selecionado ganha borda+fundo em
          prx e o preço fica alinhado à direita em mono. O radio nativo continua no
          DOM (sr-only) para não perder semântica nem navegação por teclado. */}
      {item.candidatos.map((c, i) => {
        const semPreco = c.precoInformado == null;
        // Sem o guard de tipo: quando o operador completa o preço de uma opção pelo
        // formulário manual, a resolução vira EDITAR_MANUAL mas continua sendo
        // *aquela* opção — o cartão precisa refletir isso.
        const selecionado = resolucao?.textoOriginalSelecionado === c.textoOriginal;
        const excluido = excluidosDoItem.has(c.textoOriginal);
        const spinOff = spinOffsDoItem.find((s) => s.textoOriginalExtra === c.textoOriginal);
        // Excluída ou virada item novo é uma decisão "terminal" sobre esse
        // candidato — selecioná-lo como vencedor DEPOIS disso, sem desfazer
        // primeiro, seria inconsistente (ex.: um candidato marcado "Novo item" e
        // também escolhido como a resposta deste fornecedor pro item original ao
        // mesmo tempo). O radio fica desabilitado até o operador clicar em
        // "desfazer" — só então volta a ser selecionável.
        const bloqueado = excluido || !!spinOff;
        // Ação explícita só faz sentido pra opção NÃO selecionada de um item com
        // múltiplas marcas — Excluir (descarte já é o padrão hoje, isto só deixa
        // visível) ou Adicionar como novo item na lista base.
        const temAcao = temMultiplasOpcoes && !selecionado;
        return (
          <div key={i}>
            <label
              className={`group flex items-center gap-3 rounded-md border px-3 py-2 transition-colors ${
                bloqueado ? "cursor-not-allowed" : "cursor-pointer"
              } ${selecionado ? "border-prx bg-prx/10" : "border-bdr bg-card hover:border-prx hover:bg-prx/5"}`}
            >
              {/* Tudo que representa a OPÇÃO em si (radio, texto, preço, badge de
                  estado) fica esmaecido quando bloqueada — mas não o botão de
                  desfazer ao lado: ele é a única saída desse estado e precisa
                  continuar totalmente visível, não escondido junto com o resto. */}
              <span className={`flex flex-1 items-center gap-3 ${bloqueado ? "opacity-40" : ""}`}>
                <span
                  className={`flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full border-2 ${
                    selecionado ? "border-prx" : "border-bdr-m"
                  }`}
                >
                  {selecionado && <span className="h-2 w-2 rounded-full bg-prx" />}
                </span>
                <input
                  type="radio"
                  name={`opcoes-${item.itemBaseId}`}
                  checked={selecionado}
                  disabled={bloqueado}
                  onChange={() => selecionarCandidato(c)}
                  className="sr-only"
                />
                <span className="flex-1 text-xs font-medium text-t1">{c.textoOriginal}</span>
                {excluido && <span className="shrink-0 text-[10px] italic text-t3">Excluída</span>}
                {spinOff && <span className="shrink-0 text-[10px] italic text-ok">Novo item</span>}
                {/* Opção sem preço não pode parecer desabilitada — ela é clicável de
                    propósito (abre o formulário manual pedindo só o preço). Usa o par
                    bg-wa-d/text-wa que o app já usa para "pendente de dado". */}
                {semPreco ? (
                  <span className="inline-flex shrink-0 items-center rounded bg-wa-d px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-wa">
                    Informar preço
                  </span>
                ) : (
                  <span className="shrink-0 font-mono text-xs font-semibold text-prx">
                    {formatarMoeda(c.precoInformado)}
                  </span>
                )}
              </span>
              {/* Ícones de ação — ao lado do item, não abaixo. Ficam invisíveis até o
                  hover da linha (`group-hover`) quando não há estado pra mostrar;
                  Excluir/Adicionar viram um ícone de desfazer, sempre visível, assim
                  que a ação é tomada (não dá pra "hover pra desfazer" algo que já
                  aconteceu). type="button" + o próprio comportamento nativo de label
                  (clique num controle aninhado não repassa pro radio) evitam que
                  clicar no ícone selecione a opção sem querer. */}
              {temAcao && (excluido || spinOff) && (
                <button
                  type="button"
                  title="Desfazer"
                  aria-label="Desfazer"
                  onClick={() => (excluido ? onToggleExcluido(c.textoOriginal) : onRemoverSpinOff(c.textoOriginal))}
                  className="shrink-0 rounded p-1 text-t3 transition-colors hover:bg-hov hover:text-t1"
                >
                  <UndoIcon />
                </button>
              )}
              {temAcao && !excluido && !spinOff && (
                <span className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                  <button
                    type="button"
                    title="Excluir"
                    aria-label="Excluir opção"
                    onClick={() => onToggleExcluido(c.textoOriginal)}
                    className="rounded p-1 text-t3 transition-colors hover:bg-er-d hover:text-er"
                  >
                    <TrashIcon />
                  </button>
                  <button
                    type="button"
                    title="Adicionar como novo item na lista base"
                    aria-label="Adicionar como novo item na lista base"
                    onClick={() => abrirSpinOff(c)}
                    className="rounded p-1 text-t3 transition-colors hover:bg-prx/10 hover:text-prx"
                  >
                    <PlusIcon />
                  </button>
                </span>
              )}
            </label>
            {/* Formulário de spin-off (texto/preço editáveis) só aparece expandido
                enquanto o operador está preenchendo — o gatilho é o ícone "+" na
                própria linha, não mais um botão de texto abaixo dela. */}
            {temMultiplasOpcoes && !selecionado && editandoSpinOffTexto === c.textoOriginal && (() => {
              const textoValido = comecaComQuantidadeEUnidade(textoSpinOff);
              return (
                <div className="ml-6 mt-1 space-y-1.5 rounded-md border border-bdr bg-card p-2">
                  <input
                    value={textoSpinOff}
                    onChange={(e) => setTextoSpinOff(e.target.value)}
                    placeholder="Descrição do produto"
                    className="w-full rounded-md border border-bdr px-2 py-1 text-xs"
                  />
                  {/* Item novo na lista base precisa seguir o mesmo formato que a
                      lista de produtos exige — o candidato do fornecedor raramente já
                      vem assim, então o campo abre pré-preenchido mas normalmente
                      precisa de ajuste antes de salvar. */}
                  {textoSpinOff.trim().length > 0 && !textoValido && (
                    <p className="text-[10px] text-er">
                      Precisa começar com quantidade + unidade (ex.: 5un, 2cx, 1fardo...).
                    </p>
                  )}
                  <div className="flex gap-1.5">
                    <input
                      value={precoSpinOff}
                      onChange={(e) => setPrecoSpinOff(e.target.value)}
                      placeholder="Preço"
                      className="w-24 rounded-md border border-bdr px-2 py-1 text-xs"
                    />
                    <button
                      type="button"
                      onClick={() => salvarSpinOff(c)}
                      disabled={!textoSpinOff.trim() || precoDigitado(precoSpinOff) == null || !textoValido}
                      className="rounded-md bg-prx px-2 py-1 text-xs font-medium text-white hover:bg-prx-l disabled:opacity-50"
                    >
                      Salvar
                    </button>
                    <button
                      type="button"
                      onClick={() => setEditandoSpinOffTexto(null)}
                      className="rounded-md border border-bdr px-2 py-1 text-xs hover:bg-hov"
                    >
                      Cancelar
                    </button>
                  </div>
                </div>
              );
            })()}
          </div>
        );
      })}
      <div className="flex flex-wrap items-center gap-2 pt-1">
        <button type="button" onClick={() => abrirEdicaoManual()} className={BTN_ACAO}>
          Editar manualmente
        </button>
        {/* "Sem oferta" É a ação de recusa desta linha (equivalente ao _srReject do
            protótipo para item da lista base), por isso o estilo destrutivo — não vale
            um segundo botão "Recusar" fazendo o mesmo. */}
        <button type="button" onClick={semOferta} className={BTN_ACAO_RECUSAR}>
          Sem oferta deste fornecedor
        </button>
        {precisaEmbalagem && (
          <span className="flex items-center gap-1 text-xs text-t2">
            Unid./embalagem:
            <input
              type="number"
              min={1}
              value={embalagemQtd}
              onChange={(e) => {
                const valorDigitado = e.target.value;
                setEmbalagemQtd(valorDigitado);
                const qtd = Number(valorDigitado);
                if (resolucao) {
                  // Item já resolvido (candidato aceito/editado manualmente) — só
                  // anexa a quantidade à resolução existente.
                  onResolver({ ...resolucao, embalagemQtd: qtd > 0 ? qtd : undefined });
                } else if (qtd > 0 && item.candidatos.length === 1 && item.candidatos[0].precoInformado != null) {
                  // PACKAGE_PRICE_SUSPECTED só existe pra item de candidato único
                  // (MULTIPLE_OPTIONS nunca carrega esse motivo). Informar a
                  // quantidade É a resolução aqui — não faz sentido exigir que o
                  // operador clique no único candidato antes, só pra depois digitar
                  // a mesma coisa que esse clique já teria escolhido. Preço vem do
                  // candidato recomputado no servidor, nunca fabricado pelo cliente
                  // (mesma disciplina de ACEITAR_SUGESTAO).
                  const c = item.candidatos[0];
                  onResolver({
                    itemBaseId: item.itemBaseId as string,
                    tipo: "ACEITAR_SUGESTAO",
                    textoOriginalSelecionado: c.textoOriginal,
                    precoInformado: c.precoInformado,
                    embalagemQtd: qtd,
                  });
                }
              }}
              className="w-14 rounded-md border border-bdr px-1.5 py-0.5"
            />
          </span>
        )}
      </div>
      {resolucao?.tipo === "SEM_OFERTA" && (
        <p className="text-xs italic text-t3">
          Marcado como sem oferta. <button type="button" onClick={onLimpar} className="underline">desfazer</button>
        </p>
      )}
      {/* O que separa Revisar de Atenção agora que os dois têm ação: só Revisar
          bloqueia "Confirmar e Processar". A ausência desta legenda em Atenção é o
          sinal de que ali resolver é opcional. */}
      {item.status === "REVISAR" && !resolucao && (
        <p className="text-[10px] font-medium text-er">Obrigatório resolver para confirmar</p>
      )}
    </div>
  );
}
