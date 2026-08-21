import { PreviewRespostaResponse, ResolucaoItemRequest } from "@/lib/types";

// Rascunho da Conferência de um fornecedor dentro do AprovacaoModal — antes vivia em
// entrada/page.tsx (RascunhoFornecedor), migrado pra cá porque o modal "passa a ser
// dono" desse estado (Fase C/D do refactor, 2026-08-20). Compartilhado entre
// AprovacaoModal (dono do mapa por cfId) e ConferenciaFornecedoresTab (resolve o
// rascunho do fornecedor atualmente selecionado dentro da aba).
export interface RascunhoFornecedor {
  texto: string;
  preview: PreviewRespostaResponse | null;
  resolucoes: Record<string, ResolucaoItemRequest>;
  spinOffs: Record<string, ResolucaoItemRequest[]>;
  excluidos: Record<string, Set<string>>;
}

export function rascunhoVazio(): RascunhoFornecedor {
  return { texto: "", preview: null, resolucoes: {}, spinOffs: {}, excluidos: {} };
}
