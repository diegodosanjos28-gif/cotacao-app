"use client";

import { use, useMemo, useState } from "react";
import Link from "next/link";
import { ColumnDef, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import Card from "@/components/Card";
import Modal from "@/components/Modal";
import StatusBadge from "@/components/StatusBadge";
import {
  buscarTenant,
  listarAcoesCliente,
  listarTemplatesMensagem,
  listarUsuariosDoTenant,
  resetarSenhaUsuario,
} from "@/lib/api";
import { formatarData } from "@/lib/format";
import { getErrorMessage } from "@/lib/errors";
import { useAsync } from "@/hooks/useAsync";
import { AcaoCliente, ResultadoAcaoCliente, TemplateMensagem, UsuarioAdmin } from "@/lib/types";
import TenantFormModal from "../components/TenantFormModal";
import UsuarioFormModal from "./components/UsuarioFormModal";
import TemplateMensagemFormModal from "./components/TemplateMensagemFormModal";

const TH_CLASSE = "px-4 py-3 font-medium";

const LABEL_RESULTADO: Record<string, string> = {
  SUCESSO: "Sucesso",
  ERRO: "Erro",
};

function labelResultado(resultado: ResultadoAcaoCliente): string {
  return resultado ? LABEL_RESULTADO[resultado] : "—";
}

interface VagaTemplate {
  acaoCliente: AcaoCliente;
  existente: TemplateMensagem | null;
}

function SenhaGeradaModal({ senha, onClose }: { senha: string | null; onClose: () => void }) {
  return (
    <Modal open={senha !== null} onClose={onClose} title="Senha gerada">
      <p className="text-sm text-t2">
        Copie a senha abaixo agora — ela não será mostrada novamente. Repasse ao cliente por fora do sistema
        (WhatsApp/telefone).
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

function TenantDetalheContent({ tenantId }: { tenantId: string }) {
  const {
    data: tenant,
    erro: erroTenant,
    setData: setTenant,
  } = useAsync(() => buscarTenant(tenantId), [tenantId], "Não foi possível carregar o tenant.");
  const {
    data: usuarios,
    erro: erroUsuarios,
    setData: setUsuarios,
  } = useAsync(() => listarUsuariosDoTenant(tenantId), [tenantId], "Não foi possível carregar os usuários.");
  const {
    data: templates,
    erro: erroTemplates,
    setData: setTemplates,
  } = useAsync(() => listarTemplatesMensagem(tenantId), [tenantId], "Não foi possível carregar os templates.");
  const { data: acoesCliente, erro: erroAcoesCliente } = useAsync(
    () => listarAcoesCliente(),
    [],
    "Não foi possível carregar as ações do cliente.",
  );

  const [abaAtiva, setAbaAtiva] = useState<"usuarios" | "templates">("usuarios");
  const [editarTenantAberto, setEditarTenantAberto] = useState(false);
  const [modalUsuario, setModalUsuario] = useState<{ usuario: UsuarioAdmin | null } | null>(null);
  const [modalTemplate, setModalTemplate] = useState<VagaTemplate | null>(null);
  const [senhaGerada, setSenhaGerada] = useState<string | null>(null);
  const [resetando, setResetando] = useState<string | null>(null);
  const [erroReset, setErroReset] = useState<string | null>(null);

  function onUsuarioSalvo(usuario: UsuarioAdmin) {
    setUsuarios((atual) => {
      const lista = atual ?? [];
      const existe = lista.some((u) => u.id === usuario.id);
      return existe ? lista.map((u) => (u.id === usuario.id ? usuario : u)) : [usuario, ...lista];
    });
    if (usuario.senhaGerada) setSenhaGerada(usuario.senhaGerada);
  }

  function onTemplateSalvo(template: TemplateMensagem) {
    setTemplates((atual) => {
      const lista = atual ?? [];
      const existe = lista.some((t) => t.id === template.id);
      return existe ? lista.map((t) => (t.id === template.id ? template : t)) : [template, ...lista];
    });
  }

  async function onResetarSenha(usuario: UsuarioAdmin) {
    setResetando(usuario.id);
    setErroReset(null);
    try {
      const { senha } = await resetarSenhaUsuario(tenantId, usuario.id);
      setSenhaGerada(senha);
    } catch (err) {
      setErroReset(getErrorMessage(err, "Não foi possível resetar a senha."));
    } finally {
      setResetando(null);
    }
  }

  const colunasUsuarios = useMemo<ColumnDef<UsuarioAdmin>[]>(
    () => [
      {
        id: "email",
        header: "Email",
        accessorFn: (u) => u.email,
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
          const u = row.original;
          return (
            <div className="flex gap-3">
              <button type="button" onClick={() => setModalUsuario({ usuario: u })} className="text-prx hover:underline">
                Editar
              </button>
              <button
                type="button"
                disabled={resetando === u.id}
                onClick={() => onResetarSenha(u)}
                className="text-prx hover:underline disabled:opacity-50"
              >
                {resetando === u.id ? "Resetando..." : "Resetar senha"}
              </button>
            </div>
          );
        },
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
    ],
    [resetando],
  );

  const tableUsuarios = useReactTable({
    data: usuarios ?? [],
    columns: colunasUsuarios,
    getRowId: (u) => u.id,
    getCoreRowModel: getCoreRowModel(),
  });

  const vagasTemplate = useMemo<VagaTemplate[]>(
    () =>
      (acoesCliente ?? []).map((acaoCliente) => ({
        acaoCliente,
        existente: templates?.find((t) => t.acaoClienteId === acaoCliente.id) ?? null,
      })),
    [acoesCliente, templates],
  );

  const colunasTemplates = useMemo<ColumnDef<VagaTemplate>[]>(
    () => [
      {
        id: "evento",
        header: "Evento",
        cell: ({ row }) => row.original.acaoCliente.descricao,
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "resultado",
        header: "Resultado",
        cell: ({ row }) => labelResultado(row.original.acaoCliente.resultado),
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 font-medium text-t1" },
      },
      {
        id: "nomeTemplateMeta",
        header: "Nome do template (Meta)",
        cell: ({ row }) => row.original.existente?.nomeTemplateMeta ?? "— não configurado —",
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
      {
        id: "idioma",
        header: "Idioma",
        cell: ({ row }) => row.original.existente?.idioma ?? "—",
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3 text-t2" },
      },
      {
        id: "status",
        header: "Status",
        cell: ({ row }) =>
          row.original.existente ? (
            <StatusBadge status={row.original.existente.ativo ? "ATIVO" : "INATIVO"} />
          ) : (
            <span className="text-t3">—</span>
          ),
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
      {
        id: "acoes",
        header: "Ações",
        cell: ({ row }) => (
          <button
            type="button"
            onClick={() => setModalTemplate(row.original)}
            className="text-prx hover:underline"
          >
            {row.original.existente ? "Editar" : "Configurar"}
          </button>
        ),
        meta: { headerClassName: TH_CLASSE, cellClassName: "px-4 py-3" },
      },
    ],
    [],
  );

  const tableTemplates = useReactTable({
    data: vagasTemplate,
    columns: colunasTemplates,
    getRowId: (v) => v.acaoCliente.id,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-4xl flex-1 px-6 py-8">
        <Link href="/admin" className="text-sm text-t2 hover:text-prx">
          ← Voltar para tenants
        </Link>

        {erroTenant && <p className="mt-4 text-sm text-er">{erroTenant}</p>}

        {tenant && (
          <Card className="mt-4">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-t1">{tenant.nomeFantasia}</h1>
                <p className="mt-1 text-sm text-t2">{tenant.razaoSocial || "Sem razão social cadastrada"}</p>
              </div>
              <button
                type="button"
                onClick={() => setEditarTenantAberto(true)}
                className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
              >
                Editar
              </button>
            </div>
            <dl className="mt-4 grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
              <div>
                <dt className="text-xs uppercase tracking-wide text-t3">Status</dt>
                <dd className="mt-1">
                  <StatusBadge status={tenant.status} />
                </dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-wide text-t3">CNPJ</dt>
                <dd className="mt-1 text-t1">{tenant.cnpj ?? "—"}</dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-wide text-t3">Plano</dt>
                <dd className="mt-1 text-t1">{tenant.plano ?? "—"}</dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-wide text-t3">Criado em</dt>
                <dd className="mt-1 text-t1">{formatarData(tenant.criadoEm)}</dd>
              </div>
            </dl>
          </Card>
        )}

        <div className="mt-8 flex gap-2 border-b border-bdr">
          <button
            type="button"
            onClick={() => setAbaAtiva("usuarios")}
            className={`px-4 py-2 text-sm font-medium ${
              abaAtiva === "usuarios" ? "border-b-2 border-prx text-prx" : "text-t2 hover:text-t1"
            }`}
          >
            Usuários
          </button>
          <button
            type="button"
            onClick={() => setAbaAtiva("templates")}
            className={`px-4 py-2 text-sm font-medium ${
              abaAtiva === "templates" ? "border-b-2 border-prx text-prx" : "text-t2 hover:text-t1"
            }`}
          >
            Templates de Mensagens
          </button>
        </div>

        {abaAtiva === "usuarios" && (
          <>
            <div className="mt-4 flex items-center justify-end gap-4">
              <button
                type="button"
                onClick={() => setModalUsuario({ usuario: null })}
                className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l"
              >
                + Novo usuário
              </button>
            </div>

            {erroUsuarios && <p className="mt-2 text-sm text-er">{erroUsuarios}</p>}
            {erroReset && <p className="mt-2 text-sm text-er">{erroReset}</p>}

            <DataGrid
              table={tableUsuarios}
              wrapperClassName="mt-4 overflow-hidden rounded-lg border border-bdr"
              tableClassName="w-full text-sm"
              theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
              tbodyClassName="divide-y divide-bdr"
              loading={usuarios === null}
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
                    Nenhum usuário cadastrado neste tenant ainda.
                  </td>
                </tr>
              }
            />
          </>
        )}

        {abaAtiva === "templates" && (
          <>
            <p className="mt-4 text-sm text-t2">
              Confirmação enviada por WhatsApp após cada mensagem recebida. A vaga &quot;Não identificado&quot; serve
              de fallback universal (usada quando não há um template específico configurado, e sempre usada para
              mensagens de formato não reconhecido). As outras 4 vagas são opcionais — configure-as se quiser
              personalizar a mensagem de Lista de Produtos e Resposta de Fornecedor separadamente, com parâmetros
              próprios de cada cenário.
            </p>

            {erroTemplates && <p className="mt-2 text-sm text-er">{erroTemplates}</p>}
            {erroAcoesCliente && <p className="mt-2 text-sm text-er">{erroAcoesCliente}</p>}

            <DataGrid
              table={tableTemplates}
              wrapperClassName="mt-4 overflow-hidden rounded-lg border border-bdr"
              tableClassName="w-full text-sm"
              theadClassName="bg-surf text-left text-xs uppercase tracking-wide text-t3"
              tbodyClassName="divide-y divide-bdr"
              loading={templates === null || acoesCliente === null}
              loadingContent={
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-t2">
                    Carregando...
                  </td>
                </tr>
              }
            />
          </>
        )}
      </main>

      {tenant && (
        <TenantFormModal
          open={editarTenantAberto}
          onClose={() => setEditarTenantAberto(false)}
          tenant={tenant}
          onSalvo={setTenant}
        />
      )}
      {modalUsuario && (
        <UsuarioFormModal
          open={modalUsuario !== null}
          onClose={() => setModalUsuario(null)}
          tenantId={tenantId}
          usuario={modalUsuario.usuario}
          onSalvo={onUsuarioSalvo}
        />
      )}
      {modalTemplate && (
        <TemplateMensagemFormModal
          open={modalTemplate !== null}
          onClose={() => setModalTemplate(null)}
          tenantId={tenantId}
          acaoCliente={modalTemplate.acaoCliente}
          template={modalTemplate.existente}
          onSalvo={onTemplateSalvo}
        />
      )}
      <SenhaGeradaModal senha={senhaGerada} onClose={() => setSenhaGerada(null)} />
    </>
  );
}

export default function TenantDetalhePage({ params }: { params: Promise<{ tenantId: string }> }) {
  const { tenantId } = use(params);
  return (
    <AuthGuard papelRequerido="ADMIN_PRX">
      <TenantDetalheContent tenantId={tenantId} />
    </AuthGuard>
  );
}
