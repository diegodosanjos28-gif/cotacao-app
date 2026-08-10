"use client";

import { FormEvent, useState } from "react";
import Modal from "@/components/Modal";
import { criarMeuTelefone } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { UsuarioTelefone } from "@/lib/types";

// Mesmo formato exigido pelo backend (UsuarioTelefoneRequest): "+" opcional seguido
// de 7 a 15 dígitos, sem espaços/traços — o backend normaliza (remove "+", e o nono
// dígito de celular BR se presente) antes de gravar, então tanto "+5511987654321"
// quanto "5511987654321" são aceitos aqui.
const REGEX_E164 = /^\+?[1-9]\d{6,14}$/;

interface Props {
  open: boolean;
  onClose: () => void;
  onCriado: (telefone: UsuarioTelefone) => void;
}

// Mesmo padrão de UsuarioFormModal: form isolado, remontado via `key` no Modal toda
// vez que abre.
function TelefoneForm({ onClose, onCriado }: { onClose: () => void; onCriado: (t: UsuarioTelefone) => void }) {
  const [numeroWhatsapp, setNumeroWhatsapp] = useState("");
  const [nomeContato, setNomeContato] = useState("");
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!REGEX_E164.test(numeroWhatsapp)) {
      setErro("Número inválido. Use o formato internacional (DDI + DDD + número), com ou sem o +, ex: 5511987654321.");
      return;
    }
    setSalvando(true);
    setErro(null);
    try {
      const criado = await criarMeuTelefone({
        numeroWhatsapp,
        nomeContato: nomeContato.trim() || null,
      });
      onCriado(criado);
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível cadastrar o número."));
    } finally {
      setSalvando(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      {erro && <p className="text-sm text-er">{erro}</p>}
      <div>
        <label className="text-xs font-medium text-t2">Número do WhatsApp</label>
        <input
          type="text"
          value={numeroWhatsapp}
          onChange={(e) => setNumeroWhatsapp(e.target.value)}
          placeholder="5511987654321"
          required
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
        <p className="mt-1 text-xs text-t3">Formato internacional, com código do país. O "+" é opcional.</p>
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Nome (opcional)</label>
        <input
          type="text"
          value={nomeContato}
          onChange={(e) => setNomeContato(e.target.value)}
          placeholder="Ex: Loja, Gerente"
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

export default function TelefoneFormModal({ open, onClose, onCriado }: Props) {
  return (
    <Modal open={open} onClose={onClose} title="Adicionar número">
      {open && <TelefoneForm onClose={onClose} onCriado={onCriado} />}
    </Modal>
  );
}
