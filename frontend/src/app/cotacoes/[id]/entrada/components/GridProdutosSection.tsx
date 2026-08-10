"use client";

import { Dispatch, SetStateAction, useEffect, useMemo, useState } from "react";
import { ColumnDef, getCoreRowModel, getPaginationRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import Card from "@/components/Card";
import Modal from "@/components/Modal";
import { buscarLista, buscarProdutos, editarItemCotacao, removerItemCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ItemListaResponse, Produto } from "@/lib/types";
import { normTxt } from "@/lib/normalizacao";
import { classificarStatusItemGrid } from "@/lib/statusItemGrid";
import { UNIDADES } from "@/lib/unidades";
import ColarWhatsappModal from "./ColarWhatsappModal";
import NovaLinhaGridProdutos from "./NovaLinhaGridProdutos";
import ProdutoAutocomplete from "./ProdutoAutocomplete";

interface Props {
  cotacaoId: string;
  itens: ItemListaResponse[];
  produtos: Produto[];
  onListaAtualizada: (itens: ItemListaResponse[]) => void;
  onProdutosAtualizados: (produtos: Produto[]) => void;
  setErro: Dispatch<SetStateAction<string | null>>;
  cotacaoFinalizada?: boolean;
}

const UNIDADES_VALIDAS = new Set<string>(UNIDADES);

const TH_CLASSE = "whitespace-nowrap px-4 py-3 font-medium";

// Linhas por página — troca o scroll interno (que exigia o Card crescer sem limite
// pra caber todo mundo, achado do usuário: "container cresce pra sempre") por altura
// previsível independente de quantos itens a lista tem.
const TAMANHO_PAGINA = 12;

// Rascunho compartilhado por linha (Prompt 12): quantidade/unidade/produto sempre são
// enviadas juntas a editarItemCotacao mesmo quando só um campo mudou — precisa de um
// estado comum por item.id em vez de estado local isolado por célula, senão editar um
// campo antes do outro terminar de salvar perderia a edição em andamento. produtoId e
// nomeExibido usam `undefined` pra "sem rascunho" (cai no valor original do item) e
// `null` é um valor de rascunho válido (produto explicitamente limpo).
interface RascunhoLinha {
  quantidade?: string;
  unidade?: string;
  produtoId?: string | null;
  nomeExibido?: string | null;
}

// Grid unificado de Entrada de Dados (Prompt 12) — generaliza o grid que antes era
// exclusivo da tela "Ajuste de Lista" (WhatsApp) pra virar a interface PRIMÁRIA de
// entrada de produtos nos dois canais, substituindo o textarea permanente que existia
// aqui (ListaProdutosSection, removido). Paste em massa virou o modal "Colar do
// WhatsApp" (ação secundária, sempre-append).
export default function GridProdutosSection({
  cotacaoId,
  itens,
  produtos,
  onListaAtualizada,
  onProdutosAtualizados,
  setErro,
  cotacaoFinalizada = false,
}: Props) {
  const [adicionando, setAdicionando] = useState(false);
  const [modalAberto, setModalAberto] = useState(false);
  const [filtro, setFiltro] = useState("");
  // null = ordem natural (a que o backend devolve, por `ordem`); "asc" = mais urgente
  // primeiro (erro > aviso > travado > ok); "desc" = inverso.
  const [ordenacaoStatus, setOrdenacaoStatus] = useState<"asc" | "desc" | null>(null);

  const [rascunhos, setRascunhos] = useState<Record<string, RascunhoLinha>>({});
  const [salvando, setSalvando] = useState<Record<string, boolean>>({});
  const [removendo, setRemovendo] = useState<Record<string, boolean>>({});
  const [erros, setErros] = useState<Record<string, string>>({});
  const [paginacao, setPaginacao] = useState({ pageIndex: 0, pageSize: TAMANHO_PAGINA });
  const [itemParaExcluir, setItemParaExcluir] = useState<ItemListaResponse | null>(null);

  // Filtrar pode reduzir o total de páginas abaixo da página atual, deixando a
  // tabela "presa" numa página vazia — volta pra primeira sempre que o termo muda.
  useEffect(() => {
    setPaginacao((p) => ({ ...p, pageIndex: 0 }));
  }, [filtro]);

  function onClicarHeaderStatus() {
    setOrdenacaoStatus((atual) => (atual === null ? "asc" : atual === "asc" ? "desc" : null));
  }

  const produtoNomePorId = useMemo(() => new Map(produtos.map((p) => [p.id, p.nome])), [produtos]);

  // Recarrega lista E catálogo — adicionar manualmente (nomeProdutoLivre) ou colar do
  // WhatsApp pode ter criado um Produto novo no backend (resolver-ou-criar), que o
  // catálogo local (produtos, buscado uma vez no carregamento da página) ainda não
  // conhece. Sem isso, o autocomplete mostra o campo vazio pro item recém-adicionado
  // até a página inteira ser recarregada (achado do smoke test manual).
  async function recarregar() {
    try {
      const [itensAtualizados, catalogoAtualizado] = await Promise.all([
        buscarLista(cotacaoId),
        buscarProdutos(),
      ]);
      onListaAtualizada(itensAtualizados);
      onProdutosAtualizados(catalogoAtualizado);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível atualizar a lista."));
    }
  }

  function draftQuantidade(item: ItemListaResponse) {
    return rascunhos[item.id]?.quantidade ?? String(item.quantidade);
  }

  function draftUnidade(item: ItemListaResponse) {
    return rascunhos[item.id]?.unidade ?? item.unidade;
  }

  function draftProdutoId(item: ItemListaResponse) {
    const rascunho = rascunhos[item.id];
    return rascunho && "produtoId" in rascunho ? rascunho.produtoId ?? null : item.produtoIdEncontrado;
  }

  function draftNomeExibido(item: ItemListaResponse) {
    return rascunhos[item.id]?.nomeExibido ?? null;
  }

  // Cotação finalizada bloqueia toda edição de item no backend (ConflictException) —
  // desabilitar aqui evita o usuário editar e só descobrir pelo erro depois de salvar.
  function itemBloqueado(item: ItemListaResponse) {
    return item.temRespostaFornecedorConfirmada || cotacaoFinalizada;
  }

  function onCellEdit(rowId: string, campo: keyof RascunhoLinha, valor: RascunhoLinha[keyof RascunhoLinha]) {
    setRascunhos((r) => ({ ...r, [rowId]: { ...r[rowId], [campo]: valor } }));
  }

  async function commitRow(item: ItemListaResponse, overrides?: RascunhoLinha) {
    if (itemBloqueado(item)) return;
    const novaQuantidade = overrides?.quantidade ?? draftQuantidade(item);
    const novaUnidade = overrides?.unidade ?? draftUnidade(item);
    const novoProdutoId = overrides && "produtoId" in overrides ? (overrides.produtoId ?? null) : draftProdutoId(item);
    const novoNomeLivre =
      overrides && "nomeExibido" in overrides ? (overrides.nomeExibido ?? null) : draftNomeExibido(item);

    const qtdNum = Number(novaQuantidade.replace(",", "."));
    if (!qtdNum || qtdNum <= 0 || !novaUnidade.trim()) return;

    setSalvando((s) => ({ ...s, [item.id]: true }));
    setErros((e) => {
      const resto = { ...e };
      delete resto[item.id];
      return resto;
    });
    try {
      await editarItemCotacao(cotacaoId, item.id, {
        quantidade: qtdNum,
        unidade: novaUnidade.trim(),
        produtoId: novoProdutoId ?? undefined,
        nomeProdutoLivre: novoProdutoId ? undefined : (novoNomeLivre ?? undefined),
      });
      await recarregar();
    } catch (err) {
      setErros((e) => ({ ...e, [item.id]: getErrorMessage(err, "Não foi possível salvar a alteração.") }));
      setRascunhos((r) => {
        const resto = { ...r };
        delete resto[item.id];
        return resto;
      });
    } finally {
      setSalvando((s) => ({ ...s, [item.id]: false }));
    }
  }

  // Abre o modal de confirmação (substitui window.confirm — achado do usuário: alerta
  // nativo do navegador não combina com o resto da UI). A exclusão de fato só acontece
  // em confirmarExclusao, disparada pelo botão "Excluir" dentro do modal.
  function pedirConfirmacaoExclusao(item: ItemListaResponse) {
    if (cotacaoFinalizada) return;
    setItemParaExcluir(item);
  }

  async function confirmarExclusao() {
    const item = itemParaExcluir;
    if (!item) return;
    setItemParaExcluir(null);
    setRemovendo((r) => ({ ...r, [item.id]: true }));
    setErros((e) => {
      const resto = { ...e };
      delete resto[item.id];
      return resto;
    });
    try {
      await removerItemCotacao(cotacaoId, item.id);
      await recarregar();
    } catch (err) {
      setErros((e) => ({ ...e, [item.id]: getErrorMessage(err, "Não foi possível excluir o item.") }));
    } finally {
      setRemovendo((r) => ({ ...r, [item.id]: false }));
    }
  }

  const itensFiltrados = useMemo(() => {
    const termo = normTxt(filtro.trim());
    if (!termo) return itens;
    return itens.filter((item) => {
      const nomeProduto = item.produtoIdEncontrado ? (produtoNomePorId.get(item.produtoIdEncontrado) ?? "") : "";
      return (
        normTxt(nomeProduto).includes(termo) ||
        normTxt(item.unidade).includes(termo) ||
        normTxt(item.textoOriginal).includes(termo)
      );
    });
  }, [itens, filtro, produtoNomePorId]);

  const itensOrdenados = useMemo(() => {
    if (!ordenacaoStatus) return itensFiltrados;
    // rank menor = mais urgente (erro=0 ... ok=3). Sort estável do JS preserva a
    // ordem relativa (por `ordem`) entre itens do mesmo rank.
    const sinal = ordenacaoStatus === "asc" ? 1 : -1;
    return [...itensFiltrados].sort(
      (a, b) => sinal * (classificarStatusItemGrid(a).rank - classificarStatusItemGrid(b).rank),
    );
  }, [itensFiltrados, ordenacaoStatus]);

  const colunas = useMemo<ColumnDef<ItemListaResponse>[]>(
    () => [
      {
        id: "textoOriginal",
        header: "Texto Original",
        cell: ({ row }) =>
          row.original.textoOriginal || <span className="italic text-t3/70">Adicionado manualmente</span>,
        meta: {
          headerClassName: TH_CLASSE,
          cellClassName: "max-w-xs whitespace-pre-wrap break-words px-4 py-3 text-xs text-t3",
        },
      },
      {
        id: "quantidade",
        header: "Qtd",
        cell: ({ row }) => {
          const item = row.original;
          return (
            <input
              type="number"
              min="0.001"
              step="any"
              value={draftQuantidade(item)}
              disabled={!!salvando[item.id] || itemBloqueado(item)}
              onChange={(e) => onCellEdit(item.id, "quantidade", e.target.value)}
              onBlur={() => commitRow(item)}
              className="w-16 rounded-md border border-bdr px-1.5 py-1 text-xs outline-none focus:border-prx disabled:opacity-50"
            />
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "whitespace-nowrap px-4 py-3" },
      },
      {
        id: "unidade",
        header: "Unidade",
        cell: ({ row }) => {
          const item = row.original;
          const unidadeAtual = draftUnidade(item);
          return (
            <select
              value={unidadeAtual}
              disabled={!!salvando[item.id] || itemBloqueado(item)}
              onChange={(e) => {
                const novaUnidade = e.target.value;
                onCellEdit(item.id, "unidade", novaUnidade);
                commitRow(item, { unidade: novaUnidade });
              }}
              className="rounded-md border border-bdr px-1.5 py-1 text-xs outline-none focus:border-prx disabled:opacity-50"
            >
              {!UNIDADES_VALIDAS.has(unidadeAtual) && <option value={unidadeAtual}>{unidadeAtual}</option>}
              {UNIDADES.map((u) => (
                <option key={u} value={u}>
                  {u}
                </option>
              ))}
            </select>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "whitespace-nowrap px-4 py-3" },
      },
      {
        id: "produto",
        header: "Produto",
        cell: ({ row }) => {
          const item = row.original;
          const produtoIdAtual = draftProdutoId(item);
          const nomeExibidoAtual = draftNomeExibido(item);
          return (
            <ProdutoAutocomplete
              produtos={produtos}
              valorAtualNome={nomeExibidoAtual ?? (produtoIdAtual ? (produtoNomePorId.get(produtoIdAtual) ?? null) : null)}
              disabled={!!salvando[item.id] || itemBloqueado(item)}
              onSelecionar={(p) => {
                onCellEdit(item.id, "produtoId", p.id);
                onCellEdit(item.id, "nomeExibido", null);
                commitRow(item, { produtoId: p.id, nomeExibido: null });
              }}
              onUsarNomeLivre={(nome) => {
                onCellEdit(item.id, "produtoId", null);
                onCellEdit(item.id, "nomeExibido", nome);
                commitRow(item, { produtoId: null, nomeExibido: nome });
              }}
            />
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
      {
        id: "status",
        header: () => (
          <button
            type="button"
            onClick={onClicarHeaderStatus}
            className="inline-flex w-full items-center justify-center gap-1 font-medium uppercase tracking-wide hover:text-t1"
            title="Ordenar por status — mais urgente primeiro"
          >
            Status
            <span className="text-[10px]">
              {ordenacaoStatus === "asc" ? "▲" : ordenacaoStatus === "desc" ? "▼" : "⇅"}
            </span>
          </button>
        ),
        cell: ({ row }) => {
          const item = row.original;
          const travado = item.temRespostaFornecedorConfirmada;
          const bloqueado = itemBloqueado(item);
          const { semProduto, mensagemErro } = classificarStatusItemGrid(item);
          const erro = erros[item.id];
          return (
            <>
              {bloqueado && (
                <span
                  className="inline-flex items-center text-t3"
                  title={
                    travado
                      ? "Este item já foi conferido por um fornecedor e não pode ser editado. Exclua e adicione novamente se precisar corrigir."
                      : "Cotação finalizada não aceita alteração de itens."
                  }
                >
                  <svg
                    width="13"
                    height="13"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={2}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                  </svg>
                </span>
              )}
              {mensagemErro && <p className={`${semProduto ? "text-er" : "text-wa-txt"}`}>{mensagemErro}</p>}
              {!bloqueado && !mensagemErro && (
                <span className="inline-flex items-center gap-1 text-ok" aria-label="Sem pendências">
                  <svg
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={3}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="M20 6L9 17l-5-5" />
                  </svg>
                  OK
                </span>
              )}
              {erro && <p className="mt-1 text-er">{erro}</p>}
            </>
          );
        },
        meta: { headerClassName: `${TH_CLASSE} text-center`, cellClassName: "px-4 py-3 text-xs text-center" },
      },
      {
        id: "acoes",
        header: "Ações",
        cell: ({ row }) => {
          const item = row.original;
          return (
            <button
              type="button"
              onClick={() => pedirConfirmacaoExclusao(item)}
              disabled={!!removendo[item.id] || cotacaoFinalizada}
              aria-label="Excluir"
              title={cotacaoFinalizada ? "Cotação finalizada não aceita alteração de itens." : "Excluir"}
              className="inline-flex items-center justify-center rounded-md border border-er/40 p-1.5 text-er hover:bg-er-d disabled:opacity-50"
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
                <path d="M10 11v6" />
                <path d="M14 11v6" />
                <path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2" />
              </svg>
            </button>
          );
        },
        meta: { headerClassName: "whitespace-nowrap px-4 py-3 font-medium text-center", cellClassName: "whitespace-nowrap px-4 py-3 text-center" },
      },
    ],
    // draftQuantidade/draftUnidade/draftProdutoId/draftNomeExibido/onCellEdit/commitRow/
    // pedirConfirmacaoExclusao são recriadas a cada render mas só leem rascunhos/salvando/erros/
    // removendo/produtos/produtoNomePorId/cotacaoId/ordenacaoStatus — todos já listados
    // abaixo, então o memo recalcula sempre que o resultado dessas funções mudaria.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [rascunhos, salvando, erros, removendo, produtos, produtoNomePorId, cotacaoId, ordenacaoStatus, cotacaoFinalizada],
  );

  const table = useReactTable({
    data: itensOrdenados,
    columns: colunas,
    getRowId: (item) => item.id,
    getCoreRowModel: getCoreRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    state: { pagination: paginacao },
    onPaginationChange: setPaginacao,
  });

  return (
    <Card className="flex h-full flex-col">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="font-semibold text-t1">Lista de produtos</h2>
          <p className="mt-1 text-sm text-t2">
            Adicione, edite ou exclua produtos individualmente — ou cole uma lista do WhatsApp de uma vez.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={() => setModalAberto(true)}
            disabled={cotacaoFinalizada}
            title={cotacaoFinalizada ? "Cotação finalizada não aceita novos itens." : undefined}
            className="rounded-md border border-bdr px-3 py-2 text-sm font-medium hover:bg-hov disabled:opacity-50"
          >
            Colar do WhatsApp
          </button>
          <button
            type="button"
            onClick={() => setAdicionando(true)}
            disabled={adicionando || cotacaoFinalizada}
            title={cotacaoFinalizada ? "Cotação finalizada não aceita novos itens." : undefined}
            className="rounded-md bg-prx px-3 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
          >
            + Adicionar Produto
          </button>
        </div>
      </div>

      {/* Lista nunca teve item: nem o grid carrega — só a mensagem de onboarding. O
          grid (com cabeçalho de colunas, altura fixa etc.) só faz sentido quando há
          algo pra mostrar ou editar. Exceção: `adicionando` (usuário clicou em "+
          Adicionar Produto" com a lista ainda vazia) precisa do grid mesmo assim,
          porque a linha de cadastro (NovaLinhaGridProdutos) é renderizada dentro
          dele via extraRows — achado do usuário: "no primeiro add o grid carrega". */}
      {itens.length === 0 && !adicionando ? (
        <div className="mt-4 flex flex-1 flex-col items-center justify-center gap-3 rounded-md border border-dashed border-bdr py-16 text-center text-t2">
          <svg
            width="40"
            height="40"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={1.5}
            strokeLinecap="round"
            strokeLinejoin="round"
            className="text-t3/60"
          >
            <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
            <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
            <path d="M9 12h6" />
            <path d="M9 16h6" />
          </svg>
          <p className="text-sm">
            Nenhum produto adicionado ainda. Use &quot;+ Adicionar Produto&quot; ou &quot;Colar do WhatsApp&quot;.
          </p>
        </div>
      ) : (
        <>
          {itens.length > 0 && (
            <input
              value={filtro}
              onChange={(e) => setFiltro(e.target.value)}
              placeholder="Buscar por produto, unidade ou texto original..."
              className="mt-4 rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
            />
          )}

          {/* Altura fixa em vez de flex-1: mesmo paginado, uma flex-basis dinâmica
              (flex-1) ainda deixava a altura variar com o conteúdo da página atual —
              página cheia "empurrava" o Card (esticado pelo CSS Grid pra acompanhar a
              coluna da direita) além da altura das páginas mais curtas (achado do
              usuário: "a altura tá mudando de acordo com as linhas"). Um valor
              numérico (não relativo a flex/grid) é imune ao auto-sizing por
              min-content do CSS Grid — por isso a altura abaixo é um valor fixo em
              px, com overflow-y-auto só como rede de segurança pra alguma linha que
              quebre mais que o normal. */}
          <div className="mt-4 h-[650px] overflow-y-auto rounded-md border border-bdr">
            <DataGrid
              table={table}
              tableClassName="w-full text-sm"
              theadClassName="sticky top-0 z-10 bg-surf text-left text-xs uppercase tracking-wide text-t3"
              tbodyClassName="divide-y divide-bdr"
              rowClassName={(item) => `align-top ${classificarStatusItemGrid(item).corLinha}`}
              extraRows={
                adicionando && (
                  <NovaLinhaGridProdutos
                    cotacaoId={cotacaoId}
                    produtos={produtos}
                    onAdicionado={() => {
                      setAdicionando(false);
                      recarregar();
                    }}
                    onCancelar={() => setAdicionando(false)}
                  />
                )
              }
              emptyContent={
                itensFiltrados.length === 0 && !adicionando ? (
                  <tr>
                    <td colSpan={colunas.length} className="px-4 py-6 text-center text-t2">
                      Nenhum item bate com o filtro.
                    </td>
                  </tr>
                ) : undefined
              }
            />
          </div>

          {itensOrdenados.length > 0 && (
            <div className="mt-3 flex shrink-0 items-center justify-between gap-3 text-sm text-t2">
              <span>
                {table.getState().pagination.pageIndex * table.getState().pagination.pageSize + 1}–
                {Math.min(
                  (table.getState().pagination.pageIndex + 1) * table.getState().pagination.pageSize,
                  itensOrdenados.length,
                )}{" "}
                de {itensOrdenados.length}
              </span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => table.previousPage()}
                  disabled={!table.getCanPreviousPage()}
                  className="rounded-md border border-bdr px-2.5 py-1 text-xs font-medium hover:bg-hov disabled:opacity-40"
                >
                  Anterior
                </button>
                <span className="text-xs text-t3">
                  Página {table.getState().pagination.pageIndex + 1} de {Math.max(table.getPageCount(), 1)}
                </span>
                <button
                  type="button"
                  onClick={() => table.nextPage()}
                  disabled={!table.getCanNextPage()}
                  className="rounded-md border border-bdr px-2.5 py-1 text-xs font-medium hover:bg-hov disabled:opacity-40"
                >
                  Próxima
                </button>
              </div>
            </div>
          )}
        </>
      )}

      <ColarWhatsappModal
        open={modalAberto}
        cotacaoId={cotacaoId}
        onClose={() => setModalAberto(false)}
        onImportado={recarregar}
      />

      <Modal
        open={itemParaExcluir !== null}
        onClose={() => setItemParaExcluir(null)}
        title="Excluir item"
        footer={
          <>
            <button
              type="button"
              onClick={() => setItemParaExcluir(null)}
              className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
            >
              Cancelar
            </button>
            <button
              type="button"
              onClick={confirmarExclusao}
              disabled={!!(itemParaExcluir && removendo[itemParaExcluir.id])}
              className="rounded-md bg-er px-4 py-2 text-sm font-medium text-white hover:bg-er-txt disabled:opacity-50"
            >
              Excluir
            </button>
          </>
        }
      >
        <p className="text-sm text-t2">
          Excluir {itemParaExcluir?.textoOriginal ? `"${itemParaExcluir.textoOriginal}"` : "este item"} da lista?
          Essa ação não pode ser desfeita.
        </p>
      </Modal>
    </Card>
  );
}
