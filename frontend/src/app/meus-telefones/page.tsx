"use client";

import { useMemo, useState } from "react";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import { listarMeusTelefones, removerMeuTelefone } from "@/lib/api";
import { formatarData } from "@/lib/format";
import { getErrorMessage } from "@/lib/errors";
import { useAsync } from "@/hooks/useAsync";
import { UsuarioTelefone } from "@/lib/types";
import TelefoneFormModal from "./components/TelefoneFormModal";

const TH_CLASSE = "px-4 py-3 font-medium";

function MeusTelefonesContent() {
  const { data: telefones, erro, setData: setTelefones } = useAsync(
    () => listarMeusTelefones(),
    [],
    "Não foi possível carregar seus números.",
  );

  const [modalAberto, setModalAberto] = useState(false);
  const [removendo, setRemovendo] = useState<string | null>(null);
  const [erroRemover, setErroRemover] = useState<string | null>(null);

  function onCriado(telefone: UsuarioTelefone) {
    setTelefones((atual) => [telefone, ...(atual ?? [])]);
  }

  async function onRemover(telefone: UsuarioTelefone) {
    if (!window.confirm(`Remover "${telefone.numeroWhatsapp}"? Ele deixa de poder mandar mensagem em seu nome.`)) return;
    setRemovendo(telefone.id);
    setErroRemover(null);
    try {
      await removerMeuTelefone(telefone.id);
      setTelefones((atual) => (atual ?? []).filter((t) => t.id !== telefone.id));
    } catch (err) {
      setErroRemover(getErrorMessage(err, "Não foi possível remover o número."));
    } finally {
      setRemovendo(null);
    }
  }

  const colunas = useMemo<ColumnDef<UsuarioTelefone>[]>(
    () => [
      {
        id: "numero",
        header: "Número",
        accessorFn: (t) => t.numeroWhatsapp,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 font-medium text-t1" },
      },
      {
        id: "nome",
        header: "Nome",
        cell: ({ row }) => row.original.nomeContato ?? "—",
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "criadoEm",
        header: "Criado em",
        cell: ({ row }) => formatarData(row.original.criadoEm),
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "acoes",
        header: "Ações",
        cell: ({ row }) => {
          const t = row.original;
          return (
            <button
              type="button"
              disabled={removendo === t.id}
              onClick={() => onRemover(t)}
              className="text-er hover:underline disabled:opacity-50"
            >
              {removendo === t.id ? "Removendo..." : "Remover"}
            </button>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
    ],
    [removendo],
  );

  const table = useReactTable({
    data: telefones ?? [],
    columns: colunas,
    getRowId: (t) => t.id,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-4xl flex-1 px-6 py-8">
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-t1">Meus Telefones</h1>
            <p className="mt-1 text-sm text-t2">
              Números de WhatsApp autorizados a mandar mensagem em seu nome — lista de produtos e respostas de
              fornecedor.
            </p>
          </div>
          <button
            type="button"
            onClick={() => setModalAberto(true)}
            className="shrink-0 rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l"
          >
            + Adicionar número
          </button>
        </div>

        {erro && <p className="mt-2 text-sm text-er">{erro}</p>}
        {erroRemover && <p className="mt-2 text-sm text-er">{erroRemover}</p>}

        <DataGrid
          table={table}
          wrapperClassName="mt-4 overflow-hidden rounded-lg border border-bdr"
          tableClassName="w-full text-sm"
          theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
          tbodyClassName="divide-y divide-bdr"
          loading={telefones === null}
          loadingContent={
            <tr>
              <td colSpan={4} className="px-4 py-6 text-center text-t2">
                Carregando...
              </td>
            </tr>
          }
          emptyContent={
            <tr>
              <td colSpan={4} className="px-4 py-6 text-center text-t2">
                Nenhum número cadastrado ainda.
              </td>
            </tr>
          }
        />
      </main>

      <TelefoneFormModal open={modalAberto} onClose={() => setModalAberto(false)} onCriado={onCriado} />
    </>
  );
}

export default function MeusTelefonesPage() {
  return (
    <AuthGuard>
      <MeusTelefonesContent />
    </AuthGuard>
  );
}
