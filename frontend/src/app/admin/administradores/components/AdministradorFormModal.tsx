"use client";

import { FormEvent, useState } from "react";
import Modal from "@/components/Modal";
import { atualizarAdministrador, criarAdministrador } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { UsuarioAdmin, UsuarioAdminRequest } from "@/lib/types";

interface Props {
  open: boolean;
  onClose: () => void;
  administrador?: UsuarioAdmin | null;
  onSalvo: (administrador: UsuarioAdmin) => void;
}

// Mesmo padrão de UsuarioFormModal (admin/[tenantId]): form isolado, remontado via
// `key` no Modal toda vez que abre.
function AdministradorForm({
  administrador,
  onClose,
  onSalvo,
}: {
  administrador?: UsuarioAdmin | null;
  onClose: () => void;
  onSalvo: (administrador: UsuarioAdmin) => void;
}) {
  const [email, setEmail] = useState(administrador?.email ?? "");
  const [ativo, setAtivo] = useState(administrador?.ativo ?? true);
  const [definirSenha, setDefinirSenha] = useState(false);
  const [senha, setSenha] = useState("");
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!email.trim()) return;
    setSalvando(true);
    setErro(null);
    try {
      const dados: UsuarioAdminRequest = {
        email,
        ativo,
        senha: definirSenha && senha ? senha : null,
      };
      const salvo = administrador
        ? await atualizarAdministrador(administrador.id, dados)
        : await criarAdministrador(dados);
      onSalvo(salvo);
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível salvar o administrador."));
    } finally {
      setSalvando(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      {erro && <p className="text-sm text-er">{erro}</p>}
      <div>
        <label className="text-xs font-medium text-t2">Email</label>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <label className="flex items-center gap-2 text-sm text-t2">
        <input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} />
        Ativo
      </label>
      <label className="flex items-center gap-2 text-sm text-t2">
        <input type="checkbox" checked={definirSenha} onChange={(e) => setDefinirSenha(e.target.checked)} />
        {administrador ? "Redefinir senha manualmente" : "Definir senha manualmente"}
      </label>
      {definirSenha && (
        <div>
          <input
            type="text"
            value={senha}
            onChange={(e) => setSenha(e.target.value)}
            placeholder="Senha do administrador"
            required
            minLength={8}
            className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
          />
          <p className="mt-1 text-xs text-t3">Mínimo de 8 caracteres.</p>
        </div>
      )}
      {!definirSenha && !administrador && (
        <p className="text-xs text-t3">Uma senha será gerada automaticamente e mostrada uma única vez após salvar.</p>
      )}
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

export default function AdministradorFormModal({ open, onClose, administrador, onSalvo }: Props) {
  return (
    <Modal open={open} onClose={onClose} title={administrador ? "Editar administrador" : "Novo administrador"}>
      {open && (
        <AdministradorForm key={administrador?.id ?? "novo"} administrador={administrador} onClose={onClose} onSalvo={onSalvo} />
      )}
    </Modal>
  );
}
