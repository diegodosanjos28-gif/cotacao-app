"use client";

import { SetStateAction, use, useEffect, useState } from "react";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatusBadge from "@/components/StatusBadge";
import {
  buscarCotacao,
  buscarLista,
  buscarProdutos,
  buscarRespostaPersistida,
  cancelarRespostaFornecedor,
  concluirAjusteLista,
  enviarResposta,
  listarFornecedores,
  listarFornecedoresDaCotacao,
} from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import {
  Cotacao,
  CotacaoFornecedorResponse,
  EstadoResolucao,
  Fornecedor,
  ItemListaResponse,
  PreviewRespostaResponse,
  Produto,
  ResolucaoItemRequest,
} from "@/lib/types";
import GridProdutosSection from "./components/GridProdutosSection";
import GuiaFormatacao from "./components/GuiaFormatacao";
import FornecedoresSidebar from "./components/FornecedoresSidebar";
import FornecedoresCotacoesSection from "./components/FornecedoresCotacoesSection";
import EntradaFooter from "./components/EntradaFooter";

// Rascunho da Conferência de um fornecedor — vive por cotacaoFornecedorId, não como
// estado único da página, pra que trocar de fornecedor (ou fechar o modal) não perca
// nem misture texto colado, preview e resoluções em andamento de outro fornecedor.
interface RascunhoFornecedor {
  texto: string;
  preview: PreviewRespostaResponse | null;
  modalAberto: boolean;
  resolucoes: Record<string, ResolucaoItemRequest>;
  spinOffs: Record<string, ResolucaoItemRequest[]>;
  excluidos: Record<string, Set<string>>;
}

function rascunhoVazio(): RascunhoFornecedor {
  return { texto: "", preview: null, modalAberto: false, resolucoes: {}, spinOffs: {}, excluidos: {} };
}

