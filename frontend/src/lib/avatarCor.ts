// Avatares por iniciais do Hall dos Fornecedores / card "Cotação atual" — cor
// determinística por nome (mesmo fornecedor sempre cai na mesma cor, sem precisar
// persistir nada), sobre a paleta qualitativa já existente do design system.
const PALETA = ["var(--color-s1)", "var(--color-s2)", "var(--color-s3)", "var(--color-s4)", "var(--color-s5)"];

function hash(texto: string): number {
  let h = 0;
  for (let i = 0; i < texto.length; i++) h = (h * 31 + texto.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export function avatarCor(nome: string): string {
  return PALETA[hash(nome) % PALETA.length];
}

export function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return "?";
  if (partes.length === 1) return partes[0].slice(0, 2).toUpperCase();
  return (partes[0][0] + partes[partes.length - 1][0]).toUpperCase();
}
