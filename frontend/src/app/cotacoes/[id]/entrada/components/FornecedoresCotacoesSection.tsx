"use client";

import { Dispatch, SetStateAction, useEffect, useMemo, useState } from "react";
import Card from "@/components/Card";
import { adicionarFornecedorNaCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { Cotacao, CotacaoFornecedorResponse, Fornecedor } from "@/lib/types";
import FornecedorRespostaBlock from "./FornecedorRespostaBlock";
import FornecedoresSidebar from "./FornecedoresSidebar";

interface Props {
  cotacao: Cotacao;
  cotacaoId: string;
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  todosFornecedores: Fornecedor[];
  onCotacaoFornecedoresAtualizados: () => void;
  onFornecedorAtualizado: (fornecedor: Fornecedor) => void;
  onFornecedorInativado: (id: string) => void;
  onAtivoAlterado: (cotacaoFornecedor: CotacaoFornecedorResponse | null) => void;
  texto: string;
  setTexto: Dispatch<SetStateAction<string>>;
  // Passo 2 e passo 3 (ConferenciaPanel) ficam sempre montados ao mesmo tempo (Prompt
  // 25/26) — sem essa guarda os dois brigam por qual fornecedor é "ativo" na página
  // (cada um chamando onAtivoAlterado com o seu próprio `atual`), causando um
  // ping-pong de re-renders que nunca estabiliza (achado do usuário, 2026-08-16: a
  // Conferência ficava "Carregando..." pra sempre com chamadas de rede duplicadas).
  // Só o passo visível pode escrever em fornecedorAtivo.
  ativo: boolean;
  setErro: Dispatch<SetStateAction<string | null>>;
}

const STATUS_DOT: Record<string, string> = {
  PENDENTE: "bg-t3",
  PROCESSADO: "bg-wa",
  CONFIRMADO: "bg-ok",
};

function ChevronIcon({ direcao }: { direcao: "esquerda" | "direita" }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round">
      {direcao === "esquerda" ? <path d="M15 18l-6-6 6-6" /> : <path d="M9 18l6-6-6-6" />}
    </svg>
  );
}

