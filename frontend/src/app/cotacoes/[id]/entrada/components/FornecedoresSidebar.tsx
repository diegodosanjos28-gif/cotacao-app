"use client";

import { useState } from "react";
import ConfirmModal from "@/components/ConfirmModal";
import { inativarFornecedor } from "@/lib/api";
import { corFornecedor } from "@/lib/coresFornecedor";
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
  // Cotação finalizada (Prompt 27): painel vira consulta — sem seleção nem botão de
  // adicionar. Editar/Inativar/+ Novo continuam liberados: agem sobre o cadastro do
  // fornecedor (tenant), ortogonal ao ciclo de vida desta cotação específica.
  somenteLeitura?: boolean;
}

export default function FornecedoresSidebar({
  fornecedores,
  onFornecedorSalvo,
  onFornecedorInativado,
  fornecedoresJaAdicionadosIds,
  onAdicionarCotacao,
  adicionando,
  podeAdicionar,
  somenteLeitura = false,
}: Props) {
  const [modalAberto, setModalAberto] = useState(false);
  const [editando, setEditando] = useState<Fornecedor | null>(null);
  const [inativando, setInativando] = useState<string | null>(null);
  const [paraInativar, setParaInativar] = useState<Fornecedor | null>(null);
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

  async function confirmarInativar() {
    const f = paraInativar;
    if (!f) return;
    setParaInativar(null);
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

      {somenteLeitura && (
        <p className="mt-2 text-xs text-t3">Cotação finalizada — consulta apenas, sem novas adições.</p>
      )}

      {erro && <p className="mt-2 text-xs text-er">{erro}</p>}

      {/* Altura FIXA (não máxima) e scroll contidos só neste miolo de cards (Prompt
          27) — não no painel inteiro nem na página. O Prompt 25 (feedback
          2026-08-16) removeu um overflow-y-auto daqui, mas aquele tentava dar altura
          própria ao painel inteiro dentro de um passo (entrada/page.tsx) que não é
          h-full-limitado como o passo 1 é — isso reintroduzia scroll duplo (painel
          E página competindo). Uma altura FIXA + borda só na lista, com o rodapé
          ("Adicionar cotação") em fluxo normal logo abaixo, evita o duplo scroll: só
          a lista tem região de rolagem própria; o resto do Card cresce livre e quem
          absorve overflow do Card inteiro continua sendo o único scroll da página.
          220px (~2 cards antes de rolar) — deixa o painel inteiro (cabeçalho + busca
          + lista + botão) caber sem precisar rolar a página, mesmo com o topo da
          tela (nav + título + timeline) já ocupando espaço acima (achado do usuário,
          2026-08-17: 380px ainda empurrava o botão "Adicionar cotação" para fora da
          viewport, exigindo scroll da página). */}
      <div className="mt-3 h-[220px] space-y-2.5 overflow-y-auto rounded-md border border-bdr p-2">
        {fornecedoresFiltrados.map((f, i) => {
          const jaAdicionado = fornecedoresJaAdicionadosIds.includes(f.id);
          const selecionavel = !jaAdicionado && !somenteLeitura;
          const ativo = f.id === selecionadoId;
          return (
            <div
              key={f.id}
              onClick={selecionavel ? () => setSelecionadoId(ativo ? null : f.id) : undefined}
              className={`flex gap-2.5 rounded-md border p-2.5 ${selecionavel ? "cursor-pointer" : ""} ${
                ativo ? "border-prx bg-prx/5" : "border-bdr bg-surf"
              }`}
            >
              <span className="w-1 shrink-0 rounded-full" style={{ background: corFornecedor(i) }} />
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
                      setParaInativar(f);
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

      {!somenteLeitura && (
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
      )}

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
      <ConfirmModal
        open={paraInativar !== null}
        onClose={() => setParaInativar(null)}
        onConfirm={confirmarInativar}
        title="Inativar fornecedor"
        message={`Inativar "${paraInativar?.nome}"? Ele deixa de aparecer para novas cotações.`}
        confirmLabel="Inativar"
        confirmando={inativando === paraInativar?.id}
      />
    </div>
  );
}
