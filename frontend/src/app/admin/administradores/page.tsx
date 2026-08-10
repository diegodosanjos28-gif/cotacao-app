"use client";

import { useMemo, useState } from "react";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import Modal from "@/components/Modal";
import StatusBadge from "@/components/StatusBadge";
import { listarAdministradores, resetarSenhaAdministrador } from "@/lib/api";
import { formatarData } from "@/lib/format";
import { getErrorMessage } from "@/lib/errors";
import { useAsync } from "@/hooks/useAsync";
import { UsuarioAdmin } from "@/lib/types";
import AdminSubNav from "../components/AdminSubNav";
import AdministradorFormModal from "./components/AdministradorFormModal";

const TH_CLASSE = "px-4 py-3 font-medium";

function SenhaGeradaModal({ senha, onClose }: { senha: string | null; onClose: () => void }) {
  return (
    <Modal open={senha !== null} onClose={onClose} title="Senha gerada">
      <p className="text-sm text-t2">
        Copie a senha abaixo agora — ela não será mostrada novamente. Repasse ao novo administrador por fora do
        sistema.
      </p>
      <p className="mt-3 rounded-md border border-bdr bg-surf px-3 py-2 text-center font-mono text-lg tracking-wide text-t1">
        {senha}
      </p>
      <div className="mt-4 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l"
        >
          Já copiei
        </button>
      </div>
    </Modal>
  );
}

function AdministradoresContent() {
  const { data: administradores, erro, setData: setAdministradores } = useAsync(
    () => listarAdministradores(),
    [],
    "Não foi possível carregar os administradores.",
  );
  const [modalAberto, setModalAberto] = useState<{ administrador: UsuarioAdmin | null } | null>(null);
  const [senhaGerada, setSenhaGerada] = useState<string | null>(null);
  const [resetando, setResetando] = useState<string | null>(null);
  const [erroReset, setErroReset] = useState<string | null>(null);

  function onSalvo(administrador: UsuarioAdmin) {
    setAdministradores((atual) => {
      const lista = atual ?? [];
      const existe = lista.some((a) => a.id === administrador.id);
      return existe ? lista.map((a) => (a.id === administrador.id ? administrador : a)) : [administrador, ...lista];
    });
    if (administrador.senhaGerada) setSenhaGerada(administrador.senhaGerada);
  }

  async function onResetarSenha(administrador: UsuarioAdmin) {
    setResetando(administrador.id);
    setErroReset(null);
    try {
      const { senha } = await resetarSenhaAdministrador(administrador.id);
      setSenhaGerada(senha);
    } catch (err) {
      setErroReset(getErrorMessage(err, "Não foi possível resetar a senha."));
    } finally {
      setResetando(null);
    }
  }

  const colunas = useMemo<ColumnDef<UsuarioAdmin>[]>(
    () => [
      {
        id: "email",
        header: "Email",
        accessorFn: (a) => a.email,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 font-medium text-t1" },
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => <StatusBadge status={row.original.ativo ? "ATIVO" : "INATIVO"} />,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
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
          const a = row.original;
          return (
            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => setModalAberto({ administrador: a })}
                className="text-prx hover:underline"
              >
                Editar
              </button>
              <button
                type="button"
                disabled={resetando === a.id}
                onClick={() => onResetarSenha(a)}
                className="text-prx hover:underline disabled:opacity-50"
              >
                {resetando === a.id ? "Resetando..." : "Resetar senha"}
              </button>
            </div>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
    ],
    [resetando],
  );

  const table = useReactTable({
    data: administradores ?? [],
    columns: colunas,
    getRowId: (a) => a.id,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-4xl flex-1 px-6 py-8">
        <AdminSubNav />

        <div className="mb-6 mt-6 flex items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-t1">Administradores</h1>
            <p className="mt-1 text-sm text-t2">Usuários ADMIN_PRX — acesso ao painel e à navegação multi-tenant.</p>
          </div>
          <button
            type="button"
            onClick={() => setModalAberto({ administrador: null })}
            className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l"
          >
            + Novo administrador
          </button>
        </div>

        {erro && <p className="mb-4 text-sm text-er">{erro}</p>}
        {erroReset && <p className="mb-4 text-sm text-er">{erroReset}</p>}

        <DataGrid
          table={table}
          wrapperClassName="overflow-hidden rounded-lg border border-bdr"
          tableClassName="w-full text-sm"
          theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
          tbodyClassName="divide-y divide-bdr"
          loading={administradores === null}
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
                Nenhum administrador cadastrado ainda.
              </td>
            </tr>
          }
        />
      </main>

      {modalAberto && (
        <AdministradorFormModal
          open={modalAberto !== null}
          onClose={() => setModalAberto(null)}
          administrador={modalAberto.administrador}
          onSalvo={onSalvo}
        />
      )}
      <SenhaGeradaModal senha={senhaGerada} onClose={() => setSenhaGerada(null)} />
    </>
  );
}

export default function AdministradoresPage() {
  return (
    <AuthGuard papelRequerido="ADMIN_PRX">
      <AdministradoresContent />
    </AuthGuard>
  );
}
