// Paleta cíclica de identidade visual por fornecedor (barras/pontos em listas e
// gráficos) — mesmo espírito do antigo CORES_BARRA de FornecedoresSidebar.tsx, sem
// persistir cor por fornecedor no backend. Sequência do handoff do Dashboard
// (2026-08-20), compartilhada entre a Entrada de Dados e o Dashboard para manter a
// mesma cor por posição nas duas telas.
export const CORES_FORNECEDOR = ["#FE7641", "#47C7FC", "#8B5CF6", "#10B981", "#F59E0B"];

export function corFornecedor(index: number): string {
  return CORES_FORNECEDOR[index % CORES_FORNECEDOR.length];
}
