"use client";

import { Dispatch, SetStateAction, useEffect, useMemo, useState } from "react";
import { ColumnDef, getCoreRowModel, getPaginationRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import Card from "@/components/Card";
import Modal from "@/components/Modal";
import Pagination from "@/components/Pagination";
import { buscarLista, buscarProdutosPorIds, removerItemCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ItemListaResponse, Produto } from "@/lib/types";
import { idsProdutosDosItens } from "@/lib/itensLista";
import { normTxt } from "@/lib/normalizacao";
import { classificarStatusItemGrid } from "@/lib/statusItemGrid";
import { UNIDADES } from "@/lib/unidades";
import { useEdicaoItemLista } from "@/hooks/useEdicaoItemLista";
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
  // Cotações WHATSAPP têm a lista populada automaticamente pela AI — "+ Adicionar
  // Produto"/"Colar do WhatsApp" só fazem sentido pro fluxo Web manual (refactor
  // 2026-08-20: este grid passou a ser usado também dentro da aba "Conferência da
  // Lista Base" do AprovacaoModal, pros dois canais). Excluir item continua liberado
  // pros dois — corrigir um item mal-parseado não exige a capacidade de adicionar.
  podeAdicionarOuColar?: boolean;
  // true (default) quando este grid é dono da própria altura/scroll (uso histórico:
  // dentro de uma coluna de altura fixa em page.tsx). false quando hospedado dentro do
  // corpo já rolável do AprovacaoModal — aí o grid cresce naturalmente e quem rola é o
  // modal, evitando um scroll aninhado dentro de outro.
  scrollProprio?: boolean;
}

const UNIDADES_VALIDAS = new Set<string>(UNIDADES);

const TH_CLASSE = "whitespace-nowrap px-4 py-3 font-medium";

