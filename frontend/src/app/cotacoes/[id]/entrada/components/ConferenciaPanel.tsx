"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ConferenciaPatch, CotacaoFornecedorResponse, EstadoResolucao, PreviewRespostaResponse } from "@/lib/types";
import ConferenciaConfirmadaReadOnly from "./ConferenciaConfirmadaReadOnly";
import ConferenciaPendente from "./ConferenciaPendente";

const STATUS_DOT: Record<string, string> = {
  PENDENTE: "bg-t3",
  PROCESSADO: "bg-wa",
  CONFIRMADO: "bg-ok",
};

interface Props {
  cotacaoId: string;
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  onConferirResposta: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<boolean>;
  onCancelarConferencia: (cotacaoFornecedor: CotacaoFornecedorResponse) => Promise<void>;
  onAtivoAlterado: (cotacaoFornecedor: CotacaoFornecedorResponse | null) => void;
  // Precisa ser aguardável: onFornecedorConfirmado espera o refetch terminar antes de
  // navegar/avançar, senão o efeito de auto-fetch abaixo vê `atual.status` ainda
  // desatualizado como PROCESSADO (achado do usuário, 2026-08-16 — ver comentário em
  // onFornecedorConfirmado).
  onCotacaoFornecedoresAtualizados: () => Promise<void>;
  // Passo 2 (FornecedoresCotacoesSection) fica sempre montado junto — sem esta guarda
  // os dois brigam por qual fornecedor é "ativo" na página, ver o comentário
  // equivalente em FornecedoresCotacoesSection.
  ativo: boolean;
  // Fornecedor que estava ativo em outro passo (ex.: acabou de ser processado no
  // passo 2 — "Processar Resposta Cotação" já entrega aqui, achado do usuário
  // 2026-08-16) — usado só como sugestão de seleção inicial ao este painel virar
  // visível, nunca depois disso (a navegação dentro do painel é livre).
  fornecedorFocoId?: string | null;
  texto: string;
  preview: PreviewRespostaResponse | null;
  estadoResolucao: EstadoResolucao;
  onEstadoResolucaoChange: (patch: ConferenciaPatch) => void;
}

