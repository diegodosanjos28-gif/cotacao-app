"use client";

import Modal from "@/components/Modal";

interface Props {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  confirmando?: boolean;
  // true (default): ação destrutiva (inativar, remover, excluir) — botão vermelho.
  destrutivo?: boolean;
}

// Substitui window.confirm em todo o app (achado do usuário, 2026-08-21: alerta
// nativo do navegador não combina com o resto da UI) — mesmo padrão já usado pra
// exclusão de item em GridProdutosSection, generalizado aqui pra não duplicar o
// Modal+footer em cada tela que precisa de uma confirmação.
export default function ConfirmModal({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel = "Confirmar",
  cancelLabel = "Cancelar",
  confirmando = false,
  destrutivo = true,
}: Props) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={confirmando}
            className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov disabled:opacity-50"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={confirmando}
            className={`rounded-md px-4 py-2 text-sm font-medium text-white disabled:opacity-50 ${
              destrutivo ? "bg-er hover:bg-er-txt" : "bg-prx hover:bg-prx-l"
            }`}
          >
            {confirmando ? "Aguarde..." : confirmLabel}
          </button>
        </>
      }
    >
      <p className="text-sm text-t2">{message}</p>
    </Modal>
  );
}
