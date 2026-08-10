"use client";

import { useState } from "react";
import { adicionarItemCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { Produto } from "@/lib/types";
import { UNIDADES } from "@/lib/unidades";
import ProdutoAutocomplete from "./ProdutoAutocomplete";

interface Props {
  cotacaoId: string;
  produtos: Produto[];
  onAdicionado: () => void;
  onCancelar: () => void;
}

// Linha editável temporária pro botão "+ Adicionar Produto" do grid unificado
// (Prompt 12) — ao contrário de LinhaGridProdutos (edita um item já persistido via
// PATCH), esta ainda não existe no backend: um único POST /produtos cria a linha já
// com produto resolvido (existente ou nome novo).
export default function NovaLinhaGridProdutos({ cotacaoId, produtos, onAdicionado, onCancelar }: Props) {
  const [produtoId, setProdutoId] = useState<string | null>(null);
  const [nomeProdutoLivre, setNomeProdutoLivre] = useState<string | null>(null);
  const [nomeExibido, setNomeExibido] = useState<string | null>(null);
  const [quantidade, setQuantidade] = useState("1");
  const [unidade, setUnidade] = useState<string>(UNIDADES[0]);
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  const produtoNomePorId = new Map(produtos.map((p) => [p.id, p.nome]));

  async function salvar() {
    const qtdNum = Number(quantidade.replace(",", "."));
    if (!qtdNum || qtdNum <= 0) {
      setErro("Informe uma quantidade válida.");
      return;
    }
    if (!produtoId && !nomeProdutoLivre) {
      setErro("Selecione um produto ou digite um nome novo.");
      return;
    }
    setSalvando(true);
    setErro(null);
    try {
      await adicionarItemCotacao(cotacaoId, {
        produtoId: produtoId ?? undefined,
        nomeProdutoLivre: produtoId ? undefined : (nomeProdutoLivre ?? undefined),
        quantidade: qtdNum,
        unidade,
        // Sem campo separado pra "texto original" — quando o produto vem de um nome
        // digitado (nomeProdutoLivre), esse mesmo texto já serve de registro do que
        // foi pedido; ao selecionar um produto já existente do catálogo, não há
        // texto nenhum pra guardar (mesmo comportamento de sempre).
        textoOriginal: produtoId ? undefined : (nomeProdutoLivre ?? undefined),
      });
      onAdicionado();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível adicionar o item."));
    } finally {
      setSalvando(false);
    }
  }

  return (
    <tr className="align-top border-l-[3px] border-l-prx bg-prx/5">
      <td className="px-4 py-3 text-xs text-t3">
        {/* Sem input próprio — reflete o que a coluna Produto vai virar: o nome
            digitado (se for um produto novo) ou nada (se for um produto já
            existente do catálogo), mesmo valor que será salvo como texto_original. */}
        {nomeProdutoLivre || <span className="italic text-t3/70">Adicionado manualmente</span>}
      </td>
      <td className="whitespace-nowrap px-4 py-3">
        <input
          type="number"
          min="0.001"
          step="any"
          value={quantidade}
          disabled={salvando}
          onChange={(e) => setQuantidade(e.target.value)}
          className="w-16 rounded-md border border-bdr px-1.5 py-1 text-xs outline-none focus:border-prx disabled:opacity-50"
        />
      </td>
      <td className="whitespace-nowrap px-4 py-3">
        <select
          value={unidade}
          disabled={salvando}
          onChange={(e) => setUnidade(e.target.value)}
          className="rounded-md border border-bdr px-1.5 py-1 text-xs outline-none focus:border-prx disabled:opacity-50"
        >
          {UNIDADES.map((u) => (
            <option key={u} value={u}>
              {u}
            </option>
          ))}
        </select>
      </td>
      <td className="px-4 py-3">
        <ProdutoAutocomplete
          produtos={produtos}
          valorAtualNome={nomeExibido ?? (produtoId ? (produtoNomePorId.get(produtoId) ?? null) : null)}
          disabled={salvando}
          onSelecionar={(p) => {
            setProdutoId(p.id);
            setNomeProdutoLivre(null);
            setNomeExibido(p.nome);
          }}
          onUsarNomeLivre={(nome) => {
            setProdutoId(null);
            setNomeProdutoLivre(nome);
            setNomeExibido(nome);
          }}
        />
      </td>
      <td className="px-4 py-3 text-xs">{erro && <p className="text-er">{erro}</p>}</td>
      <td className="whitespace-nowrap px-4 py-3 text-right">
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={salvar}
            disabled={salvando}
            className="rounded-md bg-prx px-2 py-1 text-xs font-medium text-white hover:bg-prx-l disabled:opacity-50"
          >
            {salvando ? "Salvando..." : "Salvar"}
          </button>
          <button
            type="button"
            onClick={onCancelar}
            disabled={salvando}
            className="rounded-md border border-bdr px-2 py-1 text-xs font-medium hover:bg-hov disabled:opacity-50"
          >
            Cancelar
          </button>
        </div>
      </td>
    </tr>
  );
}
