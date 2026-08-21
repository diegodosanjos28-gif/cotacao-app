"use client";

import { useMemo, useState } from "react";
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
// escurecido (não os tokens wa/er puros) para contraste em fundo claro.
const STATUS_BADGE: Record<string, string> = {
  OK: "bg-ok-d text-ok",
  ATENCAO: "bg-wa/10 text-wa-txt",
  REVISAR: "bg-er/8 text-er-txt",
};

const STATUS_LABEL: Record<string, string> = { OK: "OK", ATENCAO: "Atenção", REVISAR: "Revisar" };

const BTN_ACAO =
  "rounded-md border border-bdr bg-card px-2.5 py-1 text-xs font-medium text-t1 transition-colors hover:border-prx hover:text-prx";
const BTN_ACAO_PRIMARIA =
  "rounded-md border border-prx bg-prx px-2.5 py-1 text-xs font-semibold text-white transition-colors hover:border-prx-l hover:bg-prx-l";
const BTN_ACAO_RECUSAR =
  "rounded-md border border-er/35 bg-card px-2.5 py-1 text-xs font-medium text-er transition-colors hover:border-er hover:bg-er-d";

interface BaseEdicaoManual {
  textoOriginal: string;
  precoInformado: number | null;
}

function precoParaInput(preco: number | null | undefined): string {
  return preco != null ? String(preco) : "";
}

function precoDigitado(texto: string): number | null {
  const preco = Number(texto.replace(",", "."));
  return Number.isFinite(preco) && preco > 0 ? preco : null;
}

const EXCLUIDOS_VAZIO: ReadonlySet<string> = new Set();

// Item extra (itemBaseId null) não tem chave estável na lista base — usa o texto
// original do candidato, mesmo casamento que o backend usa (textoOriginalExtra).
function chaveResolucao(item: ItemConferenciaResponse): string | null {
  if (item.itemBaseId) return item.itemBaseId;
  const texto = item.candidatos[0]?.textoOriginal;
  return texto ? `extra:${texto}` : null;
}

function statusEfetivoDoItem(item: ItemConferenciaResponse, resolvido: boolean): string {
  return item.preservado ? item.status : resolvido ? "RESOLVIDO" : item.status;
}

// Espelha .sr-row-attention/.sr-row-review do protótipo: fundo sutil + borda esquerda
// de 3px na cor do status.
function corLinhaDoStatus(statusEfetivo: string): string {
  return statusEfetivo === "REVISAR"
    ? "border-l-[3px] border-l-er bg-er/5"
    : statusEfetivo === "ATENCAO"
      ? "border-l-[3px] border-l-wa bg-wa/6"
      : "";
}

type Filtro = "all" | "revisar" | "atencao" | "ok";

interface Contadores {
  total: number;
  ok: number;
  atencao: number;
  revisar: number;
}

// Anel de progresso segmentado (Prompt 26) — conic-gradient com as 3 cores de
// severidade já usadas em toda a Conferência (--color-ok/--color-wa/--color-er,
// globals.css). Precisa de style inline porque os percentuais são dinâmicos —
// Tailwind não tem como gerar essa classe em build time.
function AnelProgresso({ contadores }: { contadores: Contadores }) {
  const total = contadores.total || 1;
  const okPct = (contadores.ok / total) * 100;
  const atencaoPct = (contadores.atencao / total) * 100;
  const pctConferido = Math.round((contadores.ok / total) * 100);
  const gradiente = `conic-gradient(var(--color-ok) 0% ${okPct}%, var(--color-wa) ${okPct}% ${okPct + atencaoPct}%, var(--color-er) ${okPct + atencaoPct}% 100%)`;
  return (
    <div className="relative h-20 w-20 shrink-0 rounded-full" style={{ background: gradiente }}>
      <div className="absolute inset-[6px] flex flex-col items-center justify-center rounded-full bg-card text-center">
        <span className="text-base font-bold text-t1">{pctConferido}%</span>
        <span className="text-[9px] uppercase tracking-wide text-t3">conferido</span>
      </div>
    </div>
  );
}