export default function FornecedoresCotacoesSection({
  cotacao,
  cotacaoId,
  cotacaoFornecedores,
  todosFornecedores,
  onCotacaoFornecedoresAtualizados,
  onFornecedorAtualizado,
  onFornecedorInativado,
  onAtivoAlterado,
  texto,
  setTexto,
  ativo,
  setErro,
}: Props) {
  const [activeId, setActiveId] = useState<string | null>(null);
  const [adicionando, setAdicionando] = useState(false);
  // Painel "Abrir fornecedores" (Prompt 25) — substitui o antigo fluxo de
  // "+ Adicionar Fornecedor" (autocomplete): agora é o mesmo painel de catálogo
  // (FornecedoresSidebar) que já existia abaixo, só que sob demanda dentro deste
  // Card. Começa aberto quando a cotação ainda não tem nenhum fornecedor, mesmo
  // motivo do antigo default de modoAdicionar.
  const [painelFornecedoresAberto, setPainelFornecedoresAberto] = useState(cotacaoFornecedores.length === 0);

  // Cotação finalizada (Prompt 27): painel de fornecedores vira consulta — sem
  // seleção nem botão de adicionar (ver FornecedoresSidebar somenteLeitura).
  const cotacaoFinalizada = cotacao.status === "FINALIZADA";

  const ultimo = cotacaoFornecedores[cotacaoFornecedores.length - 1];
  const podeAdicionarProximo = cotacaoFornecedores.length === 0 || ultimo?.status === "CONFIRMADO";

  // Ordem de navegação da Fase 4: pendentes de auditoria primeiro (PENDENTE/PROCESSADO),
  // depois os já confirmados — preservando a ordem original (`ordem`) dentro de cada
  // grupo. Só usa o status já exposto por GET .../fornecedores; nenhuma contagem de
  // divergência por item entra aqui (essa granularidade só existe efêmera dentro do
  // preview de cada fornecedor — ver decisão documentada no plano da Fase 4).
  const sequencia = useMemo(() => {
    const pendentes = cotacaoFornecedores.filter((cf) => cf.status !== "CONFIRMADO");
    const confirmados = cotacaoFornecedores.filter((cf) => cf.status === "CONFIRMADO");
    return [...pendentes, ...confirmados];
  }, [cotacaoFornecedores]);

  const atual = cotacaoFornecedores.find((cf) => cf.id === activeId) ?? sequencia[0];
  const posicao = atual ? sequencia.findIndex((cf) => cf.id === atual.id) + 1 : 0;
  const totalPendentes = cotacaoFornecedores.filter((cf) => cf.status !== "CONFIRMADO").length;

  function irPara(id: string) {
    setPainelFornecedoresAberto(false);
    setActiveId(id);
  }

  function irParaAdjacente(delta: 1 | -1) {
    if (!atual || sequencia.length < 2) return;
    const idx = sequencia.findIndex((cf) => cf.id === atual.id);
    const proximo = sequencia[(idx + delta + sequencia.length) % sequencia.length];
    irPara(proximo.id);
  }

  // Único caminho de adicionar fornecedor à cotação (Prompt 25 — antes também existia
  // um autocomplete separado, absorvido pelo painel "Abrir fornecedores"). Fecha o
  // painel ao concluir pra voltar direto pro fornecedor recém-adicionado.
  async function onAdicionarCotacaoDoPainel(fornecedor: Fornecedor) {
    setAdicionando(true);
    setErro(null);
    try {
      const cf = await adicionarFornecedorNaCotacao(cotacaoId, { fornecedorId: fornecedor.id });
      onCotacaoFornecedoresAtualizados();
      setPainelFornecedoresAberto(false);
      setActiveId(cf.id);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível adicionar o fornecedor à cotação."));
    } finally {
      setAdicionando(false);
    }
  }

  useEffect(() => {
    if (!ativo) return;
    onAtivoAlterado(painelFornecedoresAberto ? null : (atual ?? null));
    // Depende só do id (não do objeto `atual`, que muda de referência a cada refetch
    // dos mesmos fornecedores) — senão um reprocessamento reseta texto/preview do pai
    // no meio do fluxo. onAtivoAlterado não entra nas deps de propósito (recriada a
    // cada render do pai).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual?.id, painelFornecedoresAberto, ativo]);

  const podeNavegar = sequencia.length > 1;

  return (
    <Card>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold text-t1">Fornecedores e cotações</h2>
          <p className="mt-1 text-sm text-t2">
            Navegue livremente entre os fornecedores já adicionados — cada um mantém suas respostas e resoluções em
            andamento. Confirme a Conferência de um para liberar a adição do próximo.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setPainelFornecedoresAberto((v) => !v)}
          className="shrink-0 rounded-md border border-prx px-3 py-1.5 text-sm font-medium text-prx hover:bg-prx/10"
        >
          {painelFornecedoresAberto
            ? cotacaoFinalizada
              ? "Voltar"
              : "Voltar para seleção"
            : cotacaoFinalizada
              ? "Ver fornecedores"
              : "Abrir fornecedores"}
        </button>
      </div>

      {cotacao.canalOrigem === "WHATSAPP" && cotacaoFornecedores.length === 0 && (
        <p className="mt-3 rounded-md border border-wa/30 bg-wa/10 px-3 py-2 text-sm text-t1">
          Nenhuma resposta de fornecedor via WhatsApp recebida ainda. Assim que um fornecedor responder pelo
          WhatsApp, a resposta aparecerá aqui automaticamente — você também pode adicionar um fornecedor
          manualmente abaixo.
        </p>
      )}

      {cotacaoFornecedores.length > 0 && !painelFornecedoresAberto && atual && (
        <div className="mt-4 flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => irParaAdjacente(-1)}
              disabled={sequencia.length < 2}
              aria-label="Fornecedor anterior"
              className="rounded-md border border-bdr px-2 py-1 text-t2 hover:bg-hov disabled:cursor-not-allowed disabled:opacity-40"
            >
              ‹
            </button>
            <span className="text-sm font-medium text-t1">
              Fornecedor {posicao} de {sequencia.length}
            </span>
            <button
              type="button"
              onClick={() => irParaAdjacente(1)}
              disabled={sequencia.length < 2}
              aria-label="Próximo fornecedor"
              className="rounded-md border border-bdr px-2 py-1 text-t2 hover:bg-hov disabled:cursor-not-allowed disabled:opacity-40"
            >
              ›
            </button>
          </div>
          {totalPendentes > 0 && (
            <span className="rounded-full bg-wa/10 px-2.5 py-0.5 text-xs font-medium text-wa">
              {totalPendentes} para conferir
            </span>
          )}
        </div>
      )}

      {cotacaoFornecedores.length > 0 && (
        <div className="mt-3 flex gap-1.5 overflow-x-auto">
          {cotacaoFornecedores.map((cf) => {
            const dadosPendentes =
              todosFornecedores.find((f) => f.id === cf.fornecedorId)?.status === "PENDENTE_DADOS";
            const ativa = !painelFornecedoresAberto && cf.id === atual?.id;
            return (
              <button
                key={cf.id}
                type="button"
                onClick={() => irPara(cf.id)}
                title={dadosPendentes ? "Cadastro incompleto — complete os dados deste fornecedor" : undefined}
                className={`flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md border px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
                  ativa
                    ? "border-prx bg-prx/10 text-prx"
                    : dadosPendentes
                      ? "border-wa/50 bg-wa-d text-wa hover:bg-wa-d"
                      : "border-bdr text-t2 hover:bg-hov"
                }`}
              >
                <span className={`h-1.5 w-1.5 rounded-full ${STATUS_DOT[cf.status] ?? "bg-t3"}`} />
                <span className={dadosPendentes ? "underline decoration-wa decoration-2 underline-offset-2" : undefined}>
                  {cf.nomeFornecedor ?? "Fornecedor"}
                </span>
                {dadosPendentes && (
                  <span aria-hidden className={ativa ? "text-wa" : undefined}>
                    ⚠
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}

      <div className="relative mt-4">
        {cotacaoFornecedores.length > 1 && !painelFornecedoresAberto && (
          <>
            {/* Card tem p-6 (24px) de padding — este bloco de conteúdo (FornecedorRespostaBlock)
                começa exatamente na borda desse padding, então um botão largo demais ou
                pouco deslocado passa a cobrir os campos (achado do usuário: "botão em
                cima dos campos") ou a vazar pra fora da borda do Card (achado do usuário:
                "botões azuis saindo do card"). w-5 (20px) em -left-6/-right-6 (24px, exatamente
                o padding do Card) mantém a pílula inteira DENTRO da borda do Card, com uma
                folga de 4px antes do conteúdo — nunca sobrepõe Nome/Prazo/Pagamento etc. e
                nunca ultrapassa a borda. */}
            <button
              type="button"
              onClick={() => irParaAdjacente(-1)}
              disabled={!podeNavegar}
              aria-label="Fornecedor anterior"
              className="absolute -left-6 top-1/2  flex h-16 w-5 -translate-y-1/2 items-center justify-center rounded-full bg-inf text-white shadow-md transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <ChevronIcon direcao="esquerda" />
            </button>
            <button
              type="button"
              onClick={() => irParaAdjacente(1)}
              disabled={!podeNavegar}
              aria-label="Próximo fornecedor"
              className="absolute -right-6 top-1/2 flex h-16 w-5 -translate-y-1/2 items-center justify-center rounded-full bg-inf text-white shadow-md transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <ChevronIcon direcao="direita" />
            </button>
          </>
        )}
        {painelFornecedoresAberto ? (
          <FornecedoresSidebar
            fornecedores={todosFornecedores}
            fornecedoresJaAdicionadosIds={cotacaoFornecedores.map((cf) => cf.fornecedorId)}
            onFornecedorSalvo={onFornecedorAtualizado}
            onFornecedorInativado={onFornecedorInativado}
            onAdicionarCotacao={onAdicionarCotacaoDoPainel}
            adicionando={adicionando}
            podeAdicionar={podeAdicionarProximo}
            somenteLeitura={cotacaoFinalizada}
          />
        ) : atual ? (
          <FornecedorRespostaBlock
            key={atual.id}
            cotacaoFornecedor={atual}
            fornecedor={todosFornecedores.find((f) => f.id === atual.fornecedorId)}
            onFornecedorAtualizado={onFornecedorAtualizado}
            texto={texto}
            setTexto={setTexto}
          />
        ) : null}
      </div>
    </Card>
  );
}
