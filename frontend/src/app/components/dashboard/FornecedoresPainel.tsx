"use client";

import { useState } from "react";
import ConfirmModal from "@/components/ConfirmModal";
import FornecedorFormModal from "@/app/cotacoes/[id]/entrada/components/FornecedorFormModal";
import { inativarFornecedor, listarFornecedores } from "@/lib/api";
import { corFornecedor } from "@/lib/coresFornecedor";
import { getErrorMessage } from "@/lib/errors";
import { useAsync } from "@/hooks/useAsync";
import { Fornecedor } from "@/lib/types";

// Fornecedor é um catálogo pequeno por tenant (dezenas, não milhares) — busca e
// paginação ficam client-side sobre a lista já carregada (uma vez, via
// listarFornecedores), sem round-trip por letra digitada. Diferente da paginação
// real por cursor do carrossel ao lado (Cotações Registradas), que cresce sem
// limite e por isso precisa ser server-side.
const TAMANHO_PAGINA_FORNECEDORES = 5;

// Painel "Fornecedores" (Dashboard, linha 1 — 25%) — catálogo do tenant inteiro
// (não escopado a uma cotação, diferente de FornecedoresSidebar em Entrada de
// Dados). Só o fundo escuro premium é novo aqui; os cards internos seguem o mesmo
// formulário/fluxo de edição/inativação já existentes (FornecedorFormModal,
// inativarFornecedor) — não redesenhados.
export default function FornecedoresPainel() {
  const { data: fornecedores, setData, carregando, erro: erroCarregar } = useAsync(
    () => listarFornecedores(),
    [],
    "Não foi possível carregar os fornecedores.",
  );
  const [modalAberto, setModalAberto] = useState(false);
  const [editando, setEditando] = useState<Fornecedor | null>(null);
  const [inativando, setInativando] = useState<string | null>(null);
  const [paraInativar, setParaInativar] = useState<Fornecedor | null>(null);
  const [erroAcao, setErroAcao] = useState<string | null>(null);
  const [busca, setBusca] = useState("");
  const [pageIndex, setPageIndex] = useState(0);

  function onSalvo(f: Fornecedor) {
    setData((atual) => {
      const lista = atual ?? [];
      const existe = lista.some((x) => x.id === f.id);
      return existe ? lista.map((x) => (x.id === f.id ? f : x)) : [f, ...lista];
    });
    setModalAberto(false);
    setEditando(null);
  }

  // "Inativar", não excluir — o cadastro de fornecedor só tem soft-delete
  // (DELETE /fornecedores/{id} marca INATIVO). O ícone de lixeira do mockup mapeia
  // pra esse fluxo real, não uma exclusão literal.
  async function confirmarInativar() {
    const f = paraInativar;
    if (!f) return;
    setParaInativar(null);
    setInativando(f.id);
    setErroAcao(null);
    try {
      await inativarFornecedor(f.id);
      setData((atual) => (atual ?? []).map((x) => (x.id === f.id ? { ...x, status: "INATIVO" } : x)));
    } catch (err) {
      setErroAcao(getErrorMessage(err, "Não foi possível inativar o fornecedor."));
    } finally {
      setInativando(null);
    }
  }

  const ativos = (fornecedores ?? []).filter((f) => f.status !== "INATIVO").length;

  const buscaNormalizada = busca.trim().toLowerCase();
  const filtrados = buscaNormalizada
    ? (fornecedores ?? []).filter((f) => f.nome.toLowerCase().includes(buscaNormalizada))
    : (fornecedores ?? []);
  const totalPaginas = Math.max(Math.ceil(filtrados.length / TAMANHO_PAGINA_FORNECEDORES), 1);
  const pageIndexValido = Math.min(pageIndex, totalPaginas - 1);
  const paginaAtual = filtrados.slice(
    pageIndexValido * TAMANHO_PAGINA_FORNECEDORES,
    (pageIndexValido + 1) * TAMANHO_PAGINA_FORNECEDORES,
  );

  function onBuscaChange(valor: string) {
    setBusca(valor);
    setPageIndex(0);
  }

  return (
    <div
      // Altura FIXA (não min-h, não stretch de grid) — depois de duas tentativas
      // de "acompanhar a altura do carrossel ao lado" via CSS Grid stretch (achado
      // do usuário, 2026-08-20: o painel continuava crescendo/empurrando o layout
      // com a paginação, mesmo com self-stretch/h-full), a exigência real é mais
      // simples: o painel NUNCA deve crescer, ponto. h-[450px] fixo + min-h-0 na
      // lista rolável dentro dele garante isso incondicionalmente, sem depender de
      // nenhum comportamento de layout do irmão. TAMANHO_PAGINA_FORNECEDORES=5
      // foi calibrado pra caber sem precisar rolar no caso comum; a rolagem interna
      // é só rede de segurança (nome longo quebrando linha, fonte maior etc.).
      className="flex h-[450px] min-h-0 flex-col rounded-xl p-4"
      style={{
        background:
          "radial-gradient(120% 140% at 100% 0%, rgba(254,118,65,.16), transparent 55%), linear-gradient(158deg, #37332F, #302E2B 55%, #242220)",
        boxShadow: "0 10px 32px rgba(0,0,0,.24), 0 2px 8px rgba(0,0,0,.14)",
      }}
    >
      <div className="mb-3.5 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm font-medium text-white">
          Fornecedores
          <span className="rounded-full bg-white/10 px-2 py-0.5 text-[10.5px] font-medium text-white/70">{ativos}</span>
        </div>
        <button
          type="button"
          onClick={() => setModalAberto(true)}
          className="inline-flex items-center gap-1 rounded-md bg-prx px-2.5 py-1.5 text-[11px] font-medium text-white shadow-[0_2px_8px_rgba(255,128,0,.3)] hover:bg-prx-l"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Novo
        </button>
      </div>

      <div className="relative mb-2.5 shrink-0">
        <svg
          width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
          strokeLinecap="round" strokeLinejoin="round"
          className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-white/40"
        >
          <circle cx="11" cy="11" r="8" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        <input
          type="text"
          value={busca}
          onChange={(e) => onBuscaChange(e.target.value)}
          placeholder="Buscar fornecedor..."
          className="w-full rounded-md border border-white/10 bg-white/[.06] py-1.5 pl-7 pr-2.5 text-[12px] text-white placeholder:text-white/40 focus:border-prx focus:outline-none"
        />
      </div>

      {(erroCarregar || erroAcao) && <p className="mb-2 text-xs text-red-300">{erroCarregar ?? erroAcao}</p>}

      <div className="flex flex-1 min-h-0 flex-col gap-2 overflow-y-auto pr-1">
        {carregando && <p className="text-sm text-white/70">Carregando...</p>}
        {!carregando && filtrados.length === 0 && (
          <p className="text-sm text-white/70">
            {buscaNormalizada ? "Nenhum fornecedor encontrado." : "Nenhum fornecedor cadastrado ainda."}
          </p>
        )}
        {paginaAtual.map((f, i) => (
          // Cor pela posição na lista filtrada (não pela posição na página) — um
          // fornecedor mantém a mesma cor ao trocar de página, em vez de recomeçar
          // o ciclo a cada página.
          <div
            key={f.id}
            className={`flex items-center gap-2.5 rounded-md border border-white/[.08] bg-white/[.06] p-2.5 hover:bg-white/10 ${
              f.status === "INATIVO" ? "opacity-50" : ""
            }`}
          >
            <span
              className="h-8 w-1 shrink-0 rounded-sm"
              style={{ background: corFornecedor(pageIndexValido * TAMANHO_PAGINA_FORNECEDORES + i) }}
            />
            <div className="min-w-0 flex-1">
              <div className="mb-0.5 flex items-center gap-1.5 truncate text-[12.5px] font-medium text-white">
                <span className="truncate">{f.nome}</span>
                {f.status === "INATIVO" && (
                  <span className="shrink-0 rounded bg-red-500/15 px-1 py-px text-[8.5px] font-medium uppercase tracking-wide text-red-400">
                    Inativo
                  </span>
                )}
              </div>
              <div className="flex flex-wrap gap-x-2.5 gap-y-0.5 text-[10.5px] text-white/50">
                <span className="inline-flex items-center gap-1">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="10" />
                    <polyline points="12 6 12 12 16 14" />
                  </svg>
                  {f.prazoEntregaPadrao ?? "—"}
                </span>
                <span className="inline-flex items-center gap-1">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                    <rect x="2" y="5" width="20" height="14" rx="2" />
                    <path d="M2 10h20" />
                  </svg>
                  {f.condicaoPagamentoPadrao ?? "—"}
                </span>
              </div>
            </div>
            <div className="flex shrink-0 gap-1">
              <button
                type="button"
                title="Editar"
                onClick={() => setEditando(f)}
                className="flex h-[26px] w-[26px] items-center justify-center rounded-md border border-white/10 text-white/55 hover:border-white/25 hover:bg-white/10 hover:text-white"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                  <path d="M18.5 2.5a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4z" />
                </svg>
              </button>
              <button
                type="button"
                title="Inativar"
                disabled={inativando === f.id || f.status === "INATIVO"}
                onClick={() => setParaInativar(f)}
                className="flex h-[26px] w-[26px] items-center justify-center rounded-md border border-white/10 text-white/55 hover:border-red-400/30 hover:bg-red-500/15 hover:text-red-400 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="3 6 5 6 21 6" />
                  <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                </svg>
              </button>
            </div>
          </div>
        ))}
      </div>

      {filtrados.length > TAMANHO_PAGINA_FORNECEDORES && (
        <div className="mt-2.5 flex shrink-0 items-center justify-between gap-2 border-t border-white/10 pt-2.5 text-[10.5px] text-white/50">
          <span>
            {pageIndexValido * TAMANHO_PAGINA_FORNECEDORES + 1}–
            {Math.min((pageIndexValido + 1) * TAMANHO_PAGINA_FORNECEDORES, filtrados.length)} de {filtrados.length}
          </span>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => setPageIndex((p) => p - 1)}
              disabled={pageIndexValido === 0}
              aria-label="Fornecedores — página anterior"
              className="flex h-6 w-6 items-center justify-center rounded border border-white/10 text-white/60 hover:border-white/25 hover:bg-white/10 hover:text-white disabled:cursor-not-allowed disabled:opacity-30"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
                <path d="M15 18l-6-6 6-6" />
              </svg>
            </button>
            <span className="text-white/60">{pageIndexValido + 1}/{totalPaginas}</span>
            <button
              type="button"
              onClick={() => setPageIndex((p) => p + 1)}
              disabled={pageIndexValido + 1 >= totalPaginas}
              aria-label="Fornecedores — próxima página"
              className="flex h-6 w-6 items-center justify-center rounded border border-white/10 text-white/60 hover:border-white/25 hover:bg-white/10 hover:text-white disabled:cursor-not-allowed disabled:opacity-30"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
                <path d="M9 18l6-6-6-6" />
              </svg>
            </button>
          </div>
        </div>
      )}

      <FornecedorFormModal open={modalAberto} onClose={() => setModalAberto(false)} onSalvo={onSalvo} />
      <FornecedorFormModal
        open={editando !== null}
        onClose={() => setEditando(null)}
        fornecedor={editando}
        onSalvo={onSalvo}
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
