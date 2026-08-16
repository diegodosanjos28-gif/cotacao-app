"use client";

import { useState } from "react";
import Modal from "@/components/Modal";
import { UNIDADES } from "@/lib/unidades";

// Botão com ícone (Prompt 25) — abre o guia em modal e some da tela ao fechar, em vez
// de ocupar a coluna lateral o tempo todo (útil só na primeira vez que o operador cola
// uma lista). Sem persistência entre sessões: sempre fechado ao carregar a página.
export default function GuiaFormatacao() {
  const [aberto, setAberto] = useState(false);

  return (
    <>
      <button
        type="button"
        onClick={() => setAberto(true)}
        title="Guia de formatação"
        aria-label="Guia de formatação"
        className="flex h-8 w-8 items-center justify-center rounded-full border border-bdr bg-card text-sm font-semibold text-t2 hover:bg-hov"
      >
        ?
      </button>

      <Modal open={aberto} onClose={() => setAberto(false)} title="Guia de formatação">
        <ol className="space-y-2 text-sm text-t2">
          <li>1. Uma linha por item.</li>
          <li>2. Formato: quantidade + unidade + nome do produto.</li>
          <li>3. Sem unidade explícita, assume-se &quot;un&quot; e quantidade 1.</li>
        </ol>
        <pre className="mt-3 rounded-md bg-surf p-3 font-mono text-xs text-t1">
          15un sazon legumes 60g{"\n"}2cx leite integral 1l
        </pre>
        <div className="mt-3 flex flex-wrap gap-1.5">
          {UNIDADES.map((u) => (
            <span key={u} className="rounded-full bg-hov px-2 py-0.5 text-xs font-medium text-t2">
              {u}
            </span>
          ))}
        </div>
      </Modal>
    </>
  );
}
