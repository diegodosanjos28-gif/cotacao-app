const CORES: Record<string, string> = {
  OK: "bg-ok-d text-ok",
  RASCUNHO: "bg-zinc-100 text-zinc-700",
  EM_ANDAMENTO: "bg-inf-d text-inf",
  FINALIZADA: "bg-ok-d text-ok",
  CANCELADA: "bg-zinc-100 text-zinc-500",
  ATIVO: "bg-ok-d text-ok",
  PENDENTE_DADOS: "bg-wa-d text-wa",
  INATIVO: "bg-zinc-100 text-zinc-500",
  SUSPENSO: "bg-er-d text-er",
  TRIAL: "bg-inf-d text-inf",
  NAO_IDENTIFICADO: "bg-zinc-100 text-zinc-600",
  PENDENTE_CONFIRMACAO: "bg-wa-d text-wa",
  DIVERGENCIA_COMPARATIVA: "bg-wa-d text-wa",
  // Tendência de preço (Histórico de Preços) — cores fiéis ao protótipo validado
  // (classificarPrecoHistorico): ALTA usa "wa" (atenção/amber), não "er" — subir de
  // preço é um alerta a observar, não um erro. QUEDA usa "ok" (oportunidade de
  // compra). NOVO = preço atual sem nenhuma referência anterior pra comparar.
  ESTAVEL: "bg-zinc-100 text-zinc-600",
  ALTA: "bg-wa-d text-wa",
  QUEDA: "bg-ok-d text-ok",
  NOVO: "bg-zinc-100 text-zinc-600",
  // Cotação WhatsApp com lista_revisada=FALSE — indicador do dashboard (Fase 3).
  AJUSTE_PENDENTE: "bg-wa-d text-wa",
};

const LABELS: Record<string, string> = {
  OK: "OK",
  RASCUNHO: "Rascunho",
  EM_ANDAMENTO: "Em andamento",
  FINALIZADA: "Finalizada",
  CANCELADA: "Cancelada",
  ATIVO: "Ativo",
  PENDENTE_DADOS: "Dados pendentes",
  INATIVO: "Inativo",
  SUSPENSO: "Suspenso",
  TRIAL: "Trial",
  NAO_IDENTIFICADO: "Não identificado",
  PENDENTE_CONFIRMACAO: "Confirmação pendente",
  DIVERGENCIA_COMPARATIVA: "Possível preço de caixa/fardo",
  ESTAVEL: "Estável",
  ALTA: "Alta",
  QUEDA: "Queda",
  NOVO: "Novo",
  AJUSTE_PENDENTE: "Ajuste pendente",
};

export default function StatusBadge({ status }: { status: string }) {
  const classe = CORES[status] ?? "bg-zinc-100 text-zinc-700";
  const label = LABELS[status] ?? status.replaceAll("_", " ");
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${classe}`}>
      {label}
    </span>
  );
}