// Linhas por página — troca o scroll interno (que exigia o Card crescer sem limite
// pra caber todo mundo, achado do usuário: "container cresce pra sempre") por altura
// previsível independente de quantos itens a lista tem.
const TAMANHO_PAGINA = 12;

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
  podeAdicionarOuColar = true,
  scrollProprio = true,
}: Props) {
  const [adicionando, setAdicionando] = useState(false);
  const [modalAberto, setModalAberto] = useState(false);
  const [filtro, setFiltro] = useState("");
  // null = ordem natural (a que o backend devolve, por `ordem`); "asc" = mais urgente
  // primeiro (erro > aviso > travado > ok); "desc" = inverso.
  const [ordenacaoStatus, setOrdenacaoStatus] = useState<"asc" | "desc" | null>(null);

  const [removendo, setRemovendo] = useState<Record<string, boolean>>({});
  const [paginacao, setPaginacao] = useState({ pageIndex: 0, pageSize: TAMANHO_PAGINA });
  const [itemParaExcluir, setItemParaExcluir] = useState<ItemListaResponse | null>(null);

  // Cotação finalizada bloqueia toda edição de item no backend (ConflictException) —
  // desabilitar aqui evita o usuário editar e só descobrir pelo erro depois de salvar.
  function itemBloqueado(item: ItemListaResponse) {
    return item.temRespostaFornecedorConfirmada || cotacaoFinalizada;
  }

  // Recarrega lista E catálogo — adicionar manualmente (nomeProdutoLivre) ou colar do
  // WhatsApp pode ter criado um Produto novo no backend (resolver-ou-criar), que o
  // catálogo local (produtos, buscado uma vez no carregamento da página) ainda não
  // conhece. Sem isso, o autocomplete mostra o campo vazio pro item recém-adicionado
  // até a página inteira ser recarregada (achado do smoke test manual).
  async function recarregar() {
    try {
      const itensAtualizados = await buscarLista(cotacaoId);
      const catalogoAtualizado = await buscarProdutosPorIds(idsProdutosDosItens(itensAtualizados));
      onListaAtualizada(itensAtualizados);
      onProdutosAtualizados(catalogoAtualizado);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível atualizar a lista."));
    }
  }

  const {
    rascunhos,
    salvando,
    erros,
    setErros,
    draftQuantidade,
    draftUnidade,
    draftProdutoId,
    draftNomeExibido,
    onCellEdit,
    commitRow,
  } = useEdicaoItemLista({ cotacaoId, itemBloqueado, onSalvo: recarregar });

  // Filtrar pode reduzir o total de páginas abaixo da página atual, deixando a
  // tabela "presa" numa página vazia — volta pra primeira sempre que o termo muda.
  useEffect(() => {
    setPaginacao((p) => ({ ...p, pageIndex: 0 }));
  }, [filtro]);

  function onClicarHeaderStatus() {
    setOrdenacaoStatus((atual) => (atual === null ? "asc" : atual === "asc" ? "desc" : null));
  }

  const produtoNomePorId = useMemo(() => new Map(produtos.map((p) => [p.id, p.nome])), [produtos]);

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

  // h-full (Prompt 25, feedback 2026-08-16): quando scrollProprio (uso histórico —
  // a página reserva o resto da viewport pra este Card), ele preenche até o limite
  // dela em vez de encolher pro tamanho do conteúdo, e só a tabela rola
  // internamente. Dentro do AprovacaoModal (scrollProprio=false), este conteúdo já
  // vive dentro de um corpo de modal com seu próprio fundo/borda — um `Card` aqui
  // dentro criaria uma moldura redundante (mesmo bg-card do modal, achado do
  // frontend-ux-designer) — usa um wrapper neutro, sem borda/padding próprios.
  const Wrapper = scrollProprio ? Card : "div";
  const wrapperClasse = scrollProprio ? "flex h-full flex-col" : "flex flex-col";

  return (
    <Wrapper className={wrapperClasse}>
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="font-semibold text-t1">Lista de produtos</h2>
          <p className="mt-1 text-sm text-t2">
            {podeAdicionarOuColar
              ? "Adicione, edite ou exclua produtos individualmente — ou cole uma lista do WhatsApp de uma vez."
              : "Corrija quantidade, unidade ou produto identificado antes de seguir para aprovação."}
          </p>
        </div>
        {podeAdicionarOuColar && (
          <div className="flex shrink-0 items-center gap-2">
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
        )}
      </div>

      {/* Lista nunca teve item: nem o grid carrega — só a mensagem de onboarding. O
          grid (com cabeçalho de colunas, altura fixa etc.) só faz sentido quando há
          algo pra mostrar ou editar. Exceção: `adicionando` (usuário clicou em "+
          Adicionar Produto" com a lista ainda vazia) precisa do grid mesmo assim,
          porque a linha de cadastro (NovaLinhaGridProdutos) é renderizada dentro
          dele via extraRows — achado do usuário: "no primeiro add o grid carrega". */}
      {itens.length === 0 && !adicionando ? (
        <div
          className={`mt-4 flex flex-col items-center justify-center gap-3 rounded-md border border-dashed border-bdr py-16 text-center text-t2 ${scrollProprio ? "flex-1" : ""}`}
        >
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
            {podeAdicionarOuColar
              ? 'Nenhum produto adicionado ainda. Use "+ Adicionar Produto" ou "Colar do WhatsApp".'
              : "Nenhum produto identificado ainda nesta lista."}
          </p>
        </div>
      ) : (
        <>
          {itens.length > 0 && (
            <input
              value={filtro}
              onChange={(e) => setFiltro(e.target.value)}
              placeholder="Buscar por produto, unidade ou texto original..."
              className="mt-4 shrink-0 rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
            />
          )}

          {/* Antes tinha altura fixa (h-[650px]) — um valor imune ao auto-sizing por
              min-content da antiga CSS Grid de 2 colunas. O Prompt 25 removeu esse
              layout; agora este Card ocupa min-h-0 flex-1 dentro de uma coluna de
              altura fixa (a área do passo ativo em page.tsx), então esta é a única
              caixa com scroll interno — preenche todo o espaço disponível e só rola
              se o conteúdo (linhas da tabela) ultrapassar esse limite, sem nunca
              crescer além dele nem duplicar o scroll da página (achado do usuário,
              2026-08-16). */}
          <div className={`mt-4 rounded-md border border-bdr ${scrollProprio ? "min-h-0 flex-1 overflow-y-auto" : ""}`}>
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
            <Pagination
              className="mt-3"
              pageIndex={table.getState().pagination.pageIndex}
              pageSize={table.getState().pagination.pageSize}
              total={itensOrdenados.length}
              onPageChange={(pageIndex) => setPaginacao((p) => ({ ...p, pageIndex }))}
            />
          )}
        </>
      )}

      <ColarWhatsappModal
        open={modalAberto}
        cotacaoId={cotacaoId}
        onClose={() => setModalAberto(false)}
        onImportado={recarregar}
        semBackdrop={!scrollProprio}
      />

      <Modal
        open={itemParaExcluir !== null}
        onClose={() => setItemParaExcluir(null)}
        title="Excluir item"
        semBackdrop={!scrollProprio}
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
    </Wrapper>
  );
}
