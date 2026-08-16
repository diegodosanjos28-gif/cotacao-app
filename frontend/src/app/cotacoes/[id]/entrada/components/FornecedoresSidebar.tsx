"use client";

import { useState } from "react";
import { inativarFornecedor } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { formatarMoeda } from "@/lib/format";
import { Fornecedor } from "@/lib/types";
import FornecedorFormModal from "./FornecedorFormModal";

interface Props {
  fornecedores: Fornecedor[];
  onFornecedorSalvo: (fornecedor: Fornecedor) => void;
  onFornecedorInativado: (id: string) => void;
  // Painel "Abrir fornecedores" dentro de FornecedoresCotacoesSection (Prompt 25):
  // renderiza direto dentro do card "Fornecedores e cotações" (sem Card próprio) —
  // além de gerenciar o catálogo, permite selecionar um fornecedor ainda não vinculado
  // à cotação atual e adicioná-lo a ela sem sair do painel.
  fornecedoresJaAdicionadosIds: string[];
  onAdicionarCotacao: (fornecedor: Fornecedor) => void;
  adicionando: boolean;
  // Gate existente (confirme a Conferência de um fornecedor pra liberar o próximo) —
  // continua valendo aqui, só que comunicado dentro do painel em vez de embaixo do card.
  podeAdicionar: boolean;
}

// Paleta cíclica só pra dar identidade visual rápida a cada card (barra lateral) —
// mesmo espírito do SR_COLORS do protótipo, sem persistir cor por fornecedor.
const CORES_BARRA = ["#FF8000", "#47C7FC", "#8B5CF6", "#F59E0B", "#EF4444", "#10B981"];

export default function FornecedoresSidebar({
  fornecedores,
  onFornecedorSalvo,
  onFornecedorInativado,
  fornecedoresJaAdicionadosIds,
  onAdicionarCotacao,
  adicionando,
  podeAdicionar,
}: Props) {
  const [modalAberto, setModalAberto] = useState(false);
  const [editando, setEditando] = useState<Fornecedor | null>(null);
  const [inativando, setInativando] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [selecionadoId, setSelecionadoId] = useState<string | null>(null);
  const [busca, setBusca] = useState("");

  const selecionado = fornecedores.find((f) => f.id === selecionadoId) ?? null;
  const fornecedoresFiltrados = fornecedores.filter((f) =>
    f.nome.toLowerCase().includes(busca.trim().toLowerCase()),
  );

  function onAdicionarClick() {
    if (selecionado && podeAdicionar) onAdicionarCotacao(selecionado);
  }

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
    <div>
      <div className="flex items-center gap-3">
        <input
          type="text"
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          placeholder="Buscar fornecedor por nome..."
          className="flex-1 rounded-md border border-bdr px-3 py-2 text-sm text-t1 placeholder:text-t3 focus:border-prx focus:outline-none"
        />
        <button
          type="button"
          onClick={() => setModalAberto(true)}
          className="shrink-0 rounded-md border border-prx px-3 py-2 text-sm font-medium text-prx hover:bg-prx/10"
        >
          + Novo
        </button>
      </div>

      {erro && <p className="mt-2 text-xs text-er">{erro}</p>}

      {/* Sem altura/scroll próprios (Prompt 25, feedback 2026-08-16) — a página já tem
          um único contêiner de scroll (entrada/page.tsx); um overflow-y-auto aqui
          dentro criava scroll aninhado. */}
      <div className="mt-3 space-y-2.5">
        {fornecedoresFiltrados.map((f, i) => {
          const jaAdicionado = fornecedoresJaAdicionadosIds.includes(f.id);
          const selecionavel = !jaAdicionado;
          const ativo = f.id === selecionadoId;
          return (
            <div
              key={f.id}
              onClick={selecionavel ? () => setSelecionadoId(ativo ? null : f.id) : undefined}
              className={`flex gap-2.5 rounded-md border p-2.5 ${selecionavel ? "cursor-pointer" : ""} ${
                ativo ? "border-prx bg-prx/5" : "border-bdr bg-surf"
              }`}
            >
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
                  {jaAdicionado && (
                    <span className="rounded-full bg-ok-d px-2 py-0.5 text-[10px] font-medium text-ok">
                      Já na cotação
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
                    onClick={(e) => {
                      e.stopPropagation();
                      setEditando(f);
                    }}
                    className="text-xs font-medium text-prx hover:text-prx-l"
                  >
                    Editar
                  </button>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onInativar(f);
                    }}
                    disabled={inativando === f.id}
                    className="text-xs font-medium text-t3 hover:text-er disabled:opacity-50"
                  >
                    {inativando === f.id ? "Inativando..." : "Inativar"}
                  </button>
                </div>
              </div>
            </div>
          );
        })}
        {fornecedoresFiltrados.length === 0 && (
          <span className="text-sm text-t2">
            {fornecedores.length === 0 ? "Nenhum fornecedor cadastrado ainda." : "Nenhum fornecedor encontrado."}
          </span>
        )}
      </div>

      <div className="mt-3 border-t border-bdr pt-3">
        {!podeAdicionar && (
          <p className="mb-2 text-xs text-t3">Processe a cotação atual para liberar o próximo fornecedor.</p>
        )}
        <button
          type="button"
          onClick={onAdicionarClick}
          disabled={!selecionado || adicionando || !podeAdicionar}
          className="w-full rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:cursor-not-allowed disabled:bg-bdr disabled:text-t3"
        >
          {adicionando ? "Adicionando..." : selecionado ? `Adicionar cotação — ${selecionado.nome}` : "Adicionar cotação"}
        </button>
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
    </div>
  );
}
