// Mesmo ícone de WhatsApp já portado em app/cotacoes/[id]/mapa/page.tsx (duplicado
// aqui, não importado de lá — aquele arquivo tem mudanças não commitadas de outra
// tarefa em andamento).
export function WhatsAppIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden className="shrink-0">
      <path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347z" />
      <path d="M12.001 2C6.478 2 2 6.477 2 12c0 1.9.542 3.746 1.564 5.35L2 22l4.797-1.548A9.955 9.955 0 0012 22c5.524 0 10-4.478 10-10S17.524 2 12.001 2zm0 18.166a8.15 8.15 0 01-4.155-1.135l-.298-.177-3.084.995 1.017-3.013-.194-.311A8.164 8.164 0 013.834 12c0-4.5 3.665-8.166 8.167-8.166 4.502 0 8.166 3.666 8.166 8.166 0 4.5-3.664 8.166-8.166 8.166z" />
    </svg>
  );
}

export function WebIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className="shrink-0"
    >
      <circle cx="12" cy="12" r="10" />
      <path d="M2 12h20M12 2a15.3 15.3 0 010 20M12 2a15.3 15.3 0 000 20" />
    </svg>
  );
}

export function CanalIcon({ canal }: { canal: "WEB" | "WHATSAPP" }) {
  return canal === "WHATSAPP" ? <WhatsAppIcon /> : <WebIcon />;
}
