"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import StatusBadge from "@/components/StatusBadge";
import { editarItemCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import {
  diferencaPercentual,
  economiaPotencial,
  menorOferta,
  statusCobertura,
  StatusCobertura,
  todosFornecedores,
  totalEconomia,
} from "@/lib/comparativo";
import { formatarMoeda } from "@/lib/format";
import { ComparativoItemResponse } from "@/lib/types";
import { UNIDADES } from "@/lib/unidades";

const LABEL_STATUS: Record<StatusCobertura, string> = {
  melhor_compra: "Melhor Compra",
  alta_variacao: "Alta Variação",
  sem_cotacao: "Sem Cotação",
  cobertura_parcial: "Cobertura Parcial",
};

// Paleta cíclica pro indicador colorido da coluna REC. — mesmo espírito da barra de
// cor por fornecedor da FornecedoresSidebar, só pra diferenciar visualmente quem é o
// fornecedor recomendado em cada linha sem depender de uma cor fixa por fornecedor.
const CORES_REC = ["#FF8000", "#47C7FC", "#8B5CF6", "#F59E0B", "#EF4444", "#10B981"];

const TH_CLASSE = "whitespace-nowrap px-4 py-3 font-medium";
const TD_CLASSE = "whitespace-nowrap px-4 py-3";

interface Props {
  itens: ComparativoItemResponse[];
  cotacaoId: string;
  onItemEditado: () => void;
}

interface Rascunho {
  quantidade?: string;
  unidade?: string;
}

export default function TabelaComparativa({ itens, cotacaoId, onItemEditado }: Props) {
  const [busca, setBusca] = useState("");
  const [filtroStatus, setFiltroStatus] = useState<StatusCobertura | "TODOS">("TODOS");
  const [filtroFornecedor, setFiltroFornecedor] = useState<string>("TODOS");

  // Rascunho compartilhado por linha: Qtd e Unidade sempre são enviadas juntas ao
  // editarItemCotacao (mesmo quando só um campo mudou), então precisam de um estado
  // comum por cotacaoProdutoId em vez de estado local isolado por célula — senão editar
  // um campo antes do blur do outro perderia a edição em andamento.
  const [rascunhos, setRascunhos] = useState<Record<string, Rascunho>>({});
  const [salvando, setSalvando] = useState<Record<string, boolean>>({});
  const [erros, setErros] = useState<Record<string, string>>({});

  const fornecedores = useMemo(() => todosFornecedores(itens), [itens]);
  const fornecedorIds = useMemo(() => Array.from(fornecedores.keys()), [fornecedores]);
  const corPorFornecedor = useMemo(() => {
    const mapa = new Map<string, string>();
    fornecedorIds.forEach((id, i) => mapa.set(id, CORES_REC[i % CORES_REC.length]));
    return mapa;
  }, [fornecedorIds]);

  const itensFiltrados = useMemo(() => {
    return itens.filter((item) => {
      if (busca && !item.nomeProduto.toLowerCase().includes(busca.toLowerCase())) return false;
      if (filtroStatus !== "TODOS" && statusCobertura(item, fornecedorIds.length) !== filtroStatus) return false;
      if (filtroFornecedor !== "TODOS" && !item.precosPorFornecedor.some((p) => p.fornecedorId === filtroFornecedor)) {
        return false;
      }
      return true;
    });
  }, [itens, busca, filtroStatus, filtroFornecedor, fornecedorIds.length]);

  const economiaTotal = totalEconomia(itensFiltrados);
  const maiorVariacao = itensFiltrados.reduce<{ nome: string; diff: number } | null>((maior, item) => {
    const diff = diferencaPercentual(item);
    if (diff === null) return maior;
    return !maior || diff > maior.diff ? { nome: item.nomeProduto, diff } : maior;
  }, null);

  function draftQuantidade(item: ComparativoItemResponse) {
    return rascunhos[item.cotacaoProdutoId]?.quantidade ?? String(item.quantidade);
  }

  function draftUnidade(item: ComparativoItemResponse) {
    return rascunhos[item.cotacaoProdutoId]?.unidade ?? item.unidade;
  }

  function onCellEdit(rowId: string, campo: keyof Rascunho, valor: string) {
    setRascunhos((r) => ({ ...r, [rowId]: { ...r[rowId], [campo]: valor } }));
  }

  async function commitRow(item: ComparativoItemResponse, overrides?: Rascunho) {
    const novaQuantidade = overrides?.quantidade ?? draftQuantidade(item);
    const novaUnidade = overrides?.unidade ?? draftUnidade(item);
    const qtdNum = Number(novaQuantidade.replace(",", "."));
    if (!qtdNum || qtdNum <= 0 || !novaUnidade.trim()) return;
    if (qtdNum === item.quantidade && novaUnidade === item.unidade) return;

    setSalvando((s) => ({ ...s, [item.cotacaoProdutoId]: true }));
    setErros((e) => {
      const resto = { ...e };
      delete resto[item.cotacaoProdutoId];
      return resto;
    });
    try {
      await editarItemCotacao(cotacaoId, item.cotacaoProdutoId, { quantidade: qtdNum, unidade: novaUnidade.trim() });
      onItemEditado();
    } catch (err) {
      setErros((e) => ({ ...e, [item.cotacaoProdutoId]: getErrorMessage(err, "Não foi possível salvar a alteração.") }));
      setRascunhos((r) => {
        const resto = { ...r };
        delete resto[item.cotacaoProdutoId];
        return resto;
      });
    } finally {
      setSalvando((s) => ({ ...s, [item.cotacaoProdutoId]: false }));
    }
  }

  const colunas = useMemo<ColumnDef<ComparativoItemResponse>[]>(() => {
    const dinamicas: ColumnDef<ComparativoItemResponse>[] = fornecedorIds.map((fid) => ({
      id: fid,
      header: fornecedores.get(fid),
      cell: ({ row }) => {
        const item = row.original;
        const menor = menorOferta(item);
        const preco = item.precosPorFornecedor.find((p) => p.fornecedorId === fid);
        if (!preco) return <span className="text-t3">—</span>;
        const ehMenor = menor !== null && !preco.semEstoque && preco.status === "OK" && preco.fornecedorId === fid;
        return (
          <>
            {preco.semEstoque ? (
              <span className="text-t3">sem estoque</span>
            ) : (
              <span className={ehMenor ? "font-semibold text-ok" : ""}>{formatarMoeda(preco.precoUnitarioCalculado)}</span>
            )}
            {preco.divergenciaComparativa && (
              <div title="Preço bem acima dos demais fornecedores para este item — possível preço de caixa/fardo lançado como unitário">
                <StatusBadge status="DIVERGENCIA_COMPARATIVA" />
              </div>
            )}
            {preco.status !== "OK" && (
              <div>
                <StatusBadge status={preco.status} />
              </div>
            )}
          </>
        );
      },
      meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
    }));

    return [
      {
        id: "produto",
        header: "Produto",
        accessorFn: (item) => item.nomeProduto,
        meta: { headerClassName: TH_CLASSE, cellClassName: "whitespace-nowrap px-4 py-3 font-medium text-t1" },
      },
      {
        id: "quantidade",
        header: "Qtd",
        cell: ({ row }) => {
          const item = row.original;
          const erro = erros[item.cotacaoProdutoId];
          return (
            <>
              <input
                type="number"
                min="0.001"
                step="any"
                value={draftQuantidade(item)}
                disabled={!!salvando[item.cotacaoProdutoId]}
                onChange={(e) => onCellEdit(item.cotacaoProdutoId, "quantidade", e.target.value)}
                onBlur={() => commitRow(item)}
                className="w-16 rounded-md border border-bdr px-1.5 py-1 text-xs outline-none focus:border-prx disabled:opacity-50"
              />
              {erro && <p className="mt-1 text-[10px] text-er">{erro}</p>}
            </>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      {
        id: "unidade",
        header: "Unidade",
        cell: ({ row }) => {
          const item = row.original;
          return (
            <select
              value={draftUnidade(item)}
              disabled={!!salvando[item.cotacaoProdutoId]}
              onChange={(e) => {
                const novaUnidade = e.target.value;
                onCellEdit(item.cotacaoProdutoId, "unidade", novaUnidade);
                commitRow(item, { unidade: novaUnidade });
              }}
              className="rounded-md border border-bdr px-1.5 py-1 text-xs outline-none focus:border-prx disabled:opacity-50"
            >
              {UNIDADES.map((u) => (
                <option key={u} value={u}>
                  {u}
                </option>
              ))}
            </select>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      ...dinamicas,
      {
        id: "menor",
        header: "Menor",
        cell: ({ row }) => {
          const menor = menorOferta(row.original);
          return menor ? formatarMoeda(menor.preco) : "—";
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "whitespace-nowrap px-4 py-3 font-semibold text-ok" },
      },
      {
        id: "rec",
        header: "Rec.",
        cell: ({ row }) => {
          const menor = menorOferta(row.original);
          if (!menor) return <span className="text-t3">—</span>;
          return (
            <span className="inline-flex items-center gap-1.5">
              <span
                className="h-2 w-2 shrink-0 rounded-full"
                style={{ background: corPorFornecedor.get(menor.fornecedorId) ?? "#a49d93" }}
              />
              <span className="text-t2">{menor.nomeFornecedor}</span>
            </span>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      {
        id: "economia",
        header: "Economia",
        cell: ({ row }) => {
          const economia = economiaPotencial(row.original);
          return economia > 0 ? formatarMoeda(economia) : "—";
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "whitespace-nowrap px-4 py-3 font-medium text-ok" },
      },
    ];
    // commitRow/draftQuantidade/draftUnidade não entram na lista de deps porque são
    // recriadas a cada render mas só leem rascunhos/salvando/erros/cotacaoId/onItemEditado
    // — todos já listados abaixo, então o memo já recalcula sempre que o resultado dessas
    // funções mudaria.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fornecedorIds, fornecedores, corPorFornecedor, rascunhos, salvando, erros, cotacaoId, onItemEditado]);

  const table = useReactTable({
    data: itensFiltrados,
    columns: colunas,
    getRowId: (item) => item.cotacaoProdutoId,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div>
      <div className="flex flex-wrap items-center gap-3">
        <input
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          placeholder="Buscar produto..."
          className="rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
        <select
          value={filtroStatus}
          onChange={(e) => setFiltroStatus(e.target.value as StatusCobertura | "TODOS")}
          className="rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        >
          <option value="TODOS">Todos os status</option>
          {(Object.keys(LABEL_STATUS) as StatusCobertura[]).map((s) => (
            <option key={s} value={s}>
              {LABEL_STATUS[s]}
            </option>
          ))}
        </select>
        <select
          value={filtroFornecedor}
          onChange={(e) => setFiltroFornecedor(e.target.value)}
          className="rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        >
          <option value="TODOS">Todos os fornecedores</option>
          {fornecedorIds.map((id) => (
            <option key={id} value={id}>
              {fornecedores.get(id)}
            </option>
          ))}
        </select>
        <div className="ml-auto flex gap-2">
          <Link
            href={`/cotacoes/${cotacaoId}/mapa`}
            className="rounded-md bg-prx px-3 py-2 text-sm font-medium text-white hover:bg-prx-l"
          >
            Gerar Mapa →
          </Link>
          <button
            onClick={() => window.print()}
            className="rounded-md border border-bdr px-3 py-2 text-sm font-medium hover:bg-hov"
          >
            Exportar
          </button>
        </div>
      </div>

      <DataGrid
        table={table}
        wrapperClassName="mt-4 overflow-x-auto rounded-lg border border-bdr"
        tableClassName="w-full text-sm"
        theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
        tbodyClassName="divide-y divide-bdr"
        emptyContent={
          <tr>
            <td colSpan={colunas.length} className="px-4 py-6 text-center text-t2">
              Nenhum produto encontrado com esses filtros.
            </td>
          </tr>
        }
      />

      {itensFiltrados.length > 0 && (
        <div className="mt-4 rounded-lg border border-prx/30 bg-prx/5 p-4 text-sm">
          <p className="text-t1">
            Economia potencial total nesta visão: <span className="font-semibold text-ok">{formatarMoeda(economiaTotal)}</span>
          </p>
          {maiorVariacao && (
            <p className="mt-1 text-t2">
              Maior variação de preço: <span className="font-medium text-t1">{maiorVariacao.nome}</span> ({maiorVariacao.diff.toFixed(0)}% entre o menor e o maior preço)
            </p>
          )}
        </div>
      )}
    </div>
  );
}
