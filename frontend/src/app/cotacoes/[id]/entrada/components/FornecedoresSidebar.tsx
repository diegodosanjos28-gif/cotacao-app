"use client";

import { useState } from "react";
import Card from "@/components/Card";
import { inativarFornecedor } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda } from "@/lib/format";
import { Fornecedor } from "@/lib/types";
import FornecedorFormModal from "./FornecedorFormModal";

interface Props {
  fornecedores: Fornecedor[];
  onFornecedorSalvo: (fornecedor: Fornecedor) => void;
  onFornecedorInativado: (id: string) => void;
}

// Paleta cíclica só pra dar identidade visual rápida a cada card (barra lateral) —
// mesmo espírito do SR_COLORS do protótipo, sem persistir cor por fornecedor.
const CORES_BARRA = ["#FF8000", "#47C7FC", "#8B5CF6", "#F59E0B", "#EF4444", "#10B981"];

export default function FornecedoresSidebar({ fornecedores, onFornecedorSalvo, onFornecedorInativado }: Props) {
  const [modalAberto, setModalAberto] = useState(false);
  const [editando, setEditando] = useState<Fornecedor | null>(null);
  const [inativando, setInativando] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  async function onInativar(f: Fornecedor) {
    if (!window.confirm(`Inativar "${f.nome}"? Ele deixa de aparecer para novas cotações.`)) return;
    setInativando(f.id);
    setErro(null);
    try {
      await inativarFornecedor(f.id);
      onFornecedorInativado(f.id);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível inativar o fornecedor."));
    } finally {
      setInativando(null);
    }
  }

  return (
    <Card className="flex h-full flex-col">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold text-t1">
          Fornecedores{" "}
          <span className="ml-1 rounded-full bg-hov px-2 py-0.5 text-xs font-medium text-t2">
            {fornecedores.length}
          </span>
        </h2>
        <button
          onClick={() => setModalAberto(true)}
          className="text-sm font-medium text-prx hover:text-prx-l"
        >
          + Novo
        </button>
      </div>

      {erro && <p className="mt-2 text-xs text-er">{erro}</p>}

      {/* flex-1 estica o card pra acompanhar a altura do grid ao lado quando este
          tem mais conteúdo (entrada/page.tsx) — mas max-h-[420px] trava um teto,
          senão uma lista de produtos muito longa faria este card crescer sem
          limite junto (achado do usuário: "fornecedores infinito"). */}
      <div className="mt-4 min-h-[180px] max-h-[420px] flex-1 space-y-2.5 overflow-y-auto pr-0.5">
        {fornecedores.map((f, i) => (
          <div key={f.id} className="flex gap-2.5 rounded-md border border-bdr bg-surf p-2.5">
            <span className="w-1 shrink-0 rounded-full" style={{ background: CORES_BARRA[i % CORES_BARRA.length] }} />
            <div className="min-w-0 flex-1">
              <p className="flex items-center gap-1.5 text-sm font-semibold text-t1">
                {f.nome}
                {f.status === "PENDENTE_DADOS" && (
                  <span
                    className="text-wa"
                    role="img"
                    aria-label="Fornecedor criado automaticamente — complete os dados"
                    title="Fornecedor criado automaticamente — complete os dados"
                  >
                    ⚠
                  </span>
                )}
              </p>
              <p className="mt-1 flex flex-wrap gap-x-2.5 gap-y-0.5 text-[11px] text-t2">
                <span>🕐 {f.prazoEntregaPadrao ?? "—"}</span>
                <span>📄 {f.condicaoPagamentoPadrao ?? "—"}</span>
                <span>{formatarMoeda(f.pedidoMinimoPadrao)} min.</span>
              </p>
              <div className="mt-1.5 flex gap-3">
                <button
                  type="button"
                  onClick={() => setEditando(f)}
                  className="text-xs font-medium text-prx hover:text-prx-l"
                >
                  Editar
                </button>
                <button
                  type="button"
                  onClick={() => onInativar(f)}
                  disabled={inativando === f.id}
                  className="text-xs font-medium text-t3 hover:text-er disabled:opacity-50"
                >
                  {inativando === f.id ? "Inativando..." : "Inativar"}
                </button>
              </div>
            </div>
          </div>
        ))}
        {fornecedores.length === 0 && (
          <span className="text-sm text-t2">Nenhum fornecedor cadastrado ainda.</span>
        )}
      </div>

      <FornecedorFormModal open={modalAberto} onClose={() => setModalAberto(false)} onSalvo={onFornecedorSalvo} />
      <FornecedorFormModal
        open={editando !== null}
        onClose={() => setEditando(null)}
        fornecedor={editando}
        onSalvo={(f) => {
          onFornecedorSalvo(f);
          setEditando(null);
        }}
      />
    </Card>
  );
}
