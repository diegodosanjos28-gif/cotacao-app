import { RefObject, useEffect } from "react";

const SELETOR_FOCAVEL =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

// Focus trap + devolução de foco para overlays modais (Modal.tsx). Extraído como
// hook (não inline no Modal) porque qualquer overlay bespoke futuro pode precisar
// do mesmo comportamento sem duplicar a lógica de ciclo Tab/Shift+Tab.
export function useFocusTrap(containerRef: RefObject<HTMLElement | null>, ativo: boolean) {
  useEffect(() => {
    if (!ativo) return;
    const container = containerRef.current;
    if (!container) return;

    // Elemento que tinha foco antes de abrir — devolvido ao fechar (handoff do
    // Dashboard, seção 5.1/10.3: "prender o foco dentro dele, e devolvê-lo ao
    // fechar").
    const elementoAnterior = document.activeElement as HTMLElement | null;

    function focaveis(): HTMLElement[] {
      return Array.from(container!.querySelectorAll<HTMLElement>(SELETOR_FOCAVEL));
    }

    // Se algum elemento dentro do container já está focado (ex.: <input autoFocus>
    // de NovaCotacaoForm dentro do Modal de "Nova cotação"), respeita esse foco em
    // vez de roubá-lo — só assume o primeiro focável quando nada dentro do modal
    // já tem foco.
    if (!container.contains(document.activeElement)) {
      const primeiro = focaveis()[0];
      // Modal somente-leitura (ex.: Conferência de Nota) pode não ter nenhum
      // elemento focável além do botão fechar, que já é focável — mas na dúvida o
      // próprio container recebe foco via tabIndex temporário, pra nunca deixar o
      // foco "solto" fora do modal.
      if (primeiro) {
        primeiro.focus();
      } else {
        container.setAttribute("tabindex", "-1");
        container.focus();
      }
    }

    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== "Tab") return;
      const itens = focaveis();
      if (itens.length === 0) return;
      const inicio = itens[0];
      const fim = itens[itens.length - 1];
      if (e.shiftKey && document.activeElement === inicio) {
        e.preventDefault();
        fim.focus();
      } else if (!e.shiftKey && document.activeElement === fim) {
        e.preventDefault();
        inicio.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      elementoAnterior?.focus();
    };
  }, [ativo, containerRef]);
}
