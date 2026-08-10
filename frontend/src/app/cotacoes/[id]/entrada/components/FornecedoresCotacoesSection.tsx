"use client";

import { Dispatch, SetStateAction, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import Card from "@/components/Card";
import { adicionarFornecedorNaCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import {
  Cotacao,
  ConferenciaPatch,
  CotacaoFornecedorResponse,
  EstadoResolucao,
  Fornecedor,
  PreviewRespostaResponse,
} from "@/lib/types";
import FornecedorAutocomplete from "./FornecedorAutocomplete";
import FornecedorFormModal from "./FornecedorFormModal";
import FornecedorRespostaBlock from "./FornecedorRespostaBlock";

interface Props {
  cotacao: Cotacao;
  cotacaoId: string;
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  todosFornecedores: Fornecedor[];
  onCotacaoFornecedoresAtualizados: () => void;
  onFornecedorAtualizado: (fornecedor: Fornecedor) => void;
  onAtivoAlterado: (cotacaoFornecedor: CotacaoFornecedorResponse | null) => void;
  onConferirResposta: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<boolean>;
  onCancelarConferencia: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<void>;
  texto: string;
  setTexto: Dispatch<SetStateAction<string>>;
  preview: PreviewRespostaResponse | null;
  modalAberto: boolean;
  onClosePreview: () => void;
  estadoResolucao: EstadoResolucao;
  onEstadoResolucaoChange: (patch: ConferenciaPatch) => void;
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
  onAtivoAlterado,
  onConferirResposta,
  onCancelarConferencia,
  texto,
  setTexto,
  preview,
  modalAberto,
  onClosePreview,
  estadoResolucao,
  onEstadoResolucaoChange,
  setErro,
}: Props) {
  const router = useRouter();
  const [activeId, setActiveId] = useState<string | null>(null);
  const [modoAdicionar, setModoAdicionar] = useState(cotacaoFornecedores.length === 0);
  const [cadastrandoNome, setCadastrandoNome] = useState<string | null>(null);
  const [adicionando, setAdicionando] = useState(false);
  const [carregandoConferencia, setCarregandoConferencia] = useState(false);

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
    setModoAdicionar(false);
    setActiveId(id);
  }

  function irParaAdjacente(delta: 1 | -1) {
    if (!atual || sequencia.length < 2) return;
    const idx = sequencia.findIndex((cf) => cf.id === atual.id);
    const proximo = sequencia[(idx + delta + sequencia.length) % sequencia.length];
    irPara(proximo.id);
  }

  // Chamado quando o operador confirma a Conferência de um fornecedor (ConferenciaModal
  // reporta o evento, não decide navegação sozinho — Fase 4). Se ainda houver outro
  // fornecedor pendente de auditoria na cotação, avança pra ele e permanece em
  // /entrada; só navega pro Comparativo quando não sobra nenhum pendente.
  async function onFornecedorConfirmado(cotacaoFornecedorId: string) {
    onCotacaoFornecedoresAtualizados();
    const restantes = sequencia.filter(
      (cf) => cf.id !== cotacaoFornecedorId && cf.status !== "CONFIRMADO",
    );
    if (restantes.length === 0) {
      router.push(`/cotacoes/${cotacaoId}/comparativo`);
      return;
    }
    const proximo = restantes[0];
    irPara(proximo.id);
    // Encadeamento automático (achado do usuário, 2026-08-04): só auto-abre quem já
    // tem resposta pra conferir (PROCESSADO). PENDENTE = fornecedor que ainda não
    // respondeu (nada persistido, nada em memória) — abrir a Conferência dele só
    // produziria "sem resposta registrada"; nesse caso só navega e para, o botão do
    // rodapé continua disponível pro operador retomar quando a resposta chegar.
    // Encadeia auto-ABRINDO, não auto-CONFIRMANDO: se o próximo tiver item REVISAR
    // sem resolução, "Confirmar e Processar" continua desabilitado e a cadeia pausa
    // esperando o operador. Se abrirConferencia falhar, a cadeia para aqui com o
    // erro visível — não pula pro seguinte.
    if (proximo.status !== "PROCESSADO") return;
    await abrirConferencia(proximo);
  }

  async function onSelecionarExistente(fornecedor: Fornecedor) {
    setAdicionando(true);
    setErro(null);
    try {
      const cf = await adicionarFornecedorNaCotacao(cotacaoId, { fornecedorId: fornecedor.id });
      onCotacaoFornecedoresAtualizados();
      setModoAdicionar(false);
      setActiveId(cf.id);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível adicionar o fornecedor à cotação."));
    } finally {
      setAdicionando(false);
    }
  }

  async function onNovoFornecedorCriado(fornecedor: Fornecedor) {
    onFornecedorAtualizado(fornecedor);
    setCadastrandoNome(null);
    await onSelecionarExistente(fornecedor);
  }

  // Ponto único de abertura da Conferência — usado tanto pelo clique do operador
  // (fornecedor aberto no carrossel) quanto pelo encadeamento automático de
  // onFornecedorConfirmado. Sempre navega pro alvo antes de abrir, porque o
  // rascunho (preview/modalAberto) só desce como prop pro fornecedor ativo.
  async function abrirConferencia(cf: CotacaoFornecedorResponse): Promise<boolean> {
    setCarregandoConferencia(true);
    irPara(cf.id);
    try {
      return (await onConferirResposta(cf)) === true;
    } catch {
      return false; // onConferirResposta já trata/exibe o erro — blindagem contra rejeição
    } finally {
      setCarregandoConferencia(false);
    }
  }

  async function onConferirAtual() {
    if (!atual) return;
    await abrirConferencia(atual);
  }

  useEffect(() => {
    onAtivoAlterado(modoAdicionar ? null : (atual ?? null));
    // Depende só do id (não do objeto `atual`, que muda de referência a cada refetch
    // dos mesmos fornecedores) — senão um reprocessamento reseta texto/preview do pai
    // no meio do fluxo. onAtivoAlterado não entra nas deps de propósito (recriada a
    // cada render do pai).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual?.id, modoAdicionar]);

  const podeNavegar = sequencia.length > 1;

  return (
    <Card>
      <h2 className="font-semibold text-t1">Fornecedores e cotações</h2>
      <p className="mt-1 text-sm text-t2">
        Navegue livremente entre os fornecedores já adicionados — cada um mantém suas respostas e resoluções em
        andamento. Confirme a Conferência de um para liberar a adição do próximo.
      </p>

      {cotacao.canalOrigem === "WHATSAPP" && cotacaoFornecedores.length === 0 && (
        <p className="mt-3 rounded-md border border-wa/30 bg-wa/10 px-3 py-2 text-sm text-t1">
          Nenhuma resposta de fornecedor via WhatsApp recebida ainda. Assim que um fornecedor responder pelo
          WhatsApp, a resposta aparecerá aqui automaticamente — você também pode adicionar um fornecedor
          manualmente abaixo.
        </p>
      )}

      {cotacaoFornecedores.length > 0 && !modoAdicionar && atual && (
        <div className="mt-4 flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => irParaAdjacente(-1)}
              disabled={sequencia.length < 2 || carregandoConferencia}
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
              disabled={sequencia.length < 2 || carregandoConferencia}
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
            const ativa = !modoAdicionar && cf.id === atual?.id;
            return (
              <button
                key={cf.id}
                type="button"
                onClick={() => irPara(cf.id)}
                disabled={carregandoConferencia}
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
        {cotacaoFornecedores.length > 1 && (
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
              disabled={!podeNavegar || carregandoConferencia}
              aria-label="Fornecedor anterior"
              className="absolute -left-6 top-1/2  flex h-16 w-5 -translate-y-1/2 items-center justify-center rounded-full bg-inf text-white shadow-md transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <ChevronIcon direcao="esquerda" />
            </button>
            <button
              type="button"
              onClick={() => irParaAdjacente(1)}
              disabled={!podeNavegar || carregandoConferencia}
              aria-label="Próximo fornecedor"
              className="absolute -right-6 top-1/2 flex h-16 w-5 -translate-y-1/2 items-center justify-center rounded-full bg-inf text-white shadow-md transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <ChevronIcon direcao="direita" />
            </button>
          </>
        )}
        {modoAdicionar ? (
          <div className="rounded-lg border border-dashed border-bdr p-4">
            <p className="mb-2 text-sm font-medium text-t1">Adicionar fornecedor</p>
            <FornecedorAutocomplete
              fornecedores={todosFornecedores}
              excluirIds={cotacaoFornecedores.map((cf) => cf.fornecedorId)}
              onSelecionar={onSelecionarExistente}
              onCadastrarNovo={(nome) => setCadastrandoNome(nome)}
              disabled={adicionando}
            />
            {cotacaoFornecedores.length > 0 && (
              <button
                type="button"
                onClick={() => setModoAdicionar(false)}
                className="mt-3 text-xs font-medium text-t3 hover:text-t2"
              >
                Cancelar
              </button>
            )}
          </div>
        ) : atual ? (
          <FornecedorRespostaBlock
            key={atual.id}
            cotacaoId={cotacaoId}
            cotacaoFornecedor={atual}
            fornecedor={todosFornecedores.find((f) => f.id === atual.fornecedorId)}
            onFornecedorAtualizado={onFornecedorAtualizado}
            onConfirmado={onFornecedorConfirmado}
            texto={texto}
            setTexto={setTexto}
            preview={preview}
            modalAberto={modalAberto}
            onClosePreview={onClosePreview}
            onCancelarConferencia={() => onCancelarConferencia(atual)}
            estadoResolucao={estadoResolucao}
            onEstadoResolucaoChange={onEstadoResolucaoChange}
          />
        ) : null}
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-bdr pt-4">
        <button
          type="button"
          onClick={() => setModoAdicionar(true)}
          disabled={!podeAdicionarProximo || modoAdicionar}
          className="rounded-md border border-prx px-4 py-2 text-sm font-medium text-prx hover:bg-prx/10 disabled:cursor-not-allowed disabled:border-bdr disabled:text-t3 disabled:hover:bg-transparent"
        >
          + Adicionar Fornecedor
        </button>
        {atual?.status === "PROCESSADO" && (
          // Ponto único de entrada da Conferência (unifica o antigo "Continuar
          // Conferência" do FornecedorRespostaBlock com o botão que reconstruía o
          // preview de uma resposta já persistida, achado do usuário 2026-08-04): age
          // sempre sobre o fornecedor ABERTO no carrossel — page.tsx (onConferirResposta)
          // decide entre reusar o preview em memória, processar o texto colado, ou
          // reconstruir do texto persistido (GET .../resposta-persistida).
          //
          // Só aparece quando o fornecedor aberto tem algo PENDENTE de conferir
          // (status PROCESSADO — achado do usuário, 2026-08-04): PENDENTE significa
          // "ainda não respondeu" (nada pra conferir ainda — o botão relevante ali é
          // "Processar Cotação", que continua sempre visível no rodapé/EntradaFooter),
          // e CONFIRMADO significa "já revisado" (reabrir reverteria silenciosamente o
          // status pra PROCESSADO, efeito colateral de gerarPreview no backend —
          // reconferir um confirmado continua possível colando o texto de novo em
          // "Processar Cotação", onde esse efeito é explícito).
          // Tier 1 (bg-prx sólido) — mesma hierarquia de "Processar Cotação"/"Confirmar
          // e Processar" (achado da revisão de UX da Fase 4.1): é o gate bloqueante, não
          // uma ação opcional como "+ Adicionar Fornecedor".
          <button
            type="button"
            onClick={onConferirAtual}
            disabled={carregandoConferencia || modoAdicionar}
            className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:cursor-not-allowed disabled:opacity-50"
          >
            {carregandoConferencia ? "Carregando..." : `Conferir resposta do fornecedor (${totalPendentes})`}
          </button>
        )}
        {!podeAdicionarProximo && !modoAdicionar && (
          <p className="w-full text-xs text-t3">
            Processe a cotação atual para liberar o próximo fornecedor.
          </p>
        )}
      </div>

      <FornecedorFormModal
        open={cadastrandoNome !== null}
        onClose={() => setCadastrandoNome(null)}
        nomeInicial={cadastrandoNome ?? ""}
        onSalvo={onNovoFornecedorCriado}
      />
    </Card>
  );
}
