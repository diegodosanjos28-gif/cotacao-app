"use client";

import { useEffect, useState } from "react";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import Modal from "@/components/Modal";
import NovaCotacaoForm from "@/components/NovaCotacaoForm";
import {
  buscarCotacao,
  buscarCotacaoAtual,
  buscarLista,
  buscarProdutosPorIds,
  listarFornecedores,
  listarFornecedoresDaCotacao,
} from "@/lib/api";
import { setCotacaoAtivaId } from "@/lib/cotacaoAtiva";
import { getErrorMessage } from "@/lib/errors";
import { idsProdutosDosItens } from "@/lib/itensLista";
import { Cotacao, CotacaoAtualResponse, CotacaoFornecedorResponse, Fornecedor, ItemListaResponse, Produto } from "@/lib/types";
import CotacaoAtualCard from "./components/CotacaoAtualCard";
import CotacoesAnterioresCarrossel from "./components/CotacoesAnterioresCarrossel";
import HallFornecedores from "./components/HallFornecedores";
import AprovacaoModal from "./components/AprovacaoModal";

interface DadosModal {
  cotacao: Cotacao;
  itensLista: ItemListaResponse[];
  produtos: Produto[];
  cotacaoFornecedores: CotacaoFornecedorResponse[];
  fornecedores: Fornecedor[];
}

// Landing tenant-wide da Entrada de Dados — único ponto de entrada da aba (ver
// NavBar.tsx). Refactor 2026-08-20 (última leva): o fluxo de aprovação passou a ser
// centralizado no AprovacaoModal, hospedado AQUI — não existe mais uma rota separada
// /cotacoes/{id}/entrada; "Revisar e aprovar"/"Detalhes" abrem o modal direto sobre
// esta tela, que continua visível (card + carrossel + Hall) atrás dele.
function EntradaLandingContent() {
  const [criandoNova, setCriandoNova] = useState(false);
  const [cotacaoAtual, setCotacaoAtual] = useState<CotacaoAtualResponse | null | undefined>(undefined);
  const [erro, setErro] = useState<string | null>(null);
  const [dadosModal, setDadosModal] = useState<DadosModal | null>(null);
  const [modalAberto, setModalAberto] = useState(false);
  const [carregandoModal, setCarregandoModal] = useState(false);

  async function carregarCotacaoAtual() {
    try {
      setCotacaoAtual((await buscarCotacaoAtual()) ?? null);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar a cotação atual."));
    }
  }

  useEffect(() => {
    // carregarCotacaoAtual() só faz setState depois de um await.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    carregarCotacaoAtual();
  }, []);

  async function recarregarFornecedoresDaCotacao() {
    if (!dadosModal) return;
    try {
      const cf = await listarFornecedoresDaCotacao(dadosModal.cotacao.id);
      setDadosModal((d) => (d ? { ...d, cotacaoFornecedores: cf } : d));
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível atualizar os fornecedores da cotação."));
    }
  }

  function onFornecedorSalvo(f: Fornecedor) {
    setDadosModal((d) => {
      if (!d) return d;
      const existe = d.fornecedores.some((p) => p.id === f.id);
      const fornecedores = existe ? d.fornecedores.map((p) => (p.id === f.id ? f : p)) : [...d.fornecedores, f];
      return { ...d, fornecedores };
    });
  }

  function onFornecedorInativado(id: string) {
    setDadosModal((d) => (d ? { ...d, fornecedores: d.fornecedores.filter((f) => f.id !== id) } : d));
  }

  async function abrirModalParaId(id: string) {
    if (carregandoModal) return;
    setCarregandoModal(true);
    setErro(null);
    try {
      const [c, f, cf, itens] = await Promise.all([
        buscarCotacao(id),
        listarFornecedores(),
        listarFornecedoresDaCotacao(id),
        buscarLista(id),
      ]);
      const catalogo = await buscarProdutosPorIds(idsProdutosDosItens(itens));
      setDadosModal({ cotacao: c, itensLista: itens, produtos: catalogo, cotacaoFornecedores: cf, fornecedores: f });
      setModalAberto(true);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar a cotação."));
    } finally {
      setCarregandoModal(false);
    }
  }

  function abrirModal() {
    if (cotacaoAtual) abrirModalParaId(cotacaoAtual.id);
  }

  function fecharModal() {
    setModalAberto(false);
    // Refaz a busca da landing pra refletir mudanças feitas dentro do modal (itens,
    // fornecedores, status) — ex.: cotação finalizada some do card, contadores mudam.
    carregarCotacaoAtual();
  }

  // "Criar cotação pela web" abre o AprovacaoModal direto pra cotação recém-criada
  // (aba 1, lista ainda vazia) — sem isso o operador cairia numa tela morta, já que
  // adicionar produto só existe dentro do modal agora.
  async function onCriada(novaId: string) {
    setCotacaoAtivaId(novaId);
    setCriandoNova(false);
    await abrirModalParaId(novaId);
    carregarCotacaoAtual();
  }

  return (
    <>
      <NavBar />
      <main className="w-full flex-1 px-6 py-8">
        <div className="mb-2 text-[11px] font-bold uppercase tracking-[1.1px] text-t3">Entrada de Dados</div>
        <h1 className="mb-5 text-[30px] font-extrabold tracking-tight text-t1">Cotação atual</h1>

        {erro && <p className="mb-4 text-sm text-er">{erro}</p>}

        <div className="grid items-stretch gap-5 md:grid-cols-[minmax(380px,1fr)_2fr]">
          <CotacaoAtualCard
            cotacaoAtual={cotacaoAtual ?? null}
            onRevisarEAprovar={abrirModal}
            onDetalhes={abrirModal}
            onCriarCotacao={() => setCriandoNova(true)}
          />
          <CotacoesAnterioresCarrossel />
        </div>

        <HallFornecedores cotacaoAtual={cotacaoAtual ?? null} />
      </main>

      <Modal open={criandoNova} onClose={() => setCriandoNova(false)} title="Nova cotação">
        <NovaCotacaoForm onCriada={onCriada} autoFocus />
      </Modal>

      {dadosModal && (
        <AprovacaoModal
          open={modalAberto}
          onClose={fecharModal}
          cotacaoId={dadosModal.cotacao.id}
          cotacao={dadosModal.cotacao}
          itensLista={dadosModal.itensLista}
          produtos={dadosModal.produtos}
          onListaAtualizada={(itens) => setDadosModal((d) => (d ? { ...d, itensLista: itens } : d))}
          onProdutosAtualizados={(produtos) => setDadosModal((d) => (d ? { ...d, produtos } : d))}
          cotacaoFornecedores={dadosModal.cotacaoFornecedores}
          todosFornecedores={dadosModal.fornecedores}
          onFornecedorAtualizado={onFornecedorSalvo}
          onFornecedorInativado={onFornecedorInativado}
          onCotacaoFornecedoresAtualizados={recarregarFornecedoresDaCotacao}
          onCotacaoAtualizada={(cotacao) => setDadosModal((d) => (d ? { ...d, cotacao } : d))}
        />
      )}
    </>
  );
}

export default function EntradaLandingPage() {
  return (
    <AuthGuard>
      <EntradaLandingContent />
    </AuthGuard>
  );
}
