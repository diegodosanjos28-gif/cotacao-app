"use client";

import { useState } from "react";
import { importarTextoCotacao } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ImportarTextoItemResponse } from "@/lib/types";
import { UNIDADES } from "@/lib/unidades";
import { linhasNaoVazias, validarLinhasLista } from "@/lib/validarLista";

interface Props {
  open: boolean;
  cotacaoId: string;
  onClose: () => void;
  onImportado: () => void;
}

const PLACEHOLDER = "15un sazon legumes 60g\n2cx bombom nestle";

// Modal "Colar do WhatsApp" (Prompt 12) — substitui o textarea permanente que existia
// na tela de Entrada de Dados. Importação em massa é sempre ADIÇÃO PURA: nunca
// substitui/mescla itens já no grid (ver CotacaoListaService.importarTexto). Mesma
// estrutura de diálogo bespoke do ConferenciaModal.tsx (header fixo / corpo com
// scroll / footer fixo) — o Modal genérico (components/Modal.tsx) é estreito demais
// pra um textarea confortável.
export default function ColarWhatsappModal({ open, cotacaoId, onClose, onImportado }: Props) {
  const [texto, setTexto] = useState("");
  const [importando, setImportando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [resultado, setResultado] = useState<ImportarTextoItemResponse[] | null>(null);

  const avisos = validarLinhasLista(texto);
  const totalLinhas = linhasNaoVazias(texto).length;

  function fechar() {
    setTexto("");
    setResultado(null);
    setErro(null);
    onClose();
  }

  async function importar() {
    if (!texto.trim()) return;
    setImportando(true);
    setErro(null);
    try {
      const itens = await importarTextoCotacao(cotacaoId, texto);
      setResultado(itens);
      setTexto("");
      onImportado();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível importar o texto."));
    } finally {
      setImportando(false);
    }
  }

  if (!open) return null;

  const importados = resultado?.filter((i) => !i.duplicado).length ?? 0;
  const duplicados = resultado?.filter((i) => i.duplicado).length ?? 0;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={fechar}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Colar do WhatsApp"
        onClick={(e) => e.stopPropagation()}
        className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-lg border border-bdr bg-card shadow-xl"
      >
        <div className="border-b border-bdr px-6 py-4">
          <h2 className="text-lg font-semibold text-t1">Colar do WhatsApp</h2>
          <p className="mt-0.5 text-sm text-t2">
            Cole o texto recebido — cada linha vira um item novo no grid. Itens já existentes na lista
            nunca são substituídos; se uma linha bater com um produto já adicionado, ela é ignorada e
            reportada abaixo.
          </p>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-4">
          {erro && (
            <p role="alert" className="mb-3 rounded-md border border-er/35 bg-er-d p-3 text-sm font-medium text-er">
              {erro}
            </p>
          )}

          {/* Guia de formatação (Prompt 27) — vivia como botão "?" solto ao lado deste
              modal (Prompt 25); movido pra dentro por disputar espaço na barra de ações
              da Lista de produtos. Aqui é referência sempre visível, não mais opcional. */}
          <div className="mb-3 rounded-md border border-bdr bg-surf p-3">
            <ol className="space-y-1 text-sm text-t2">
              <li>1. Uma linha por item.</li>
              <li>2. Formato: quantidade + unidade + nome do produto.</li>
              <li>3. Sem unidade explícita, assume-se &quot;un&quot; e quantidade 1.</li>
            </ol>
            <pre className="mt-2 rounded-md bg-card p-2.5 font-mono text-xs text-t1">
              15un sazon legumes 60g{"\n"}2cx leite integral 1l
            </pre>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {UNIDADES.map((u) => (
                <span key={u} className="rounded-full bg-hov px-2 py-0.5 text-xs font-medium text-t2">
                  {u}
                </span>
              ))}
            </div>
          </div>

          <textarea
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder={PLACEHOLDER}
            rows={10}
            className="w-full resize-none rounded-md border border-bdr px-3 py-2 font-mono text-sm leading-6 outline-none focus:border-prx"
          />

          {texto.trim().length > 0 && avisos.length > 0 && (
            <div className="mt-3 max-h-32 overflow-y-auto rounded-md border border-wa/30 bg-wa-d p-3 text-xs text-t2">
              <p className="font-medium text-wa">
                {avisos.length} de {totalLinhas} linha{totalLinhas !== 1 ? "s" : ""} com formato incorreto
              </p>
              <ul className="mt-1.5 space-y-1">
                {avisos.slice(0, 5).map((a) => (
                  <li key={a.numero}>
                    <span className="font-mono font-medium text-t3">L{a.numero}</span> {a.mensagem}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {resultado && (
            <div className="mt-3 rounded-md border border-bdr bg-surf p-3 text-sm">
              <p className="font-medium text-t1">
                {importados} item{importados !== 1 ? "ns" : ""} importado{importados !== 1 ? "s" : ""}
                {duplicados > 0 && `, ${duplicados} ignorado${duplicados !== 1 ? "s" : ""} por já existir na lista`}
              </p>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-bdr px-6 py-4">
          <button
            type="button"
            onClick={fechar}
            className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
          >
            Fechar
          </button>
          <button
            type="button"
            onClick={importar}
            disabled={importando || !texto.trim()}
            className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
          >
            {importando ? "Importando..." : "Importar"}
          </button>
        </div>
      </div>
    </div>
  );
}
