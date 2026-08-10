"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Produto } from "@/lib/types";
import { normTxt } from "@/lib/normalizacao";

interface Props {
  produtos: Produto[];
  valorAtualNome: string | null;
  onSelecionar: (produto: Produto) => void;
  disabled?: boolean;
  // Só o fluxo "+ Adicionar Produto" (grid novo, POST /produtos) passa isto — permite
  // digitar um nome nunca visto no catálogo, que o backend resolve-ou-cria (mesmo
  // pipeline do parser de lista/WhatsApp). Editar o produto de uma linha JÁ
  // persistida (PATCH /produtos/{id}) não tem essa opção: EditarItemCotacaoRequest só
  // aceita produtoId, não um nome livre.
  onUsarNomeLivre?: (nome: string) => void;
}

// Autocomplete de produto do grid unificado (Prompt 12 — antes exclusivo da tela
// "Ajuste de Lista" do WhatsApp, generalizado pra também servir o fluxo Web). Mesmo
// padrão de FornecedorAutocomplete (busca por similaridade client-side via normTxt).
export default function ProdutoAutocomplete({ produtos, valorAtualNome, onSelecionar, disabled, onUsarNomeLivre }: Props) {
  const [texto, setTexto] = useState("");
  const [aberto, setAberto] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const sugestoes = useMemo(() => {
    const termo = normTxt(texto);
    return produtos.filter((p) => termo === "" || normTxt(p.nome).includes(termo)).slice(0, 8);
  }, [texto, produtos]);

  const termoAparado = texto.trim();
  const semMatchExato = termoAparado.length > 0 && !produtos.some((p) => normTxt(p.nome) === normTxt(termoAparado));
  const mostrarUsarNomeLivre = onUsarNomeLivre && semMatchExato;

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
        value={aberto ? texto : (texto || valorAtualNome || "")}
        onChange={(e) => {
          setTexto(e.target.value);
          setAberto(true);
        }}
        onFocus={() => {
          setTexto("");
          setAberto(true);
        }}
        disabled={disabled}
        placeholder="Buscar produto..."
        className="w-full rounded-md border border-bdr px-2 py-1.5 text-xs outline-none focus:border-prx disabled:opacity-50"
      />
      {aberto && !disabled && (
        <ul className="absolute z-10 mt-1 max-h-56 w-56 overflow-auto rounded-md border border-bdr bg-card shadow-lg">
          {sugestoes.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                onClick={() => {
                  onSelecionar(p);
                  setTexto("");
                  setAberto(false);
                }}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-hov"
              >
                {p.nome}
              </button>
            </li>
          ))}
          {sugestoes.length === 0 && !mostrarUsarNomeLivre && (
            <li className="px-3 py-2 text-sm text-t3">Nenhum produto encontrado</li>
          )}
          {mostrarUsarNomeLivre && (
            <li>
              <button
                type="button"
                onClick={() => {
                  onUsarNomeLivre!(termoAparado);
                  setTexto("");
                  setAberto(false);
                }}
                className="block w-full border-t border-bdr px-3 py-2 text-left text-sm font-medium text-prx hover:bg-hov"
              >
                + usar &quot;{termoAparado}&quot; como novo produto
              </button>
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
