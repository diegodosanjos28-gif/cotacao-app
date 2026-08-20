"use client";

import { useEffect, useRef, useState } from "react";
import { buscarProdutos } from "@/lib/api";
import { Page, Produto } from "@/lib/types";
import { normTxt } from "@/lib/normalizacao";
import { useDebouncedValue } from "@/hooks/useDebouncedValue";

interface Props {
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

const TAMANHO_PAGINA = 8;

const PAGINA_VAZIA: Page<Produto> = { content: [], totalElements: 0, totalPages: 0, number: 0, size: TAMANHO_PAGINA };

// Autocomplete de produto do grid unificado (Prompt 12) — busca no servidor, debounced
// e paginada (o catálogo do tenant cresce sem limite por natureza, ver auditoria
// técnica 17/08 seção 9), no lugar do filtro client-side sobre um array pré-carregado
// que existia antes. A resolução de nome de um produto JÁ atribuído a um item
// (produtoIdEncontrado) não passa por aqui — isso é responsabilidade de
// buscarProdutosPorIds em GridProdutosSection.tsx/entrada/page.tsx.
export default function ProdutoAutocomplete({ valorAtualNome, onSelecionar, disabled, onUsarNomeLivre }: Props) {
  const [texto, setTexto] = useState("");
  const [aberto, setAberto] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  const [pagina, setPagina] = useState<Page<Produto> | null>(null);
  const [carregando, setCarregando] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const termoDebounced = useDebouncedValue(texto.trim(), 300);

  // Termo mudou: volta pra primeira página — senão a busca nova podia ficar "presa"
  // numa página vazia do resultado anterior (mesmo padrão de GridProdutosSection).
  const termoAnteriorRef = useRef(termoDebounced);
  if (termoAnteriorRef.current !== termoDebounced) {
    termoAnteriorRef.current = termoDebounced;
    if (pageIndex !== 0) setPageIndex(0);
  }

  useEffect(() => {
    if (!aberto) return;
    let cancelado = false;
    setCarregando(true);
    buscarProdutos({ q: termoDebounced || undefined, page: pageIndex, size: TAMANHO_PAGINA })
      .then((resultado) => {
        if (!cancelado) setPagina(resultado);
      })
      .catch(() => {
        if (!cancelado) setPagina(PAGINA_VAZIA);
      })
      .finally(() => {
        if (!cancelado) setCarregando(false);
      });
    return () => {
      cancelado = true;
    };
  }, [aberto, termoDebounced, pageIndex]);

  const sugestoes = pagina?.content ?? [];
  const termoAparado = texto.trim();
  // Checa só contra a página atual de resultados, não o catálogo inteiro — trade-off
  // da conversão pra busca paginada: um match exato que caia numa página seguinte não
  // é detectado aqui, e a ação "+ usar como novo produto" pode aparecer mesmo já
  // existindo. Risco baixo (o termo buscado pra um match exato costuma ser específico
  // o bastante pra não ter várias páginas de concorrentes).
  const semMatchExato = termoAparado.length > 0 && !sugestoes.some((p) => normTxt(p.nome) === normTxt(termoAparado));
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
        <div className="absolute z-10 mt-1 w-56 rounded-md border border-bdr bg-card shadow-lg">
          <ul className="max-h-56 overflow-auto">
            {carregando && <li className="px-3 py-2 text-sm text-t3">Buscando...</li>}
            {!carregando &&
              sugestoes.map((p) => (
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
            {!carregando && sugestoes.length === 0 && !mostrarUsarNomeLivre && (
              <li className="px-3 py-2 text-sm text-t3">Nenhum produto encontrado</li>
            )}
            {!carregando && mostrarUsarNomeLivre && (
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
          {/* Paginador compacto (setas) em vez do componente Pagination de tela cheia —
              o dropdown tem só 224px de largura, estreito demais pro texto completo
              "Anterior"/"Próxima"/"Página X de Y" (achado do frontend-ux-designer). */}
          {!carregando && pagina && pagina.totalElements > TAMANHO_PAGINA && (
            <div className="flex items-center justify-between border-t border-bdr px-2 py-1 text-xs text-t3">
              <button
                type="button"
                aria-label="Página anterior"
                disabled={pageIndex === 0}
                onClick={() => setPageIndex((p) => p - 1)}
                className="px-1 disabled:opacity-40"
              >
                ‹
              </button>
              <span>
                {pageIndex + 1}/{Math.ceil(pagina.totalElements / TAMANHO_PAGINA)}
              </span>
              <button
                type="button"
                aria-label="Próxima página"
                disabled={pageIndex + 1 >= Math.ceil(pagina.totalElements / TAMANHO_PAGINA)}
                onClick={() => setPageIndex((p) => p + 1)}
                className="px-1 disabled:opacity-40"
              >
                ›
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
