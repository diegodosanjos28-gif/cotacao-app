"use client";

// Timeline de progresso da Entrada de Dados (Prompt 25) — substitui o texto solto
// "Fornecedor X de Y" por 4 marcadores clicáveis que alternam qual seção é exibida
// (ver page.tsx). Puramente apresentacional: quem chama já traz done/current/label
// calculados a partir do estado real da cotação — este componente não decide nada de
// negócio, só desenha o marcador.

export type PassoEntrada = 1 | 2 | 3;

export interface PassoInfo {
  numero: PassoEntrada;
  label: string;
  done: boolean;
  /** true quando o passo tem pendência que merece destaque (ex: Conferência aguardando) */
  atencao?: boolean;
}

interface Props {
  passos: PassoInfo[];
  passoAtivo: PassoEntrada;
  onSelecionar: (passo: PassoEntrada) => void;
}

export default function EntradaStepper({ passos, passoAtivo, onSelecionar }: Props) {
  return (
    <div className="flex items-center rounded-lg border border-bdr bg-card p-4">
      {passos.map((passo, idx) => {
        const atual = passo.numero === passoAtivo;
        return (
          <div key={passo.numero} className="flex flex-1 items-center">
            <button
              type="button"
              onClick={() => onSelecionar(passo.numero)}
              className="flex items-center gap-2.5 text-left"
            >
              <span
                className={`flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-full text-xs font-semibold ${
                  passo.done
                    ? "bg-ok text-white"
                    : atual
                      ? passo.atencao
                        ? "bg-wa text-white"
                        : "bg-prx text-white"
                      : passo.atencao
                        ? "bg-wa-d text-wa"
                        : "bg-surf text-t3"
                }`}
              >
                {passo.done ? "✓" : passo.numero}
              </span>
              <span
                className={`text-sm font-medium ${
                  passo.done || atual ? "text-t1" : "text-t3"
                }`}
              >
                {passo.label}
              </span>
            </button>
            {idx < passos.length - 1 && (
              <span className={`mx-3 h-0.5 flex-1 ${passo.done ? "bg-ok" : "bg-surf"}`} />
            )}
          </div>
        );
      })}
    </div>
  );
}
