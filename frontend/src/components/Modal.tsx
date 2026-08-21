"use client";

import { useEffect, useRef } from "react";
import { useFocusTrap } from "@/hooks/useFocusTrap";

export default function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  className,
  semBackdrop,
}: {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  // Sobrescreve a largura/raio/padding padrão (w-full max-w-lg rounded-lg border
  // border-bdr bg-card p-6 shadow-xl) — usado por modais com layout próprio (ex.:
  // Conferência de Nota do Dashboard, min(760px,100%)/max-height:88vh), que também
  // assumem controle total do próprio cabeçalho/corpo rolável via `children`.
  className?: string;
  // true quando este modal pode abrir por cima de outro já aberto (ex.: confirmação
  // de exclusão dentro do GridProdutosSection hospedado no AprovacaoModal) — omite o
  // próprio `bg-black/50` pra não somar dois overlays escurecidos, e sobe o z-index
  // acima do modal pai. Clique-fora continua fechando normalmente.
  semBackdrop?: boolean;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useFocusTrap(dialogRef, open);

  useEffect(() => {
    if (!open) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  // Trava o scroll da página por trás do modal — sem isto o fundo (ex.: a landing de
  // Entrada de Dados, que continua montada atrás do backdrop) mantém sua própria barra
  // de rolagem, dando a impressão de "2 scrolls" ao lado do scroll interno do corpo do
  // modal (achado do usuário, 2026-08-21).
  useEffect(() => {
    if (!open) return;
    const original = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = original;
    };
  }, [open]);

  if (!open) return null;

  return (
    <div
      className={`fixed inset-0 flex items-center justify-center p-4 ${semBackdrop ? "z-[60]" : "z-50 bg-black/50"}`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        className={className ?? "w-full max-w-lg rounded-lg border border-bdr bg-card p-6 shadow-xl"}
      >
        {title && <h2 className="text-lg font-semibold text-t1">{title}</h2>}
        {/* Sem título, o consumidor está montando um layout customizado próprio via
            `className` (ex.: AprovacaoModal, ConferenciaNotaModal — um flex-col com
            header/corpo-rolável/rodapé). `display:contents` faz este wrapper sumir da
            árvore de caixas, deixando os filhos virarem itens flex diretos da caixa do
            diálogo. Sem isso, o wrapper (um <div> comum, sem display:flex) é um item
            flex do diálogo com min-height:auto — recusa encolher abaixo da altura
            natural do próprio conteúdo, então em telas mais baixas o conteúdo real
            (cabeçalho+corpo+rodapé) ultrapassa o max-height do diálogo e vaza pra fora
            da caixa (overflow:visible), deixando o rodapé/botão de ação inacessível
            bem abaixo da viewport — achado do usuário testando num notebook. */}
        <div className={title ? "mt-4" : "contents"}>{children}</div>
        {footer && <div className="mt-6 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}
