"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import AuthGuard from "@/components/AuthGuard";
import NavBar from "@/components/NavBar";
import Modal from "@/components/Modal";
import NovaCotacaoForm from "@/components/NovaCotacaoForm";
import { buscarCotacaoAtual } from "@/lib/api";
import { setCotacaoAtivaId } from "@/lib/cotacaoAtiva";
import { useAsync } from "@/hooks/useAsync";
import CotacaoAtualCard from "./components/CotacaoAtualCard";
import CotacoesAnterioresCarrossel from "./components/CotacoesAnterioresCarrossel";
import HallFornecedores from "./components/HallFornecedores";

// Landing tenant-wide da Entrada de Dados — substitui o antigo botão fixo "+ Nova
// Cotação"/modal de criação como ponto único de entrada da aba (ver NavBar.tsx): daqui
// pra frente, criar cotação só acontece pelo CTA do card vazio; revisar/editar uma
// cotação em andamento acontece em /cotacoes/{id}/entrada.
function EntradaLandingContent() {
  const router = useRouter();
  const [criandoNova, setCriandoNova] = useState(false);
  const { data, erro } = useAsync(() => buscarCotacaoAtual(), [], "Não foi possível carregar a cotação atual.");
  const cotacaoAtual = data ?? null;

  function onCriada(novaId: string) {
    setCotacaoAtivaId(novaId);
    setCriandoNova(false);
    router.push(`/cotacoes/${novaId}/entrada`);
  }

  return (
    <>
      <NavBar />
      <main className="mx-auto w-full max-w-[1400px] flex-1 px-6 py-8">
        <div className="mb-2 text-[11px] font-bold uppercase tracking-[1.1px] text-t3">Entrada de Dados</div>
        <h1 className="mb-5 text-[30px] font-extrabold tracking-tight text-t1">Cotação atual</h1>

        {erro && <p className="mb-4 text-sm text-er">{erro}</p>}

        <div className="grid items-stretch gap-5 md:grid-cols-[minmax(380px,1fr)_2fr]">
          <CotacaoAtualCard
            cotacaoAtual={cotacaoAtual}
            onRevisarEAprovar={() => cotacaoAtual && router.push(`/cotacoes/${cotacaoAtual.id}/entrada?revisar=1`)}
            onDetalhes={() => cotacaoAtual && router.push(`/cotacoes/${cotacaoAtual.id}/entrada`)}
            onCriarCotacao={() => setCriandoNova(true)}
          />
          <CotacoesAnterioresCarrossel />
        </div>

        <HallFornecedores cotacaoAtual={cotacaoAtual} />
      </main>

      <Modal open={criandoNova} onClose={() => setCriandoNova(false)} title="Nova cotação">
        <NovaCotacaoForm onCriada={onCriada} autoFocus />
      </Modal>
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
