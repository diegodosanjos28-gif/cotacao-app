"use client";

import { FormEvent, useState } from "react";
import Modal from "@/components/Modal";
import { atualizarTenant, criarTenant } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { Tenant, TenantRequest, TenantStatus } from "@/lib/types";

interface Props {
  open: boolean;
  onClose: () => void;
  tenant?: Tenant | null;
  onSalvo: (tenant: Tenant) => void;
}

const STATUS_OPCOES: TenantStatus[] = ["TRIAL", "ATIVO", "SUSPENSO"];

function dadosIniciais(tenant?: Tenant | null): TenantRequest {
  if (!tenant) {
    return { nomeFantasia: "", razaoSocial: "", cnpj: "", status: "TRIAL", plano: "" };
  }
  return {
    nomeFantasia: tenant.nomeFantasia,
    razaoSocial: tenant.razaoSocial ?? "",
    cnpj: tenant.cnpj ?? "",
    status: tenant.status,
    plano: tenant.plano ?? "",
  };
}

// Mesmo padrão de FornecedorFormModal: form isolado, remontado via `key` no Modal.
function TenantForm({
  tenant,
  onClose,
  onSalvo,
}: {
  tenant?: Tenant | null;
  onClose: () => void;
  onSalvo: (tenant: Tenant) => void;
}) {
  const [dados, setDados] = useState<TenantRequest>(() => dadosIniciais(tenant));
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!dados.nomeFantasia.trim()) return;
    setSalvando(true);
    setErro(null);
    try {
      const salvo = tenant ? await atualizarTenant(tenant.id, dados) : await criarTenant(dados);
      onSalvo(salvo);
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível salvar o tenant."));
    } finally {
      setSalvando(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      {erro && <p className="text-sm text-er">{erro}</p>}
      <div>
        <label className="text-xs font-medium text-t2">Nome fantasia</label>
        <input
          value={dados.nomeFantasia}
          onChange={(e) => setDados((d) => ({ ...d, nomeFantasia: e.target.value }))}
          required
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Razão social</label>
        <input
          value={dados.razaoSocial ?? ""}
          onChange={(e) => setDados((d) => ({ ...d, razaoSocial: e.target.value }))}
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="text-xs font-medium text-t2">CNPJ</label>
          <input
            value={dados.cnpj ?? ""}
            onChange={(e) => setDados((d) => ({ ...d, cnpj: e.target.value }))}
            placeholder="opcional"
            className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-t2">Status</label>
          <select
            value={dados.status ?? "TRIAL"}
            onChange={(e) => setDados((d) => ({ ...d, status: e.target.value as TenantStatus }))}
            className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
          >
            {STATUS_OPCOES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Plano</label>
        <input
          value={dados.plano ?? ""}
          onChange={(e) => setDados((d) => ({ ...d, plano: e.target.value }))}
          placeholder="opcional — preparo para cobrança futura"
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div className="flex justify-end gap-2 pt-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
        >
          Cancelar
        </button>
        <button
          type="submit"
          disabled={salvando}
          className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
        >
          {salvando ? "Salvando..." : "Salvar"}
        </button>
      </div>
    </form>
  );
}

export default function TenantFormModal({ open, onClose, tenant, onSalvo }: Props) {
  return (
    <Modal open={open} onClose={onClose} title={tenant ? "Editar tenant" : "Novo tenant"}>
      {open && <TenantForm key={tenant?.id ?? "novo"} tenant={tenant} onClose={onClose} onSalvo={onSalvo} />}
    </Modal>
  );
}
