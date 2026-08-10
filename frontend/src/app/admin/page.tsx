"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatusBadge from "@/components/StatusBadge";
import { listarTenants } from "@/lib/api";
import { formatarData } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";
import { Tenant } from "@/lib/types";
import AdminSubNav from "./components/AdminSubNav";
import TenantFormModal from "./components/TenantFormModal";

const TH_CLASSE = "px-4 py-3 font-medium";
const TD_CLASSE = "px-4 py-3 text-t2";

function IconSearch() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8" />
      <path d="M21 21l-4.35-4.35" />
    </svg>
  );
}

function AdminContent() {
  const router = useRouter();
  const { data: tenants, erro, setData: setTenants } = useAsync(
    () => listarTenants(),
    [],
    "Não foi possível carregar os tenants.",
  );
  const [busca, setBusca] = useState("");
  const [modalAberto, setModalAberto] = useState(false);

  const todos = tenants ?? [];
  const buscaNorm = busca.trim().toLowerCase();
  const listaExibida = buscaNorm
    ? todos.filter(
        (t) =>
          t.nomeFantasia.toLowerCase().includes(buscaNorm) ||
          (t.razaoSocial ?? "").toLowerCase().includes(buscaNorm) ||
          (t.cnpj ?? "").toLowerCase().includes(buscaNorm),
      )
    : todos;

  function onTenantCriado(tenant: Tenant) {
    setTenants((atual) => [...(atual ?? []), tenant]);
  }

  const colunas = useMemo<ColumnDef<Tenant>[]>(
    () => [
      {
        id: "nomeFantasia",
        header: "Nome fantasia",
        accessorFn: (t) => t.nomeFantasia,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 font-medium text-t1" },
      },
      {
        id: "cnpj",
        header: "CNPJ",
        cell: ({ row }) => row.original.cnpj ?? "—",
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
      {
        id: "plano",
        header: "Plano",
        cell: ({ row }) => row.original.plano ?? "—",
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
      {
        id: "criadoEm",
        header: "Criado em",
        cell: ({ row }) => formatarData(row.original.criadoEm),
        meta: { headerClassName: TH_CLASSE, cellClassName: TD_CLASSE },
      },
    ],
    [],
  );

  const table = useReactTable({
    data: listaExibida,
    columns: colunas,
    getRowId: (t) => t.id,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
        <AdminSubNav />

        <div className="mb-6 mt-6 flex items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-t1">Admin PRX</h1>
            <p className="mt-1 text-sm text-t2">Cadastro de tenants (clientes da PRX) e seus usuários.</p>
          </div>
          <button
            type="button"
            onClick={() => setModalAberto(true)}
            className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l"
          >
            + Novo tenant
          </button>
        </div>

        {erro && <p className="mb-4 text-sm text-er">{erro}</p>}

        <div className="relative max-w-lg">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-t3">
            <IconSearch />
          </span>
          <input
            type="text"
            value={busca}
            onChange={(e) => setBusca(e.target.value)}
            placeholder="Buscar por nome fantasia, razão social ou CNPJ..."
            className="w-full rounded-md border border-bdr bg-card py-2 pl-9 pr-3 text-sm text-t1 shadow-[0_1px_4px_rgba(37,34,32,.06)] placeholder:text-t3 focus:border-prx focus:outline-none focus:ring-2 focus:ring-prx/20"
          />
        </div>

        <DataGrid
          table={table}
          wrapperClassName="mt-4 overflow-hidden rounded-lg border border-bdr"
          tableClassName="w-full text-sm"
          theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
          tbodyClassName="divide-y divide-bdr"
          rowClassName="cursor-pointer hover:bg-hov"
          onRowClick={(t) => router.push(`/admin/${t.id}`)}
          loading={tenants === null}
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
                {todos.length === 0 ? "Nenhum tenant cadastrado ainda." : "Nenhum tenant encontrado para esta busca."}
              </td>
            </tr>
          }
        />
      </main>

      <TenantFormModal open={modalAberto} onClose={() => setModalAberto(false)} onSalvo={onTenantCriado} />
    </>
  );
}

export default function AdminPage() {
  return (
    <AuthGuard papelRequerido="ADMIN_PRX">
      <AdminContent />
    </AuthGuard>
  );
}
