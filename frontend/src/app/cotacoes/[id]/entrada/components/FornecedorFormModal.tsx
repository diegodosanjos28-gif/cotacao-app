"use client";

import { FormEvent, useState } from "react";
import Modal from "@/components/Modal";
import { atualizarFornecedor, criarFornecedor } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { Fornecedor, FornecedorRequest } from "@/lib/types";

interface Props {
  open: boolean;
  onClose: () => void;
  fornecedor?: Fornecedor | null;
  nomeInicial?: string;
  onSalvo: (fornecedor: Fornecedor) => void;
}

function dadosIniciais(fornecedor?: Fornecedor | null, nomeInicial?: string): FornecedorRequest {
  if (!fornecedor) {
    return {
      nome: nomeInicial?.trim() ?? "",
      prazoEntregaPadrao: "",
      condicaoPagamentoPadrao: "",
      pedidoMinimoPadrao: null,
      observacoesPadrao: "",
    };
  }
  return {
    nome: fornecedor.nome,
    prazoEntregaPadrao: fornecedor.prazoEntregaPadrao ?? "",
    condicaoPagamentoPadrao: fornecedor.condicaoPagamentoPadrao ?? "",
    pedidoMinimoPadrao: fornecedor.pedidoMinimoPadrao,
    observacoesPadrao: fornecedor.observacoesPadrao ?? "",
  };
}

// Formulário isolado num componente próprio, remontado via `key` no Modal toda vez
// que abre — evita sincronizar `dados` com a prop `fornecedor` via useEffect
// (cascading render desnecessário; o estado já nasce correto no mount).
function FornecedorForm({
  fornecedor,
  nomeInicial,
  onClose,
  onSalvo,
}: {
  fornecedor?: Fornecedor | null;
  nomeInicial?: string;
  onClose: () => void;
  onSalvo: (fornecedor: Fornecedor) => void;
}) {
  const [dados, setDados] = useState<FornecedorRequest>(() => dadosIniciais(fornecedor, nomeInicial));
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!dados.nome.trim()) return;
    setSalvando(true);
    setErro(null);
    try {
      const salvo = fornecedor ? await atualizarFornecedor(fornecedor.id, dados) : await criarFornecedor(dados);
      onSalvo(salvo);
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível salvar o fornecedor."));
    } finally {
      setSalvando(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
      {erro && <p className="text-sm text-er">{erro}</p>}
      <div>
        <label className="text-xs font-medium text-t2">Nome</label>
        <input
          value={dados.nome}
          onChange={(e) => setDados((d) => ({ ...d, nome: e.target.value }))}
          required
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="text-xs font-medium text-t2">Prazo de entrega</label>
          <input
            value={dados.prazoEntregaPadrao ?? ""}
            onChange={(e) => setDados((d) => ({ ...d, prazoEntregaPadrao: e.target.value }))}
            placeholder="ex: 3 dias úteis"
            className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-t2">Condição de pagamento</label>
          <input
            value={dados.condicaoPagamentoPadrao ?? ""}
            onChange={(e) => setDados((d) => ({ ...d, condicaoPagamentoPadrao: e.target.value }))}
            placeholder="ex: 28 dias"
            className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
          />
        </div>
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Pedido mínimo (R$)</label>
        <input
          type="number"
          min={0}
          step="0.01"
          value={dados.pedidoMinimoPadrao ?? ""}
          onChange={(e) =>
            setDados((d) => ({ ...d, pedidoMinimoPadrao: e.target.value ? Number(e.target.value) : null }))
          }
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Observações</label>
        <textarea
          value={dados.observacoesPadrao ?? ""}
          onChange={(e) => setDados((d) => ({ ...d, observacoesPadrao: e.target.value }))}
          rows={2}
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

export default function FornecedorFormModal({ open, onClose, fornecedor, nomeInicial, onSalvo }: Props) {
  return (
    <Modal open={open} onClose={onClose} title={fornecedor ? "Editar fornecedor" : "Novo fornecedor"}>
      {open && (
        <FornecedorForm
          key={fornecedor?.id ?? nomeInicial ?? "novo"}
          fornecedor={fornecedor}
          nomeInicial={nomeInicial}
          onClose={onClose}
          onSalvo={onSalvo}
        />
      )}
    </Modal>
  );
}
