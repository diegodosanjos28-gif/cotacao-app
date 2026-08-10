"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatusBadge from "@/components/StatusBadge";
import { listarTenants, selecionarTenant } from "@/lib/api";
import { setTokens } from "@/lib/auth";
import { clearCotacaoAtivaId } from "@/lib/cotacaoAtiva";
import { getErrorMessage } from "@/lib/errors";
import { useAsync } from "@/hooks/useAsync";
import { Tenant } from "@/lib/types";

const TH_CLASSE = "px-4 py-3 font-medium";

function IconSearch() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="8" />
      <path d="M21 21l-4.35-4.35" />
    </svg>
  );
}

function SelecionarTenantContent() {
  const router = useRouter();
  const { data: tenants, erro: erroCarregar } = useAsync(
    () => listarTenants(),
    [],
    "Não foi possível carregar os tenants.",
  );
  const [busca, setBusca] = useState("");
  const [selecionando, setSelecionando] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);

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

  // "id" fica null pro atalho "Painel administrativo" e o id do tenant nos demais —
  // um único handler cobre os dois casos de selecionarTenant(tenantId | null).
  async function onSelecionar(tenantId: string | null) {
    setSelecionando(tenantId ?? "__admin__");
    setErro(null);
    try {
      const tokens = await selecionarTenant(tenantId);
      setTokens(tokens.accessToken, tokens.refreshToken);
      // Trocar de tenant é uma identidade de navegação nova — qualquer cotação
      // "ativa" lembrada da sessão anterior não é válida no novo contexto.
      clearCotacaoAtivaId();
      router.push(tenantId ? "/" : "/admin");
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível selecionar o tenant."));
      setSelecionando(null);
    }
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
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
      {
        id: "navegar",
        header: "",
        cell: ({ row }) => (selecionando === row.original.id ? "Entrando..." : "Navegar →"),
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 text-right text-xs text-prx" },
      },
    ],
    [selecionando],
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
      <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col px-6 py-10">
      <div className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-t1">Selecionar tenant</h1>
        <p className="mt-1 text-sm text-t2">
          Escolha em qual tenant você quer navegar. Você pode trocar a qualquer momento pelo menu.
        </p>
      </div>

      {(erro ?? erroCarregar) && <p className="mb-4 text-sm text-er">{erro ?? erroCarregar}</p>}

      <button
        type="button"
        onClick={() => onSelecionar(null)}
        disabled={selecionando !== null}
        className="mb-4 flex items-center justify-between rounded-lg border border-bdr bg-card px-4 py-3 text-left text-sm shadow-[0_1px_4px_rgba(37,34,32,.06)] hover:bg-hov disabled:opacity-50"
      >
        <span className="font-medium text-t1">Painel administrativo (nenhum tenant)</span>
        <span className="text-xs text-t3">
          {selecionando === "__admin__" ? "Entrando..." : "Gerenciar tenants e administradores"}
        </span>
      </button>

      <div className="relative mb-4">
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
        wrapperClassName="overflow-hidden rounded-lg border border-bdr"
        tableClassName="w-full text-sm"
        theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
        tbodyClassName="divide-y divide-bdr"
        rowClassName={() => `cursor-pointer hover:bg-hov ${selecionando !== null ? "pointer-events-none opacity-50" : ""}`}
        onRowClick={(t) => {
          if (selecionando === null) onSelecionar(t.id);
        }}
        loading={tenants === null}
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
              {todos.length === 0 ? "Nenhum tenant cadastrado ainda." : "Nenhum tenant encontrado para esta busca."}
            </td>
          </tr>
        }
      />
      </main>
    </>
  );
}

export default function SelecionarTenantPage() {
  return (
    <AuthGuard papelRequerido="ADMIN_PRX">
      <SelecionarTenantContent />
    </AuthGuard>
  );
}