function EntradaContent({ cotacaoId }: { cotacaoId: string }) {
  const [cotacao, setCotacao] = useState<Cotacao | null>(null);
  const [fornecedores, setFornecedores] = useState<Fornecedor[]>([]);
  const [cotacaoFornecedores, setCotacaoFornecedores] = useState<CotacaoFornecedorResponse[]>([]);
  const [itensLista, setItensLista] = useState<ItemListaResponse[]>([]);
  const [produtos, setProdutos] = useState<Produto[]>([]);
  const [erro, setErro] = useState<string | null>(null);
  const [fornecedorAtivo, setFornecedorAtivo] = useState<CotacaoFornecedorResponse | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [concluindoAjuste, setConcluindoAjuste] = useState(false);
  const [rascunhos, setRascunhos] = useState<Record<string, RascunhoFornecedor>>({});

  const rascunhoAtivo = fornecedorAtivo ? (rascunhos[fornecedorAtivo.id] ?? rascunhoVazio()) : rascunhoVazio();
  const texto = rascunhoAtivo.texto;
  const preview = rascunhoAtivo.preview;

  function atualizarRascunho(id: string, patch: Partial<RascunhoFornecedor>) {
    setRascunhos((prev) => ({ ...prev, [id]: { ...(prev[id] ?? rascunhoVazio()), ...patch } }));
  }

  function setTexto(valor: SetStateAction<string>) {
    if (!fornecedorAtivo) return;
    const novoTexto = typeof valor === "function" ? valor(rascunhoAtivo.texto) : valor;
    atualizarRascunho(fornecedorAtivo.id, { texto: novoTexto });
  }

  const estadoResolucao: EstadoResolucao = {
    resolucoes: rascunhoAtivo.resolucoes,
    spinOffs: rascunhoAtivo.spinOffs,
    excluidos: rascunhoAtivo.excluidos,
  };

  function onEstadoResolucaoChange(patch: Partial<RascunhoFornecedor>) {
    if (fornecedorAtivo) atualizarRascunho(fornecedorAtivo.id, patch);
  }

  async function carregar() {
    try {
      const c = await buscarCotacao(cotacaoId);
      // Cotação WhatsApp com lista ainda não revisada (Prompt 12): antes redirecionava
      // pra uma rota separada (/ajuste-lista, removida); agora o mesmo grid unificado
      // atende os dois casos nesta página — só o restante da tela (fornecedores/
      // conferência) fica escondido até "Concluir ajuste" (ver precisaAjuste abaixo).
      const [f, cf, itens, catalogo] = await Promise.all([
        listarFornecedores(),
        listarFornecedoresDaCotacao(cotacaoId),
        buscarLista(cotacaoId),
        buscarProdutos(),
      ]);
      setCotacao(c);
      setFornecedores(f);
      setCotacaoFornecedores(cf);
      setItensLista(itens);
      setProdutos(catalogo);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar a cotação."));
    }
  }

  async function recarregarFornecedoresDaCotacao() {
    try {
      setCotacaoFornecedores(await listarFornecedoresDaCotacao(cotacaoId));
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível atualizar os fornecedores da cotação."));
    }
  }

  function onAtivoAlterado(cf: CotacaoFornecedorResponse | null) {
    setFornecedorAtivo(cf);
  }

  async function onProcessar() {
    if (!fornecedorAtivo || !texto.trim()) return;
    setEnviando(true);
    setErro(null);
    try {
      const resultado = await enviarResposta(cotacaoId, fornecedorAtivo.fornecedorId, texto);
      atualizarRascunho(fornecedorAtivo.id, { preview: resultado, modalAberto: true });
      await recarregarFornecedoresDaCotacao();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível processar a resposta."));
    } finally {
      setEnviando(false);
    }
  }

  // Ponto único de abertura da Conferência — unifica os dois fluxos que existiam
  // separados: "Continuar Conferência" (reusar um preview já em memória, sem rede) e
  // "Conferir resposta do fornecedor" (reconstruir de texto já persistido, caminho
  // WhatsApp — WhatsappRespostaFornecedorService persiste direto em
  // cotacao_produto_fornecedor, sem nunca passar por preview). Recebe o
  // cotacaoFornecedor alvo explicitamente em vez de depender de fornecedorAtivo — quem
  // chama (FornecedoresCotacoesSection) pode estar mudando de fornecedor ativo no
  // mesmo clique, inclusive em cadeia automática após confirmar outro fornecedor.
  // Retorna true se um modal foi de fato aberto — o chamador usa isso pra decidir se
  // um encadeamento automático deve parar. Nunca lança: ConferenciaModal.confirmar()
  // chama onConfirmado() sem await dentro do próprio try, então uma rejeição vinda
  // daqui escaparia como unhandled rejection.
  async function onConferirResposta(cf: CotacaoFornecedorResponse): Promise<boolean> {
    setErro(null);
    const rascunho = rascunhos[cf.id];

    // 1) Preview já em memória (processou e fechou sem confirmar) — reabre
    //    exatamente como foi deixado, sem chamada de rede.
    if (rascunho?.preview) {
      atualizarRascunho(cf.id, { modalAberto: true });
      return true;
    }

    try {
      // 2) Texto colado na tela ainda não processado  →  3) texto já persistido
      //    (caminho WhatsApp: nunca passou por preview).
      let textoParaConferir = rascunho?.texto?.trim() ? rascunho.texto : "";
      if (!textoParaConferir) {
        const { texto: persistido } = await buscarRespostaPersistida(cotacaoId, cf.fornecedorId);
        textoParaConferir = persistido;
      }
      if (!textoParaConferir.trim()) {
        setErro(
          "Não há resposta registrada para conferir deste fornecedor. Cole a resposta no campo ao lado e clique em Processar Cotação.",
        );
        return false;
      }
      const resultado = await enviarResposta(cotacaoId, cf.fornecedorId, textoParaConferir);
      atualizarRascunho(cf.id, { texto: textoParaConferir, preview: resultado, modalAberto: true });
      await recarregarFornecedoresDaCotacao();
      return true;
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar a resposta para conferência."));
      return false;
    }
  }

  // "Cancelar Conferência" (achado do usuário, 2026-08-04): diferente de onClosePreview
  // (que só esconde o modal preservando tudo), isto apaga a resposta do fornecedor de
  // verdade — no backend (DELETE .../resposta, volta cotacao_fornecedor.status pra
  // PENDENTE) e no rascunho local (texto/preview/resoluções). Sem o DELETE no backend,
  // "Conferir resposta do fornecedor" reconstruiria a mesma resposta cancelada no
  // próximo clique (ver onConferirResposta, branch 3). Propositalmente NÃO captura
  // erro aqui — deixa propagar pra ConferenciaModal.cancelarConferencia(), que mostra
  // o erro dentro do próprio modal (mesmo padrão de confirmar()), não no banner do
  // topo da página.
  async function onCancelarConferencia(cf: CotacaoFornecedorResponse) {
    await cancelarRespostaFornecedor(cotacaoId, cf.fornecedorId);
    atualizarRascunho(cf.id, {
      texto: "",
      preview: null,
      resolucoes: {},
      spinOffs: {},
      excluidos: {},
      modalAberto: false,
    });
    await recarregarFornecedoresDaCotacao();
  }

  useEffect(() => {
    // carregar() só faz setState depois de um await.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    carregar();
    // carregar não entra nas deps de propósito (recriada a cada render, entraria em loop).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cotacaoId]);

  function onFornecedorSalvo(f: Fornecedor) {
    setFornecedores((prev) => {
      const existe = prev.some((p) => p.id === f.id);
      return existe ? prev.map((p) => (p.id === f.id ? f : p)) : [...prev, f];
    });
  }

  function onFornecedorInativado(id: string) {
    setFornecedores((prev) => prev.filter((f) => f.id !== id));
  }

  // Cotação WhatsApp cujo parse inicial ainda não foi revisado pelo operador — gate
  // que antes era a rota separada /ajuste-lista (Prompt 12: dobrado nesta mesma
  // página). Enquanto precisaAjuste, o restante da tela (Fornecedores/Conferência)
  // fica escondido: o grid unificado é a única coisa visível, igual ao comportamento
  // anterior, só sem navegação para uma rota própria.
  const precisaAjuste = cotacao != null && cotacao.canalOrigem === "WHATSAPP" && !cotacao.listaRevisada;

  async function onConcluirAjuste() {
    setConcluindoAjuste(true);
    setErro(null);
    try {
      setCotacao(await concluirAjusteLista(cotacaoId));
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível concluir o ajuste."));
    } finally {
      setConcluindoAjuste(false);
    }
  }

  if (!cotacao) {
    return (
      <>
        <NavBar />
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
          {erro ? <p className="text-sm text-er">{erro}</p> : <p className="text-sm text-t2">Carregando...</p>}
        </main>
      </>
    );
  }

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8 space-y-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-t1">{cotacao.titulo}</h1>
          <div className="mt-1">
            <StatusBadge status={cotacao.status} />
          </div>
        </div>

        {erro && <p className="text-sm text-er">{erro}</p>}

        {precisaAjuste && (
          <p className="rounded-md border border-wa/30 bg-wa-d px-4 py-3 text-sm text-t2">
            Revise os itens recebidos por WhatsApp antes de seguir para a conferência de fornecedores —
            corrija quantidade, unidade ou o produto identificado. Fornecedores e Conferência ficam
            disponíveis depois de concluir este ajuste.
          </p>
        )}

        <div className={precisaAjuste ? "" : "grid gap-6 lg:grid-cols-[2fr_1fr]"}>
          <div className="flex flex-col">
            <GridProdutosSection
              cotacaoId={cotacaoId}
              itens={itensLista}
              produtos={produtos}
              onListaAtualizada={setItensLista}
              onProdutosAtualizados={setProdutos}
              setErro={setErro}
              cotacaoFinalizada={cotacao.status === "FINALIZADA"}
            />
          </div>
          {!precisaAjuste && (
            <div className="flex h-full flex-col gap-6">
              <GuiaFormatacao />
              {/* flex-1 + min-h-0: faz o card de Fornecedores esticar até a mesma
                  altura do grid à esquerda (CSS Grid estica as duas colunas pra
                  altura da mais alta) em vez de sobrar espaço em branco invisível
                  abaixo dele quando o grid tem mais itens — min-h-0 é o que permite o
                  scroll interno (overflow-y-auto) funcionar dentro de um item flex,
                  senão ele nunca encolhe abaixo da altura do conteúdo. */}
              <div className="flex min-h-0 flex-1 flex-col">
                <FornecedoresSidebar
                  fornecedores={fornecedores}
                  onFornecedorSalvo={onFornecedorSalvo}
                  onFornecedorInativado={onFornecedorInativado}
                />
              </div>
            </div>
          )}
        </div>

        {precisaAjuste ? (
          <div className="flex items-center justify-end">
            <button
              type="button"
              onClick={onConcluirAjuste}
              disabled={concluindoAjuste || itensLista.length === 0}
              className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:cursor-not-allowed disabled:opacity-50"
            >
              {concluindoAjuste ? "Concluindo..." : "Concluir ajuste e seguir para conferência"}
            </button>
          </div>
        ) : (
          <>
            <FornecedoresCotacoesSection
              cotacao={cotacao}
              cotacaoId={cotacaoId}
              cotacaoFornecedores={cotacaoFornecedores}
              todosFornecedores={fornecedores}
              onCotacaoFornecedoresAtualizados={recarregarFornecedoresDaCotacao}
              onFornecedorAtualizado={onFornecedorSalvo}
              onAtivoAlterado={onAtivoAlterado}
              onConferirResposta={onConferirResposta}
              onCancelarConferencia={onCancelarConferencia}
              texto={texto}
              setTexto={setTexto}
              preview={preview}
              modalAberto={rascunhoAtivo.modalAberto}
              onClosePreview={() => onEstadoResolucaoChange({ modalAberto: false })}
              estadoResolucao={estadoResolucao}
              onEstadoResolucaoChange={onEstadoResolucaoChange}
              setErro={setErro}
            />

            <EntradaFooter
              cotacao={cotacao}
              numFornecedores={cotacaoFornecedores.length}
              onCotacaoAtualizada={setCotacao}
              onProcessar={onProcessar}
              processando={enviando}
              podeProcessar={fornecedorAtivo != null && texto.trim() !== ""}
              setErro={setErro}
            />
          </>
        )}
      </main>
    </>
  );
}

export default function EntradaPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <AuthGuard>
      <EntradaContent cotacaoId={id} />
    </AuthGuard>
  );
}
