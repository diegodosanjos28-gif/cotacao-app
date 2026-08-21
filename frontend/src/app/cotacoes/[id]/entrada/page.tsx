"use client";

import { SetStateAction, use, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import StatusBadge from "@/components/StatusBadge";
import {
  buscarCotacao,
  buscarLista,
  buscarProdutosPorIds,
  concluirAjusteLista,
  enviarResposta,
  listarFornecedores,
  listarFornecedoresDaCotacao,
} from "@/lib/api";
import { idsProdutosDosItens } from "@/lib/itensLista";
import { getErrorMessage } from "@/lib/errors";
import { Cotacao, CotacaoFornecedorResponse, Fornecedor, ItemListaResponse, Produto } from "@/lib/types";
import GridProdutosSection from "./components/GridProdutosSection";
import FornecedoresCotacoesSection from "./components/FornecedoresCotacoesSection";
import EntradaFooter from "./components/EntradaFooter";
import EntradaStepper, { PassoEntrada, PassoInfo } from "./components/EntradaStepper";
import AprovacaoModal, { SeedRascunho } from "@/app/entrada/components/AprovacaoModal";

// Passo inicial da timeline (Prompt 25) — "retoma de onde parou" em vez de sempre
// abrir no passo 1, calculado só na 1ª carga da cotação (ver carregar()). A partir do
// refactor de 2026-08-20 (Fase D), PROCESSADO não abre mais um passo 3 próprio — a
// Conferência virou o AprovacaoModal, alcançado via "Revisar e aprovar"/"?revisar=1".
function calcularPassoInicial(cfs: CotacaoFornecedorResponse[]): PassoEntrada {
  return cfs.length > 0 ? 2 : 1;
}

function EntradaContent({ cotacaoId }: { cotacaoId: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const [cotacao, setCotacao] = useState<Cotacao | null>(null);
  const [fornecedores, setFornecedores] = useState<Fornecedor[]>([]);
  const [cotacaoFornecedores, setCotacaoFornecedores] = useState<CotacaoFornecedorResponse[]>([]);
  const [itensLista, setItensLista] = useState<ItemListaResponse[]>([]);
  const [produtos, setProdutos] = useState<Produto[]>([]);
  const [erro, setErro] = useState<string | null>(null);
  const [fornecedorAtivo, setFornecedorAtivo] = useState<CotacaoFornecedorResponse | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [concluindoAjuste, setConcluindoAjuste] = useState(false);
  const [passoAtivo, setPassoAtivo] = useState<PassoEntrada>(1);

  // Só o texto sendo digitado/colado por fornecedor no Passo 2 (textarea) — preview,
  // resoluções e demais estado da Conferência migraram pro AprovacaoModal (Fase C/D
  // do refactor, 2026-08-20), que agora é quem "possui" esse rascunho.
  const [textosResposta, setTextosResposta] = useState<Record<string, string>>({});
  const texto = fornecedorAtivo ? (textosResposta[fornecedorAtivo.id] ?? "") : "";

  function setTexto(valor: SetStateAction<string>) {
    if (!fornecedorAtivo) return;
    setTextosResposta((prev) => {
      const atual = prev[fornecedorAtivo.id] ?? "";
      const novo = typeof valor === "function" ? (valor as (prev: string) => string)(atual) : valor;
      return { ...prev, [fornecedorAtivo.id]: novo };
    });
  }

  const [modalAberto, setModalAberto] = useState(false);
  const [modalAbaInicial, setModalAbaInicial] = useState<1 | 2>(1);
  const [modalFornecedorFoco, setModalFornecedorFoco] = useState<string | null>(null);
  const [modalSeedRascunho, setModalSeedRascunho] = useState<SeedRascunho | null>(null);

  function abrirModal(opts: { aba: 1 | 2; fornecedorFocoId?: string | null; seed?: SeedRascunho | null }) {
    setModalAbaInicial(opts.aba);
    setModalFornecedorFoco(opts.fornecedorFocoId ?? null);
    setModalSeedRascunho(opts.seed ?? null);
    setModalAberto(true);
  }

  function fecharModal() {
    setModalAberto(false);
  }

  async function carregar() {
    try {
      const c = await buscarCotacao(cotacaoId);
      // Cotação WhatsApp com lista ainda não revisada (Prompt 12): antes redirecionava
      // pra uma rota separada (/ajuste-lista, removida); agora o mesmo grid unificado
      // atende os dois casos nesta página — só o restante da tela (fornecedores/
      // aprovação) fica escondido até "Concluir ajuste" (ver precisaAjuste abaixo).
      const [f, cf, itens] = await Promise.all([
        listarFornecedores(),
        listarFornecedoresDaCotacao(cotacaoId),
        buscarLista(cotacaoId),
      ]);
      // Só os produtos já referenciados pelos itens desta cotação — bounded pelo
      // tamanho da lista, não o catálogo inteiro do tenant (ver ProdutoAutocomplete,
      // que busca sugestões novas sob demanda, paginado, no servidor).
      const catalogo = await buscarProdutosPorIds(idsProdutosDosItens(itens));
      setCotacao(c);
      setFornecedores(f);
      setCotacaoFornecedores(cf);
      setItensLista(itens);
      setProdutos(catalogo);
      setPassoAtivo(calcularPassoInicial(cf));
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
      await recarregarFornecedoresDaCotacao();
      // Processar já entrega pra conferência (achado do usuário, 2026-08-16) — antes
      // isso significava ir pro passo 3; agora abre o AprovacaoModal direto na aba de
      // Fornecedores, com o preview recém-obtido semeado (sem refazer a chamada de
      // rede que a aba faria sozinha via onConferirResposta).
      abrirModal({
        aba: 2,
        fornecedorFocoId: fornecedorAtivo.id,
        seed: { cfId: fornecedorAtivo.id, texto, preview: resultado },
      });
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível processar a resposta."));
    } finally {
      setEnviando(false);
    }
  }

  function onTextoLimpo(cfId: string) {
    setTextosResposta((prev) => {
      const resto = { ...prev };
      delete resto[cfId];
      return resto;
    });
  }

  useEffect(() => {
    // carregar() só faz setState depois de um await.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    carregar();
    // carregar não entra nas deps de propósito (recriada a cada render, entraria em loop).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cotacaoId]);

  // Landing (/entrada) navega pra cá com ?revisar=1 quando o operador clica "Revisar e
  // aprovar" direto do card da cotação atual — abre o modal já na aba 1 assim que a
  // cotação carrega, e remove o param da URL (evita reabrir num refresh manual da
  // página). Ver decisão de design do refactor 2026-08-20: o modal só é hospedado
  // aqui, não duplicado na landing.
  useEffect(() => {
    if (!cotacao) return;
    if (searchParams.get("revisar") === "1") {
      abrirModal({ aba: 1 });
      router.replace(pathname);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cotacao, searchParams]);

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
  // página). Enquanto precisaAjuste, o restante da tela (Fornecedores/Aprovação) fica
  // escondido: o grid unificado é a única coisa visível, igual ao comportamento
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
        <main className="mx-auto w-full max-w-[1680px] flex-1 px-6 py-8">
          {erro ? <p className="text-sm text-er">{erro}</p> : <p className="text-sm text-t2">Carregando...</p>}
        </main>
      </>
    );
  }

  // Labels/marcadores da timeline (Prompt 25) — reaproveita a mesma definição de
  // "pendente pra conferir" já usada em FornecedoresCotacoesSection (status !==
  // CONFIRMADO), sem inventar um segundo critério de pendência.
  const confirmadosCount = cotacaoFornecedores.filter((cf) => cf.status === "CONFIRMADO").length;
  const passos: PassoInfo[] = [
    { numero: 1, label: "Lista de produtos", done: itensLista.length > 0 },
    {
      numero: 2,
      label:
        cotacaoFornecedores.length === 0
          ? "Fornecedores e cotações"
          : `${confirmadosCount} de ${cotacaoFornecedores.length} fornecedores`,
      done: cotacaoFornecedores.length > 0 && confirmadosCount === cotacaoFornecedores.length,
    },
  ];

  return (
    <>
      <NavBar />
      {/* Altura fixa (viewport - NavBar de 3.5rem) e sem scroll na página inteira
          (Prompt 25, feedback 2026-08-16) — só a área do passo ativo, logo abaixo,
          rola internamente se o conteúdo não couber; header, stepper e o rodapé de
          ações ficam sempre visíveis, sem precisar rolar. max-w bem mais largo que o
          antigo max-w-6xl pra não sobrar tanto espaço em branco nas laterais em
          telas largas. */}
      <main className="mx-auto flex h-[calc(100vh-3.5rem)] w-full max-w-[1680px] flex-col overflow-hidden px-6 py-6">
        <div className="shrink-0 space-y-3">
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
              corrija quantidade, unidade ou o produto identificado. Fornecedores e Aprovação ficam
              disponíveis depois de concluir este ajuste.
            </p>
          )}
        </div>

        {precisaAjuste ? (
          <div className="mt-4 flex min-h-0 flex-1 flex-col gap-4">
            <div className="min-h-0 flex-1 overflow-y-auto">
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
            <div className="shrink-0 flex items-center justify-end">
              <button
                type="button"
                onClick={onConcluirAjuste}
                disabled={concluindoAjuste || itensLista.length === 0}
                className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:cursor-not-allowed disabled:opacity-50"
              >
                {concluindoAjuste ? "Concluindo..." : "Concluir ajuste e seguir para conferência"}
              </button>
            </div>
          </div>
        ) : (
          <>
            <div className="mt-4 shrink-0">
              <EntradaStepper passos={passos} passoAtivo={passoAtivo} onSelecionar={setPassoAtivo} />
            </div>

            {/* Única área com scroll interno da tela — o passo ativo (o outro fica
                escondido via classe, sempre montado, ver comentários abaixo). */}
            <div className="mt-4 min-h-0 flex-1 overflow-y-auto">
              {/* Passo 1 — Lista de produtos. Grid ocupa a tela toda; o Guia de
                  formatação virou um ícone de dica dentro do próprio Card (ver
                  GridProdutosSection), no lugar de uma coluna lateral fixa ou de uma
                  linha própria acima do Card (que desalinhava a altura deste passo com
                  a do passo 2 — achado do usuário, 2026-08-16). Fica sempre montado
                  (nunca desmontado condicionalmente) e só escondido via classe:
                  GridProdutosSection guarda rascunho local (ex: modal "Colar do
                  WhatsApp" com texto ainda não importado) que se perderia se o
                  componente desmontasse ao trocar de passo. */}
              <div className={passoAtivo === 1 ? "flex h-full flex-col gap-3" : "hidden"}>
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

              {/* Passo 2 — Fornecedores e cotações: dados cadastrais + colar/processar
                  texto. Sempre montado (mesmo motivo do passo 1) — estado local do
                  painel de fornecedores não pode se perder ao trocar de passo. A
                  Conferência (Prompt 26) deixou de ser um 3º passo — virou o
                  AprovacaoModal (Fase C/D, 2026-08-20), acionado pelo rodapé. */}
              <div className={passoAtivo === 2 ? "" : "hidden"}>
                <FornecedoresCotacoesSection
                  cotacao={cotacao}
                  cotacaoId={cotacaoId}
                  cotacaoFornecedores={cotacaoFornecedores}
                  todosFornecedores={fornecedores}
                  onCotacaoFornecedoresAtualizados={recarregarFornecedoresDaCotacao}
                  onFornecedorAtualizado={onFornecedorSalvo}
                  onFornecedorInativado={onFornecedorInativado}
                  onAtivoAlterado={onAtivoAlterado}
                  texto={texto}
                  setTexto={setTexto}
                  ativo={passoAtivo === 2}
                  setErro={setErro}
                />
              </div>
            </div>

            <div className="shrink-0">
              <EntradaFooter
                cotacao={cotacao}
                numFornecedores={cotacaoFornecedores.length}
                passoAtivo={passoAtivo}
                podeAvancarPasso1={itensLista.length > 0}
                onAvancarPasso={() => setPassoAtivo(2)}
                onProcessar={onProcessar}
                processando={enviando}
                podeProcessar={fornecedorAtivo != null && texto.trim() !== ""}
                onAbrirAprovacao={() => abrirModal({ aba: 1 })}
              />
            </div>
          </>
        )}
      </main>

      <AprovacaoModal
        open={modalAberto}
        onClose={fecharModal}
        cotacaoId={cotacaoId}
        cotacao={cotacao}
        itensLista={itensLista}
        produtos={produtos}
        onListaAtualizada={setItensLista}
        cotacaoFornecedores={cotacaoFornecedores}
        onCotacaoFornecedoresAtualizados={recarregarFornecedoresDaCotacao}
        onCotacaoAtualizada={setCotacao}
        abaInicial={modalAbaInicial}
        fornecedorFocoId={modalFornecedorFoco}
        seedRascunho={modalSeedRascunho}
        onTextoLimpo={onTextoLimpo}
      />
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
