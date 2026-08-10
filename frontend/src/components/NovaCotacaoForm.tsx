"use client";

import { useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import { criarCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";

// Único fluxo de criação de cotação do app — usado tanto pelo bloco da Visão Geral
// quanto pelo modal disparado pela navbar quando não há cotação ativa. `onCriada`
// deixa quem embrulha o form decidir o pós-criação (ex: fechar modal e navegar pra
// aba clicada); sem ele, cai no comportamento padrão de ir pra Entrada de Dados.
export default function NovaCotacaoForm({
  onCriada,
  autoFocus,
}: {
  onCriada?: (cotacaoId: string) => void;
  autoFocus?: boolean;
}) {
  const router = useRouter();
  const [titulo, setTitulo] = useState("");
  const [criando, setCriando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!titulo.trim()) return;
    setCriando(true);
    setErro(null);
    try {
      const nova = await criarCotacao(titulo.trim());
      if (onCriada) {
        onCriada(nova.id);
      } else {
        router.push(`/cotacoes/${nova.id}/entrada`);
      }
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível criar a cotação."));
      setCriando(false);
    }
  }

  return (
    <div>
      <form onSubmit={onSubmit} className="flex gap-2">
        <input
          autoFocus={autoFocus}
          value={titulo}
          onChange={(e) => setTitulo(e.target.value)}
          placeholder="Título da nova cotação (ex: Cotação semanal 09/07)"
          className="flex-1 rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
        <button
          type="submit"
          disabled={criando}
          className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
        >
          Nova cotação
        </button>
      </form>
      {erro && <p className="mt-2 text-sm text-er">{erro}</p>}
    </div>
  );
}