// Passo 3 da Entrada de Dados (Prompt 26) — painel dedicado de Conferência, no lugar
// do antigo ConferenciaModal. Dono da própria navegação entre fornecedores (pendentes
// primeiro, depois confirmados — mesmo padrão de FornecedoresCotacoesSection, mas
// local a este painel, não compartilhado com o passo 2). Fornecedor PROCESSADO abre a
// Conferência editável (ConferenciaPendente); fornecedor CONFIRMADO abre a visão
// somente-leitura (ConferenciaConfirmadaReadOnly), sem nunca reprocessar a resposta.
export default function ConferenciaPanel({
  cotacaoId,
  cotacaoFornecedores,
  onConferirResposta,
  onCancelarConferencia,
  onAtivoAlterado,
  onCotacaoFornecedoresAtualizados,
  ativo,
  fornecedorFocoId,
  texto,
  preview,
  estadoResolucao,
  onEstadoResolucaoChange,
}: Props) {
  const router = useRouter();
  const [activeId, setActiveId] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(false);

  const sequencia = useMemo(() => {
    const pendentes = cotacaoFornecedores.filter((cf) => cf.status !== "CONFIRMADO");
    const confirmados = cotacaoFornecedores.filter((cf) => cf.status === "CONFIRMADO");
    return [...pendentes, ...confirmados];
  }, [cotacaoFornecedores]);

  const atual = cotacaoFornecedores.find((cf) => cf.id === activeId) ?? sequencia[0];

  // Ref (não state) só pra o `finally` abaixo saber, no momento em que a promise
  // resolve, qual fornecedor está ativo *agora* — usado pra distinguir troca real de
  // fornecedor (aí sim descarta o resultado) do próprio efeito se re-disparando porque
  // ele mesmo acabou de gravar `preview` no rascunho (não é cancelamento, é sucesso).
  const atualIdRef = useRef(atual?.id);
  useEffect(() => {
    atualIdRef.current = atual?.id;
  }, [atual?.id]);

  // Mantém fornecedorAtivo/rascunhoAtivo de entrada/page.tsx sincronizado com a
  // seleção deste painel — sem isto, texto/preview/estadoResolucao (donos da página)
  // continuariam escopados pro fornecedor que estava ativo no passo 2.
  // Ao virar visível, adota o fornecedor que estava ativo em outro passo (se algum
  // fornecedor da cotação corresponder) como seleção inicial — só nesse instante, não
  // a cada render, pra não atrapalhar a navegação livre dentro do painel.
  useEffect(() => {
    if (!ativo || !fornecedorFocoId) return;
    if (cotacaoFornecedores.some((cf) => cf.id === fornecedorFocoId)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setActiveId(fornecedorFocoId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ativo]);

  useEffect(() => {
    if (!ativo) return;
    onAtivoAlterado(atual ?? null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual?.id, ativo]);

  // Abre a Conferência automaticamente ao selecionar um fornecedor PROCESSADO sem
  // preview ainda em memória — herda o efeito que antes vivia em
  // entrada/page.tsx#onSelecionarPasso, agora local a este painel. Só roda enquanto o
  // passo 3 está visível — evita buscar/perder resposta em memória por um efeito
  // disparando com o painel escondido atrás do passo 2.
  useEffect(() => {
    if (!ativo || !atual || atual.status !== "PROCESSADO" || preview) return;
    const cotacaoFornecedorIdCarregando = atual.id;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCarregando(true);
    onConferirResposta(atual).finally(() => {
      // Compara com o ref (não um bool fixado no cleanup): o próprio
      // onConferirResposta grava `preview` no rascunho antes de terminar, o que
      // re-dispara este efeito (preview está nas deps) e roda o cleanup ANTES desta
      // promise assentar — um bool "cancelado" capturado no cleanup marcaria esse
      // sucesso como cancelamento e travaria `carregando` em true pra sempre (bug
      // relatado pelo usuário 2026-08-17: loading eterno sem erro de rede). Só
      // ignora o resultado se o fornecedor ativo realmente mudou nesse meio tempo.
      if (atualIdRef.current === cotacaoFornecedorIdCarregando) setCarregando(false);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual?.id, atual?.status, preview, ativo]);

  // Encadeamento pós-confirmação (mesma lógica de
  // FornecedoresCotacoesSection.onFornecedorConfirmado, duplicada aqui porque este
  // painel tem sua própria `sequencia`/seleção — os dois navegadores não compartilham
  // estado): avança pro próximo pendente, ou sai pro Comparativo se não sobrar nenhum.
  // Aguarda o refetch ANTES de decidir/navegar (achado do usuário, 2026-08-16): sem
  // isso, ConferenciaPendente.confirmar() limpa o preview em memória logo depois de
  // chamar isto, e por uma janela o fornecedor recém-confirmado fica com
  // `atual.status` ainda desatualizado como PROCESSADO (cotacaoFornecedores só reflete
  // CONFIRMADO depois do fetch) + preview nulo — exatamente a condição que o efeito de
  // auto-fetch acima interpreta como "precisa reprocessar", disparando um
  // enviarResposta duplicado pro fornecedor que acabou de ser confirmado.
  async function onFornecedorConfirmado(cotacaoFornecedorId: string) {
    await onCotacaoFornecedoresAtualizados();
    const restantes = sequencia.filter((cf) => cf.id !== cotacaoFornecedorId && cf.status !== "CONFIRMADO");
    if (restantes.length === 0) {
      router.push(`/cotacoes/${cotacaoId}/comparativo`);
      return;
    }
    setActiveId(restantes[0].id);
  }

  if (!atual) {
    return (
      <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">
        Nenhum fornecedor adicionado a esta cotação ainda — volte ao passo 2 pra adicionar um.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex gap-1.5 overflow-x-auto rounded-lg border border-bdr bg-card p-3">
        {sequencia.map((cf) => (
          <button
            key={cf.id}
            type="button"
            onClick={() => setActiveId(cf.id)}
            disabled={carregando}
            className={`flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md border px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
              cf.id === atual.id ? "border-prx bg-prx/10 text-prx" : "border-bdr text-t2 hover:bg-hov"
            }`}
          >
            <span className={`h-1.5 w-1.5 rounded-full ${STATUS_DOT[cf.status] ?? "bg-t3"}`} />
            {cf.nomeFornecedor ?? "Fornecedor"}
          </button>
        ))}
      </div>

      {atual.status === "CONFIRMADO" ? (
        <ConferenciaConfirmadaReadOnly
          key={atual.id}
          cotacaoId={cotacaoId}
          fornecedorId={atual.fornecedorId}
          fornecedorNome={atual.nomeFornecedor ?? ""}
        />
      ) : atual.status === "PENDENTE" ? (
        <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">
          Este fornecedor ainda não respondeu — volte ao passo 2 pra colar a resposta dele.
        </div>
      ) : carregando || !preview ? (
        <div className="rounded-lg border border-bdr bg-card p-6 text-sm text-t2">Carregando...</div>
      ) : (
        <ConferenciaPendente
          key={atual.id}
          onConfirmado={() => onFornecedorConfirmado(atual.id)}
          onCancelarConferencia={() => onCancelarConferencia(atual)}
          cotacaoId={cotacaoId}
          fornecedorId={atual.fornecedorId}
          fornecedorNome={atual.nomeFornecedor ?? ""}
          textoOriginal={texto}
          preview={preview}
          estadoResolucao={estadoResolucao}
          onEstadoResolucaoChange={onEstadoResolucaoChange}
        />
      )}
    </div>
  );
}
