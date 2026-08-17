"use client";

import { Dispatch, SetStateAction, useState } from "react";
import { atualizarFornecedor } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { CotacaoFornecedorResponse, Fornecedor, FornecedorRequest } from "@/lib/types";

interface Props {
  cotacaoFornecedor: CotacaoFornecedorResponse;
  fornecedor: Fornecedor | undefined;
  onFornecedorAtualizado: (fornecedor: Fornecedor) => void;
  texto: string;
  setTexto: Dispatch<SetStateAction<string>>;
}

const STATUS_LABEL: Record<string, string> = {
  PENDENTE: "Aguardando resposta",
  PROCESSADO: "Processado — confirme na Conferência para liberar o próximo fornecedor",
  CONFIRMADO: "Confirmado",
};

function dadosDe(fornecedor: Fornecedor | undefined): FornecedorRequest {
  return {
    nome: fornecedor?.nome ?? "",
    prazoEntregaPadrao: fornecedor?.prazoEntregaPadrao ?? "",
    condicaoPagamentoPadrao: fornecedor?.condicaoPagamentoPadrao ?? "",
    pedidoMinimoPadrao: fornecedor?.pedidoMinimoPadrao ?? null,
    observacoesPadrao: fornecedor?.observacoesPadrao ?? "",
  };
}

// Painel do fornecedor ativo — campos de dados comerciais direto na tela (sem modal),
// igual ao protótipo (.fe-col-dados). Salva por campo, ao perder o foco, só quando o
// valor muda. O card compacto da FornecedoresSidebar continua com badges + modal —
// esta é a única tela que precisa dos campos abertos pra edição rápida durante o
// processamento sequencial da cotação.
export default function FornecedorRespostaBlock({
  cotacaoFornecedor,
  fornecedor,
  onFornecedorAtualizado,
  texto,
  setTexto,
}: Props) {
  const [dados, setDados] = useState<FornecedorRequest>(() => dadosDe(fornecedor));
  const [salvandoCampo, setSalvandoCampo] = useState<string | null>(null);
  const [erroCampo, setErroCampo] = useState<string | null>(null);

  // O bloco do fornecedor ativo permanece montado enquanto o operador edita campos em
  // outro lugar da tela (modal "Editar" da FornecedoresSidebar) sem trocar de fornecedor
  // ativo — `key={atual.id}` só remonta ao trocar. Sem ressincronizar `dados` aqui, o
  // próximo salvarCampo (onBlur) faria um PUT do objeto `dados` inteiro com os campos
  // antigos, revertendo silenciosamente a edição feita fora deste bloco (achado da Fase 4).
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

  const dadosPendentes = fornecedor?.status === "PENDENTE_DADOS";

  return (
    <div className={`rounded-lg border p-4 ${dadosPendentes ? "border-wa/50 bg-wa-d" : "border-bdr"}`}>
      <div className="grid items-stretch gap-4 md:grid-cols-2">
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2">
            <h3
              className={`font-medium ${
                dadosPendentes ? "text-wa underline decoration-wa decoration-2 underline-offset-2" : "text-t1"
              }`}
              title={dadosPendentes ? "Complete os dados deste fornecedor" : undefined}
            >
              {cotacaoFornecedor.nomeFornecedor}
            </h3>
            {salvandoCampo && <span className="text-xs text-t3">Salvando...</span>}
          </div>

          {dadosPendentes && (
            <p className="-mt-1 text-xs font-medium text-wa">
              ⚠ Cadastro incompleto — complete prazo de entrega, condição de pagamento e pedido mínimo para incluir
              este fornecedor na Compra Equilibrada do Mapa de Compra.
            </p>
          )}

          {erroCampo && <p className="text-xs text-er">{erroCampo}</p>}

          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="text-[10.5px] font-semibold text-t2">Nome</label>
              <input
                value={dados.nome}
                onChange={(e) => setDados((d) => ({ ...d, nome: e.target.value }))}
                onBlur={() => salvarCampo("nome")}
                className="mt-0.5 w-full rounded-md border border-bdr px-2.5 py-2 text-xs outline-none focus:border-prx"
              />
            </div>
            <div>
              <label className="text-[10.5px] font-semibold text-t2">Prazo de entrega</label>
              <input
                value={dados.prazoEntregaPadrao ?? ""}
                onChange={(e) => setDados((d) => ({ ...d, prazoEntregaPadrao: e.target.value }))}
                onBlur={() => salvarCampo("prazoEntregaPadrao")}
                placeholder="Ex: 2 dias úteis"
                className="mt-0.5 w-full rounded-md border border-bdr px-2.5 py-2 text-xs outline-none focus:border-prx"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="text-[10.5px] font-semibold text-t2">Pagamento</label>
              <input
                value={dados.condicaoPagamentoPadrao ?? ""}
                onChange={(e) => setDados((d) => ({ ...d, condicaoPagamentoPadrao: e.target.value }))}
                onBlur={() => salvarCampo("condicaoPagamentoPadrao")}
                placeholder="Ex: Boleto 14 dias"
                className="mt-0.5 w-full rounded-md border border-bdr px-2.5 py-2 text-xs outline-none focus:border-prx"
              />
            </div>
            <div>
              <label className="text-[10.5px] font-semibold text-t2">Pedido mínimo (R$)</label>
              <input
                type="number"
                min={0}
                step="0.01"
                value={dados.pedidoMinimoPadrao ?? ""}
                onChange={(e) =>
                  setDados((d) => ({ ...d, pedidoMinimoPadrao: e.target.value ? Number(e.target.value) : null }))
                }
                onBlur={() => salvarCampo("pedidoMinimoPadrao")}
                className="mt-0.5 w-full rounded-md border border-bdr px-2.5 py-2 text-xs outline-none focus:border-prx"
              />
            </div>
          </div>

          <div>
            <label className="text-[10.5px] font-semibold text-t2">Observações</label>
            <textarea
              value={dados.observacoesPadrao ?? ""}
              onChange={(e) => setDados((d) => ({ ...d, observacoesPadrao: e.target.value }))}
              onBlur={() => salvarCampo("observacoesPadrao")}
              rows={2}
              placeholder="Desconto PIX, entrega grátis, etc."
              className="mt-0.5 w-full rounded-md border border-bdr px-2.5 py-2 text-xs outline-none focus:border-prx"
            />
          </div>

          <p className="mt-auto text-xs font-medium text-t3">{STATUS_LABEL[cotacaoFornecedor.status]}</p>
        </div>

        <div className="flex flex-col gap-2">
          <label className="text-[10.5px] font-semibold text-t2">Colar resposta do WhatsApp</label>
          <textarea
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder={"Sazon Legumes 60g - R$ 4,89\nLeite Integral 1L - sem estoque"}
            className="min-h-[160px] w-full flex-1 resize-y rounded-md border border-bdr px-3 py-2 font-mono text-sm outline-none focus:border-prx"
          />
        </div>
      </div>
    </div>
  );
}
