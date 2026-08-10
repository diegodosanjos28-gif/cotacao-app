"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Fornecedor } from "@/lib/types";
import { normTxt } from "@/lib/normalizacao";

interface Props {
  fornecedores: Fornecedor[];
  excluirIds?: string[];
  onSelecionar: (fornecedor: Fornecedor) => void;
  onCadastrarNovo: (nomeDigitado: string) => void;
  disabled?: boolean;
}

// Autocomplete de fornecedor: busca por similaridade simples (case-insensitive, sem
// acento — mesma normalização usada no matching de produto, ver lib/normalizacao.ts)
// sobre a lista de fornecedores já cadastrados do tenant. Sempre oferece
// "+ Cadastrar novo fornecedor" ao final das sugestões.
export default function FornecedorAutocomplete({
  fornecedores,
  excluirIds = [],
  onSelecionar,
  onCadastrarNovo,
  disabled,
}: Props) {
  const [texto, setTexto] = useState("");
  const [aberto, setAberto] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const sugestoes = useMemo(() => {
    const termo = normTxt(texto);
    return fornecedores
      .filter((f) => !excluirIds.includes(f.id))
      .filter((f) => termo === "" || normTxt(f.nome).includes(termo))
      .slice(0, 8);
  }, [texto, fornecedores, excluirIds]);

  useEffect(() => {
    function onClickFora(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setAberto(false);
      }
    }
    document.addEventListener("mousedown", onClickFora);
    return () => document.removeEventListener("mousedown", onClickFora);
  }, []);

  return (
    <div ref={containerRef} className="relative">
      <input
        value={texto}
        onChange={(e) => {
          setTexto(e.target.value);
          setAberto(true);
        }}
        onFocus={() => setAberto(true)}
        disabled={disabled}
        placeholder="Buscar fornecedor..."
        className="w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx disabled:opacity-50"
      />
      {aberto && !disabled && (
        <ul className="absolute z-10 mt-1 max-h-56 w-full overflow-auto rounded-md border border-bdr bg-card shadow-lg">
          {sugestoes.map((f) => (
            <li key={f.id}>
              <button
                type="button"
                onClick={() => {
                  onSelecionar(f);
                  setTexto("");
                  setAberto(false);
                }}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-hov"
              >
                {f.nome}
              </button>
            </li>
          ))}
          {sugestoes.length === 0 && (
            <li className="px-3 py-2 text-sm text-t3">Nenhum fornecedor encontrado</li>
          )}
          <li className="border-t border-bdr">
            <button
              type="button"
              onClick={() => {
                onCadastrarNovo(texto);
                setAberto(false);
              }}
              className="block w-full px-3 py-2 text-left text-sm font-medium text-prx hover:bg-hov"
            >
              + Cadastrar novo fornecedor
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}
