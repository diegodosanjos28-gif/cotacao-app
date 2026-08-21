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
        <div className={title ? "mt-4" : undefined}>{children}</div>
        {footer && <div className="mt-6 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}
