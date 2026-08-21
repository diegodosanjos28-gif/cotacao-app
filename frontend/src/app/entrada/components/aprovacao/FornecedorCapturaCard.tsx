"use client";

import { useState } from "react";
import { atualizarFornecedor } from "@/lib/api";
import { avatarCor, iniciais } from "@/lib/avatarCor";
import { getErrorMessage } from "@/lib/errors";
import { CotacaoFornecedorResponse, Fornecedor, FornecedorRequest } from "@/lib/types";

function dadosDe(fornecedor: Fornecedor | undefined): FornecedorRequest {
  return {
    nome: fornecedor?.nome ?? "",
    prazoEntregaPadrao: fornecedor?.prazoEntregaPadrao ?? "",
    condicaoPagamentoPadrao: fornecedor?.condicaoPagamentoPadrao ?? "",
    pedidoMinimoPadrao: fornecedor?.pedidoMinimoPadrao ?? null,
    observacoesPadrao: fornecedor?.observacoesPadrao ?? "",
  };
}

// Card de captura do fluxo Web manual — fornecedor PENDENTE dentro da aba
// "Conferência das Cotações" do AprovacaoModal (refactor 2026-08-20: antes vivia num
// painel próprio atrás do modal, FornecedorRespostaBlock; redesenhado aqui pra seguir
// a mesma linguagem visual do resto da Entrada de Dados — avatar por iniciais,
// pill de status, cards com borda suave — em vez do formulário em grid antigo).
// Nunca aparece para cotações WHATSAPP (a resposta chega sozinha pelo webhook).
export default function FornecedorCapturaCard({
  cotacaoFornecedor,
  fornecedor,
  onFornecedorAtualizado,
  texto,
  onTextoChange,
  onProcessar,
}: {
  cotacaoFornecedor: CotacaoFornecedorResponse;
  fornecedor: Fornecedor | undefined;
  onFornecedorAtualizado: (fornecedor: Fornecedor) => void;
  texto: string;
  onTextoChange: (texto: string) => void;
  onProcessar: () => Promise<void>;
}) {
  const [dados, setDados] = useState<FornecedorRequest>(() => dadosDe(fornecedor));
  const [salvandoCampo, setSalvandoCampo] = useState<string | null>(null);
  const [erroCampo, setErroCampo] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  // Mesmo motivo do FornecedorRespostaBlock original: ressincroniza se o fornecedor
  // for editado em outro lugar (modal "Editar" da sidebar) sem trocar de fornecedor
  // ativo — key={cotacaoFornecedor.id} no chamador só remonta ao trocar de fornecedor.
  const [fornecedorSincronizado, setFornecedorSincronizado] = useState(fornecedor);
  if (fornecedor !== fornecedorSincronizado) {
    setFornecedorSincronizado(fornecedor);
    setDados(dadosDe(fornecedor));
  }

  async function salvarCampo(campo: keyof FornecedorRequest) {
    if (!fornecedor) return;
    const original = dadosDe(fornecedor);
    if (dados[campo] === original[campo]) return;
    if (campo === "nome" && !String(dados.nome).trim()) {
      setDados((d) => ({ ...d, nome: fornecedor.nome }));
      return;
    }
    setSalvandoCampo(campo);
    setErroCampo(null);
    try {
      const salvo = await atualizarFornecedor(fornecedor.id, dados);
      onFornecedorAtualizado(salvo);
    } catch (err) {
      setErroCampo(getErrorMessage(err, "Não foi possível salvar a alteração."));
      setDados(original);
    } finally {
      setSalvandoCampo(null);
    }
  }

  async function onClicarProcessar() {
    setEnviando(true);
    try {
      await onProcessar();
    } finally {
      setEnviando(false);
    }
  }

  const nome = cotacaoFornecedor.nomeFornecedor ?? "Fornecedor";
  const dadosPendentes = fornecedor?.status === "PENDENTE_DADOS";

  return (
    <div className="rounded-xl border border-bdr bg-card p-4">
      <div className="mb-3.5 flex items-center gap-3">
        <span
          className="flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-full text-sm font-medium text-white shadow-[0_2px_8px_rgba(0,0,0,.16)]"
          style={{ background: avatarCor(nome) }}
        >
          {iniciais(nome)}
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-t1">{nome}</div>
          {dadosPendentes && (
            <div className="mt-0.5 text-[11px] font-medium text-wa-txt">
              ⚠ Cadastro incompleto — complete os dados abaixo
            </div>
          )}
        </div>
        <span className="ml-auto inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border border-bdr bg-surf px-2.5 py-1 text-[10px] font-medium text-t3">
          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-bdr-m" />
          Aguardando resposta
        </span>
      </div>

      {erroCampo && <p className="mb-2 text-xs text-er">{erroCampo}</p>}

      {/* Captura (o que o operador precisa fazer) recebe mais peso que os dados
          comerciais (já vêm preenchidos do cadastro na maioria dos casos). */}
      <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="text-xs font-medium text-t2">Prazo de entrega</label>
            <input
              value={dados.prazoEntregaPadrao ?? ""}
              onChange={(e) => setDados((d) => ({ ...d, prazoEntregaPadrao: e.target.value }))}
              onBlur={() => salvarCampo("prazoEntregaPadrao")}
              placeholder="Ex: 2 dias úteis"
              disabled={!!salvandoCampo}
              className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx disabled:opacity-50"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-t2">Pagamento</label>
            <input
              value={dados.condicaoPagamentoPadrao ?? ""}
              onChange={(e) => setDados((d) => ({ ...d, condicaoPagamentoPadrao: e.target.value }))}
              onBlur={() => salvarCampo("condicaoPagamentoPadrao")}
              placeholder="Ex: Boleto 14 dias"
              disabled={!!salvandoCampo}
              className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx disabled:opacity-50"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-t2">Pedido mínimo (R$)</label>
            <input
              type="number"
              min={0}
              step="0.01"
              value={dados.pedidoMinimoPadrao ?? ""}
              onChange={(e) => setDados((d) => ({ ...d, pedidoMinimoPadrao: e.target.value ? Number(e.target.value) : null }))}
              onBlur={() => salvarCampo("pedidoMinimoPadrao")}
              disabled={!!salvandoCampo}
              className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx disabled:opacity-50"
            />
          </div>
          <div className="col-span-2">
            <label className="text-xs font-medium text-t2">Observações</label>
            <textarea
              value={dados.observacoesPadrao ?? ""}
              onChange={(e) => setDados((d) => ({ ...d, observacoesPadrao: e.target.value }))}
              onBlur={() => salvarCampo("observacoesPadrao")}
              rows={2}
              placeholder="Desconto PIX, entrega grátis, etc."
              disabled={!!salvandoCampo}
              className="mt-1 w-full resize-none rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx disabled:opacity-50"
            />
          </div>
        </div>

        <div className="flex flex-col gap-2">
          <label className="text-xs font-medium text-t2">Colar resposta do fornecedor</label>
          <textarea
            value={texto}
            onChange={(e) => onTextoChange(e.target.value)}
            placeholder={"Sazon Legumes 60g - R$ 4,89\nLeite Integral 1L - sem estoque"}
            className="min-h-[140px] w-full flex-1 resize-y rounded-md border border-bdr px-3 py-2 font-mono text-xs outline-none focus:border-prx"
          />
          <button
            type="button"
            onClick={onClicarProcessar}
            disabled={enviando || !texto.trim()}
            className="rounded-md bg-prx px-4 py-2 text-sm font-semibold text-white hover:bg-prx-l disabled:opacity-50"
          >
            {enviando ? "Processando..." : "Processar Resposta Cotação"}
          </button>
        </div>
      </div>
    </div>
  );
}
