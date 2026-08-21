"use client";

import { useEffect, useState } from "react";
import { fornecedoresHistorico } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { CotacaoAtualResponse, FornecedorHistoricoResponse } from "@/lib/types";
import { StoreIcon } from "@/components/icons/HallIcons";
import HallFornecedorCard from "./HallFornecedorCard";

// 2 modos: "ativa" lê cotacaoAtual.fornecedores direto (sem fetch próprio);
// "histórico" busca GET /fornecedores/historico quando não há cotação em andamento.
export default function HallFornecedores({ cotacaoAtual }: { cotacaoAtual: CotacaoAtualResponse | null }) {
  const [historico, setHistorico] = useState<FornecedorHistoricoResponse[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    if (cotacaoAtual) return;
    let ignorar = false;
    fornecedoresHistorico()
      .then((dados) => {
        if (!ignorar) setHistorico(dados);
      })
      .catch((err) => {
        if (!ignorar) setErro(getErrorMessage(err, "Não foi possível carregar o histórico de fornecedores."));
      });
    return () => {
      ignorar = true;
    };
  }, [cotacaoAtual]);

  const ativa = cotacaoAtual != null;
  const respondidos = ativa ? cotacaoAtual.fornecedores.filter((f) => f.status !== "PENDENTE").length : 0;
  const total = ativa ? cotacaoAtual.fornecedores.length : (historico?.length ?? 0);

  return (
    <div className="mt-8">
      <div className="mb-3.5 flex flex-wrap items-end justify-between gap-3">
        <div className="flex items-center gap-2.5 text-base font-medium tracking-tight text-t1">
          <span className="text-prx">
            <StoreIcon />
          </span>
          {ativa ? "Fornecedores na concorrência" : "Fornecedores cadastrados"}
          <span className="rounded-full border border-prx/28 bg-prx/[.12] px-2.5 py-0.5 text-[11px] font-medium text-prx-l">{total}</span>
        </div>
        <span className="text-xs font-normal text-t3">
          {ativa
            ? `${respondidos} responderam · ${total - respondidos} pendente${total - respondidos === 1 ? "" : "s"}`
            : "Histórico completo · todas as cotações"}
        </span>
      </div>

      <p className="mb-3.5 -mt-1 flex items-center gap-1.5 text-[11px] text-t3">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="shrink-0 text-prx">
          <circle cx="12" cy="12" r="10" />
          <path d="M12 16v-4M12 8h.01" />
        </svg>
        {ativa
          ? "Prévia — os dados de resposta e cobertura desta cotação ainda não foram lançados no Comparativo / Mapa de Compra."
          : "Médias consolidadas de todas as cotações em que cada fornecedor já participou."}
      </p>

      {erro && <p className="mb-3 text-sm text-er">{erro}</p>}

      {ativa && cotacaoAtual.fornecedores.length === 0 && (
        <p className="py-6 text-center text-sm text-t2">Nenhum fornecedor foi adicionado a esta cotação ainda.</p>
      )}

      {ativa && cotacaoAtual.fornecedores.length > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {cotacaoAtual.fornecedores.map((f) => (
            <HallFornecedorCard key={f.fornecedorId} modo="ativa" fornecedor={f} itensListaBase={cotacaoAtual.itensListaBase} />
          ))}
        </div>
      )}

      {!ativa && historico === null && !erro && <p className="py-6 text-center text-sm text-t2">Carregando...</p>}

      {!ativa && historico !== null && historico.length === 0 && (
        <p className="py-6 text-center text-sm text-t2">Nenhum fornecedor cadastrado ainda.</p>
      )}

      {!ativa && historico !== null && historico.length > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {historico.map((f) => (
            <HallFornecedorCard key={f.fornecedorId} modo="historico" fornecedor={f} />
          ))}
        </div>
      )}
    </div>
  );
}