function Chip({
  ativo,
  onClick,
  children,
}: {
  ativo: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full border px-3 py-1.5 text-xs font-medium transition-colors ${
        ativo ? "border-prx bg-prx/10 text-prx" : "border-bdr text-t2 hover:bg-hov"
      }`}
    >
      {children}
    </button>
  );
}

interface Props {
  // Reporta "este fornecedor acabou de ser confirmado" — quem chama (ConferenciaPanel)
  // decide navegação (avançar pro próximo pendente, ou não). Aguardável de propósito:
  // confirmar() só limpa o preview em memória DEPOIS que isto resolve, pra não deixar
  // uma janela onde cotacaoFornecedores ainda mostra PROCESSADO (desatualizado) e
  // preview já é nulo — condição que o efeito de auto-fetch de ConferenciaPanel lê
  // como "precisa reprocessar" e reenvia a mesma resposta à toa.
  onConfirmado: () => Promise<void>;
  // "Cancelar Conferência" apaga a resposta do fornecedor de verdade (backend +
  // rascunho local) — este componente só pede a confirmação e mostra erro se falhar.
  onCancelarConferencia: () => Promise<void>;
  cotacaoId: string;
  fornecedorId: string;
  fornecedorNome: string;
  textoOriginal: string;
  preview: PreviewRespostaResponse;
  // Resoluções/spinOffs/excluídos vivem no rascunho por fornecedor do componente pai
  // (entrada/page.tsx) — trocar de fornecedor no painel não pode perder o que ainda
  // não foi confirmado.
  estadoResolucao: EstadoResolucao;
  onEstadoResolucaoChange: (patch: ConferenciaPatch) => void;
}

// Painel de Conferência de um fornecedor PROCESSADO (Prompt 26) — conteúdo inline do
// passo 3 da Entrada de Dados, não mais um modal. É o corpo do antigo
// ConferenciaModal.tsx acrescido da camada visual que separa bloqueante (REVISAR) de
// aviso (ATENCAO): anel segmentado, chips de filtro, itens OK recolhidos por padrão, e
// um atalho de 1 clique pra itens de Atenção. A regra de bloqueio em si (só REVISAR
// impede confirmar) não muda — ver itensRevisarPendentes/confirmar abaixo, idêntico ao
// que já existia.
export default function ConferenciaPendente({
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
  const [filtro, setFiltro] = useState<Filtro>("all");
  const [okExpandido, setOkExpandido] = useState(false);

  const itensRevisarPendentes = useMemo(
    () =>
      preview.itens.filter(
        (item) => item.itemBaseId != null && item.status === "REVISAR" && !resolucoes[item.itemBaseId],
      ),
    [preview.itens, resolucoes],
  );

  // Contadores ao vivo: todo item resolvido pelo operador — Revisar OU Atenção — conta
  // como OK aqui (mesma semântica do protótipo).
  const contadores = useMemo<Contadores>(() => {
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

  // "Ciente, manter preço" (Prompt 26) — atalho de 1 clique pra itens ATENCAO: não é
  // um TipoResolucao novo, é a mesma resolução que ResolucaoInline já aplicaria ao
  // clicar no candidato único do item (ACEITAR_SUGESTAO/SELECIONAR_CANDIDATO).
  function cienteManterPreco(item: ItemConferenciaResponse) {
    const chave = chaveResolucao(item);
    const candidato = item.candidatos[0];
    if (!chave || !item.itemBaseId || !candidato || candidato.precoInformado == null) return;
    setResolucao(chave, {
      itemBaseId: item.itemBaseId,
      tipo: (item.candidatos.length > 1 ? "SELECIONAR_CANDIDATO" : "ACEITAR_SUGESTAO") as TipoResolucao,
      textoOriginalSelecionado: candidato.textoOriginal,
      precoInformado: candidato.precoInformado,
    });
  }

  // Agrupamento por severidade bruta (não pelo efetivo pós-resolução) — um item
  // resolvido continua na sua seção original só com o badge "Resolvido" e sem a borda
  // colorida (mesmo comportamento do protótipo: resolver marca o card, não muda de
  // grupo). Itens preservados (rodada anterior, não tocados nesta) entram no grupo OK.
  const itensRevisar = useMemo(
    () => preview.itens.filter((i) => !i.preservado && i.status === "REVISAR"),
    [preview.itens],
  );
  const itensAtencao = useMemo(
    () => preview.itens.filter((i) => !i.preservado && i.status === "ATENCAO"),
    [preview.itens],
  );
  const itensOk = useMemo(
    () => preview.itens.filter((i) => i.preservado || i.status === "OK"),
    [preview.itens],
  );

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
      // onConfirmado ANTES de limpar preview (achado do usuário, 2026-08-16): espera o
      // refetch de cotacaoFornecedores terminar e a navegação/avanço acontecer, senão o
      // efeito de auto-fetch do painel vê preview=null com status ainda desatualizado
      // como PROCESSADO e reprocessa a resposta à toa — ver comentário na prop
      // onConfirmado.
      await onConfirmado();
      onEstadoResolucaoChange({ resolucoes: {}, spinOffs: {}, excluidos: {}, preview: null });
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível confirmar a resposta."));
    } finally {
      setConfirmando(false);
    }
  }

  function ItemCard({ item }: { item: ItemConferenciaResponse }) {
    const chave = chaveResolucao(item);
    const resolucao = chave ? resolucoes[chave] : undefined;
    const isRevisar = item.status === "REVISAR";
    // Achado do frontend-ux-designer (revisão de fidelidade ao protótipo, 2026-08-16):
    // ATENCAO não pode carregar a UI pesada de resolução (lista de candidatos com
    // rádio, editar manualmente, etc.) — isso dava ao card de Atenção o mesmo peso
    // visual do card de Revisar, exatamente o que o protótipo evita. Só REVISAR usa
    // ResolucaoInline; ATENCAO usa ResolucaoAtencaoInline, que só mostra o input de
    // embalagem (quando aplicável) ou um fallback mínimo de edição manual — o "Ciente,
    // manter preço" abaixo já cobre o caso comum de item com preço.
    const podeResolver = isRevisar && item.itemBaseId != null && !item.preservado;
    const podeResolverAtencao = item.status === "ATENCAO" && item.itemBaseId != null && !item.preservado;
    const podeResolverExtra = item.itemBaseId == null && item.candidatos.length > 0;
    const resolvido = !!resolucao;
    const statusEfetivo = statusEfetivoDoItem(item, resolvido);
    const precoExibido =
      resolucao?.tipo === "SEM_OFERTA"
        ? null
        : (resolucao?.precoInformado ?? item.candidatos[0]?.precoInformado ?? null);
    const temAtualizacao =
      !item.preservado &&
      item.precoAnteriorConfirmado != null &&
      precoExibido != null &&
      Number(item.precoAnteriorConfirmado) !== Number(precoExibido);
    const podeCiente =
      item.status === "ATENCAO" &&
      !item.preservado &&
      !resolvido &&
      item.itemBaseId != null &&
      item.candidatos[0]?.precoInformado != null;

    return (
      <div className={`rounded-md border border-bdr bg-card p-3 ${corLinhaDoStatus(statusEfetivo)}`}>
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-t3">{item.nomeItemBase ?? "—"}</p>
            <p className="mt-0.5 text-t1">
              {resolucao?.textoOriginalSelecionado ?? item.candidatos[0]?.textoOriginal ?? "— (sem resposta)"}
            </p>
            {item.motivos.length > 0 && !resolvido && (
              <p className={`mt-1 text-xs ${item.status === "REVISAR" ? "text-er" : "text-wa-txt"}`}>
                {item.motivos.map((m) => MOTIVO_LABEL[m]).join(" · ")}
              </p>
            )}
          </div>
          <div className="shrink-0 text-right">
            {temAtualizacao ? (
              <div className="text-xs">
                <span className="mb-1 inline-flex items-center rounded bg-wa/10 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-wa-txt">
                  Atualização
                </span>
                <div>
                  <span className="text-t3 line-through">{formatarMoeda(item.precoAnteriorConfirmado ?? undefined)}</span>
                  {" → "}
                  <span className="font-mono">{formatarMoeda(precoExibido ?? undefined)}</span>
                </div>
              </div>
            ) : (
              <span className="font-mono text-t1">{formatarMoeda(precoExibido ?? undefined)}</span>
            )}
            <div className="mt-1">
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
            </div>
          </div>
        </div>

        {podeCiente && (
          <div className="mt-2">
            <button
              type="button"
              onClick={() => cienteManterPreco(item)}
              className="rounded-md bg-wa px-2.5 py-1 text-xs font-semibold text-white transition-colors hover:opacity-90"
            >
              ✓ Ciente, manter preço
            </button>
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
        {podeResolverAtencao && (
          <ResolucaoAtencaoInline
            item={item}
            resolucao={resolucao}
            onResolver={(r) => chave && setResolucao(chave, r)}
          />
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
      </div>
    );
  }

  const mostraRevisar = (filtro === "all" || filtro === "revisar") && itensRevisar.length > 0;
  const mostraAtencao = (filtro === "all" || filtro === "atencao") && itensAtencao.length > 0;
  const mostraOk = (filtro === "all" || filtro === "ok") && itensOk.length > 0;
  const nadaParaMostrar = !mostraRevisar && !mostraAtencao && !mostraOk;

  return (
    <div className="flex flex-col overflow-hidden rounded-lg border border-bdr bg-card">
      <div className="border-b border-bdr px-6 py-4">
        <h2 className="text-lg font-semibold text-t1">Conferência do Fornecedor — {fornecedorNome}</h2>
        <p className="mt-0.5 text-sm text-t2">Compare a resposta do fornecedor com sua lista base</p>

        <div className="mt-4 flex items-center gap-4">
          <AnelProgresso contadores={contadores} />
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
            <span className="flex items-center gap-1.5">
              <i className="inline-block h-2.5 w-2.5 rounded-full bg-ok" /> {contadores.ok} OK
            </span>
            <span className="flex items-center gap-1.5">
              <i className="inline-block h-2.5 w-2.5 rounded-full bg-wa" /> {contadores.atencao} Atenção
            </span>
            <span className="flex items-center gap-1.5">
              <i className="inline-block h-2.5 w-2.5 rounded-full bg-er" /> {contadores.revisar} Revisar
            </span>
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Chip ativo={filtro === "all"} onClick={() => setFiltro("all")}>
            Todos ({contadores.total})
          </Chip>
          <Chip ativo={filtro === "revisar"} onClick={() => setFiltro("revisar")}>
            🔴 Precisa resolver ({itensRevisar.length})
          </Chip>
          <Chip ativo={filtro === "atencao"} onClick={() => setFiltro("atencao")}>
            🟡 Atenção — não bloqueia ({itensAtencao.length})
          </Chip>
          <Chip ativo={filtro === "ok"} onClick={() => setFiltro("ok")}>
            🟢 Conferido automático ({itensOk.length})
          </Chip>
        </div>
      </div>

      {/* Sem altura/scroll próprios (achado do usuário, 2026-08-21): este painel só é
          hospedado dentro do corpo já rolável do AprovacaoModal (aba 2) desde o refactor
          2026-08-20 — um max-h+overflow-y-auto aqui aninhava um segundo scroll dentro do
          scroll do modal, dando a impressão de "2 scrolls" na Conferência das Cotações.
          Quem rola agora é só o modal. */}
      <div className="px-6 py-4">
        {erro && (
          <p role="alert" className="mb-3 rounded-md border border-er/35 bg-er-d p-3 text-sm font-medium text-er">
            {erro}
          </p>
        )}

        {preview.itens.length === 0 ? (
          <p className="py-6 text-center text-sm text-t2">
            Nenhum item da resposta casou com a lista base desta cotação.
          </p>
        ) : nadaParaMostrar ? (
          <p className="py-6 text-center text-sm text-t2">Nenhum item neste filtro.</p>
        ) : (
          <div className="space-y-5">
            {mostraRevisar && (
              <section>
                <p className="mb-2 text-xs font-semibold text-er">🔴 Bloqueiam a confirmação — resolva para continuar</p>
                <div className="space-y-2">
                  {itensRevisar.map((item, i) => (
                    <ItemCard key={chaveResolucao(item) ?? `revisar-${i}`} item={item} />
                  ))}
                </div>
              </section>
            )}

            {mostraAtencao && (
              <section>
                <p className="mb-2 text-xs font-semibold text-wa-txt">🟡 Divergência detectada — não bloqueia, mas vale revisar</p>
                <div className="space-y-2">
                  {itensAtencao.map((item, i) => (
                    <ItemCard key={chaveResolucao(item) ?? `atencao-${i}`} item={item} />
                  ))}
                </div>
              </section>
            )}

            {mostraOk && (
              <section>
                <button
                  type="button"
                  onClick={() => setOkExpandido((v) => !v)}
                  className="flex w-full items-center justify-between rounded-md border border-bdr bg-surf px-3 py-2 text-left text-sm text-t2 hover:bg-hov"
                >
                  <span>✓ {itensOk.length} item(ns) conferidos automaticamente — nenhuma ação necessária</span>
                  <span className="text-xs text-t3">{okExpandido ? "recolher ▴" : "expandir ▾"}</span>
                </button>
                {okExpandido && (
                  <div className="mt-2 space-y-2">
                    {itensOk.map((item, i) => (
                      <ItemCard key={chaveResolucao(item) ?? `ok-${i}`} item={item} />
                    ))}
                  </div>
                )}
              </section>
            )}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between border-t border-bdr px-6 py-4">
        <div className="min-w-[180px]">
          <p className={`text-xs font-medium ${itensRevisarPendentes.length > 0 ? "text-er" : "text-ok"}`}>
            {itensRevisarPendentes.length > 0
              ? `${itensRevisarPendentes.length} item(ns) em Revisar precisam de resolução.`
              : itensRevisar.length > 0
                ? "✓ Tudo resolvido — pronto para confirmar"
                : ""}
          </p>
          {/* Barra de progresso do rodapé (achado do frontend-ux-designer, protótipo
              #s-conf .foot-bar) — conta só REVISAR, mesma regra do gate real de
              confirmação (ATENCAO nunca entra aqui). */}
          {itensRevisar.length > 0 && (
            <div className="mt-1 h-1.5 w-full max-w-[220px] overflow-hidden rounded-full bg-surf">
              <div
                className={`h-full rounded-full transition-all ${itensRevisarPendentes.length === 0 ? "bg-ok" : "bg-er"}`}
                style={{
                  width: `${Math.round(((itensRevisar.length - itensRevisarPendentes.length) / itensRevisar.length) * 100)}%`,
                }}
              />
            </div>
          )}
        </div>
        <div className="flex gap-2">
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

// Resolução leve pra item ATENCAO (Prompt 26, ajuste pós-revisão de fidelidade ao
// protótipo) — deliberadamente NÃO reusa ResolucaoInline (lista de candidatos com
// rádio, editar manualmente sempre disponível etc.), que é peso visual reservado a
// REVISAR. ATENCAO sempre tem candidato único (MULTIPLE_OPTIONS escala pra REVISAR),
// então só cobre dois casos: o input de embalagem quando o motivo é
// PACKAGE_PRICE_SUSPECTED, e um fallback mínimo de edição manual só quando o candidato
// não tem preço (o botão "Ciente, manter preço", fora deste componente, já cobre o
// caso comum de aceitar o preço como está). Depois de resolvido não renderiza nada —
// o badge "Resolvido" no cabeçalho do card já comunica isso.
function ResolucaoAtencaoInline({
  item,
  resolucao,
  onResolver,
}: {
  item: ItemConferenciaResponse;
  resolucao: ResolucaoItemRequest | undefined;
  onResolver: (r: ResolucaoItemRequest) => void;
}) {
  const [editando, setEditando] = useState(false);
  const candidato = item.candidatos[0];
  const [textoManual, setTextoManual] = useState(candidato?.textoOriginal ?? item.nomeItemBase ?? "");
  const [precoManual, setPrecoManual] = useState(precoParaInput(candidato?.precoInformado));
  const precisaEmbalagem = item.motivos.includes("PACKAGE_PRICE_SUSPECTED");
  const [embalagemQtd, setEmbalagemQtd] = useState("");

  if (resolucao) return null;

  function salvarManual() {
    const preco = precoDigitado(precoManual);
    if (!textoManual.trim() || preco == null) return;
    onResolver({
      itemBaseId: item.itemBaseId as string,
      tipo: "EDITAR_MANUAL",
      textoOriginalSelecionado: textoManual.trim(),
      precoInformado: preco,
    });
  }

  if (editando) {
    return (
      <div className="mt-2 space-y-1.5 rounded-md border border-bdr bg-card p-2">
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

  if (!precisaEmbalagem && candidato?.precoInformado != null) return null;

  return (
    <div className="mt-2 flex flex-wrap items-center gap-2">
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
              if (qtd > 0 && candidato?.precoInformado != null) {
                onResolver({
                  itemBaseId: item.itemBaseId as string,
                  tipo: "ACEITAR_SUGESTAO",
                  textoOriginalSelecionado: candidato.textoOriginal,
                  precoInformado: candidato.precoInformado,
                  embalagemQtd: qtd,
                });
              }
            }}
            className="w-14 rounded-md border border-bdr px-1.5 py-0.5"
          />
        </span>
      )}
      {candidato?.precoInformado == null && (
        <button type="button" onClick={() => setEditando(true)} className={BTN_ACAO}>
          Editar manualmente
        </button>
      )}
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
  // descartado por padrão na confirmação e nunca bloqueia "Confirmar e Processar".
  // "Recusado" aqui é só feedback visual local.
  const [recusado, setRecusado] = useState(false);
  const candidato = item.candidatos[0];
  if (!candidato) return null;
  const textoOriginalExtra = candidato.textoOriginal;

  function adicionarALista() {
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
      {item.candidatos.map((c, i) => {
        const semPreco = c.precoInformado == null;
        const selecionado = resolucao?.textoOriginalSelecionado === c.textoOriginal;
        const excluido = excluidosDoItem.has(c.textoOriginal);
        const spinOff = spinOffsDoItem.find((s) => s.textoOriginalExtra === c.textoOriginal);
        const bloqueado = excluido || !!spinOff;
        const temAcao = temMultiplasOpcoes && !selecionado;
        return (
          <div key={i}>
            <label
              className={`group flex items-center gap-3 rounded-md border px-3 py-2 transition-colors ${
                bloqueado ? "cursor-not-allowed" : "cursor-pointer"
              } ${selecionado ? "border-prx bg-prx/10" : "border-bdr bg-card hover:border-prx hover:bg-prx/5"}`}
            >
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
                  onResolver({ ...resolucao, embalagemQtd: qtd > 0 ? qtd : undefined });
                } else if (qtd > 0 && item.candidatos.length === 1 && item.candidatos[0].precoInformado != null) {
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
      {item.status === "REVISAR" && !resolucao && (
        <p className="text-[10px] font-medium text-er">Obrigatório resolver para confirmar</p>
      )}
    </div>
  );
}
