"use client";

import { useEffect, useRef, useState } from "react";
import { economiaCarrossel } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { CotacaoFinalizadaCursorResponse } from "@/lib/types";
import { mesAtual, primeiroDiaMes, ultimoDiaMes } from "./competencia";
import ConferenciaNotaModal from "./ConferenciaNotaModal";
import EconomiaCartaoCotacao from "./EconomiaCartaoCotacao";

const TAMANHO_PAGINA = 10;
// Distância (px) do fim da track a partir da qual um scroll nativo já dispara a
// busca da próxima página — antecipa o carregamento pra não deixar o usuário ver o
// fim vazio da lista antes dela crescer.
const LIMIAR_SCROLL_PX = 250;

// Carrossel "Cotações Registradas" — substitui a antiga tabela "Economia de
// Cotações" (Dashboard). O filtro de período (inicio/fim) vive aqui, fora do card
// que efetivamente busca/pagina — CarrosselCard é remontado via `key` a cada troca
// de período, o que reinicia toda a paginação (itens/cursor/hasMore) de graça, sem
// precisar de reset manual de estado (nem do lint react-hooks/set-state-in-effect
// que uma sequência de setState síncronos no efeito dispararia).
export default function EconomiaCarrossel() {
  // Padrão: mês vigente — mesmo critério default dos painéis de insight abaixo
  // (CompetenciaSelector/mesAtual), pra não abrir a tela com um carrossel vazio nem
  // com o histórico inteiro sem filtro nenhum.
  const [inicio, setInicio] = useState(() => primeiroDiaMes(mesAtual()));
  const [fim, setFim] = useState(() => ultimoDiaMes(mesAtual()));

  return (
    <CarrosselCard key={`${inicio}|${fim}`} inicio={inicio} fim={fim} onInicioChange={setInicio} onFimChange={setFim} />
  );
}

function CarrosselCard({
  inicio,
  fim,
  onInicioChange,
  onFimChange,
}: {
  inicio: string;
  fim: string;
  onInicioChange: (v: string) => void;
  onFimChange: (v: string) => void;
}) {
  const [itens, setItens] = useState<CotacaoFinalizadaCursorResponse[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  // Total de cotações que batem com o filtro atual — vem pronto do backend
  // (CotacaoFinalizadaCursorPage.totalElements), nunca o total só do que já foi
  // carregado no cliente (achado do usuário: o badge não pode refletir só a
  // primeira leva de itens).
  const [total, setTotal] = useState(0);
  const [carregandoMais, setCarregandoMais] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [conferenciaAberta, setConferenciaAberta] = useState<CotacaoFinalizadaCursorResponse | null>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  // Guard único compartilhado pelos dois gatilhos de "carregar mais" (botões ‹ › e
  // scroll nativo) — sem ele, um clique no botão que também dispara o scroll nativo
  // cruzando o limiar quase ao mesmo tempo dispararia duas buscas concorrentes,
  // arriscando anexar páginas fora de ordem.
  const buscandoRef = useRef(false);

  async function carregarMais() {
    if (buscandoRef.current || (itens !== null && !hasMore)) return;
    buscandoRef.current = true;
    setCarregandoMais(itens !== null);
    setErro(null);
    try {
      const pagina = await economiaCarrossel(cursor, TAMANHO_PAGINA, inicio || null, fim || null);
      setItens((atual) => [...(atual ?? []), ...pagina.items]);
      setTotal(pagina.totalElements);
      setCursor(pagina.nextCursor);
      setHasMore(pagina.hasMore);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar as cotações registradas."));
    } finally {
      buscandoRef.current = false;
      setCarregandoMais(false);
    }
  }

  useEffect(() => {
    // setTimeout(0), não chamada direta — carregarMais() faz setState (setErro/
    // setCarregandoMais) antes do primeiro await, e chamá-la sincronamente aqui
    // dispara o lint react-hooks/set-state-in-effect (cascading render). Adiar pra
    // depois do commit do efeito evita isso sem duplicar a lógica de fetch só pro
    // carregamento inicial.
    const id = setTimeout(carregarMais, 0);
    return () => clearTimeout(id);
    // Só na primeira montagem deste card (remontado via key a cada troca de
    // período) — carregarMais() gerencia seu próprio cursor via closure/estado.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function rolar(direcao: 1 | -1) {
    trackRef.current?.scrollBy({ left: direcao * 250, behavior: "smooth" });
  }

  function onScrollTrack() {
    const el = trackRef.current;
    if (!el || !hasMore || buscandoRef.current) return;
    if (el.scrollLeft + el.clientWidth >= el.scrollWidth - LIMIAR_SCROLL_PX) {
      carregarMais();
    }
  }

  const carregandoPrimeira = itens === null;
  const filtroAtivo = inicio !== "" || fim !== "";

  return (
    <div className="card econ-carossel relative overflow-hidden rounded-xl border border-bdr bg-card p-5 shadow-[0_8px_30px_rgba(37,34,32,.10),0_2px_8px_rgba(37,34,32,.05)]">
      <span className="absolute inset-x-0 top-0 h-[3px]" style={{ background: "linear-gradient(90deg, #FE7641, transparent 70%)" }} />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <span className="text-[15px] font-bold tracking-tight text-t1">Cotações Registradas</span>
          <span className="rounded-full border border-bdr bg-surf px-2.5 py-0.5 text-[10.5px] font-medium text-t2">
            {total} cotaç{total === 1 ? "ão" : "ões"}
          </span>
        </div>

        {/* Filtro de período — dois <input type="date"> nativos (sem dependência
            nova de date-picker), controlados pelo pai (EconomiaCarrossel) pra
            sobreviver ao remount deste card quando o período muda. */}
        <div className="flex flex-wrap items-center gap-1.5 text-xs text-t2">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="shrink-0 text-t3">
            <rect x="3" y="4" width="18" height="18" rx="2" />
            <path d="M16 2v4M8 2v4M3 10h18" />
          </svg>
          <input
            type="date"
            value={inicio}
            onChange={(e) => onInicioChange(e.target.value)}
            aria-label="Data inicial do período"
            max={fim || undefined}
            className="rounded-md border border-bdr bg-card px-1.5 py-1 text-xs text-t1 focus:border-prx focus:outline-none"
          />
          <span className="text-t3">até</span>
          <input
            type="date"
            value={fim}
            onChange={(e) => onFimChange(e.target.value)}
            aria-label="Data final do período"
            min={inicio || undefined}
            className="rounded-md border border-bdr bg-card px-1.5 py-1 text-xs text-t1 focus:border-prx focus:outline-none"
          />
          {filtroAtivo && (
            <button
              type="button"
              onClick={() => {
                onInicioChange("");
                onFimChange("");
              }}
              className="rounded-md px-1.5 py-1 text-t3 hover:bg-hov hover:text-t1"
            >
              Limpar
            </button>
          )}
        </div>
      </div>

      <div className="mb-3.5 flex items-center justify-between">
        <span className="text-xs text-t3">Deslize para ver o histórico →</span>
        <div className="flex gap-1.5">
          <button
            type="button"
            onClick={() => rolar(-1)}
            aria-label="Ver cotações anteriores no carrossel"
            className="flex h-8 w-8 items-center justify-center rounded-[9px] border border-bdr text-t2 hover:border-prx hover:bg-prx/10 hover:text-prx"
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </button>
          <button
            type="button"
            onClick={() => rolar(1)}
            aria-label="Ver próximas cotações no carrossel"
            className="flex h-8 w-8 items-center justify-center rounded-[9px] border border-bdr text-t2 hover:border-prx hover:bg-prx/10 hover:text-prx"
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 18l6-6-6-6" />
            </svg>
          </button>
        </div>
      </div>

      {erro && <p className="mb-3 text-sm text-er">{erro}</p>}

      {carregandoPrimeira && <p className="py-6 text-center text-sm text-t2">Carregando...</p>}

      {!carregandoPrimeira && itens.length === 0 && (
        <p className="py-10 text-center text-sm text-t2">
          {filtroAtivo
            ? "Nenhuma cotação finalizada no período selecionado."
            : "Nenhuma cotação finalizada ainda. Finalize uma ordem de compra no Mapa de Compra para começar a acompanhar sua economia."}
        </p>
      )}

      {!carregandoPrimeira && itens.length > 0 && (
        <div
          ref={trackRef}
          onScroll={onScrollTrack}
          className="flex gap-3.5 overflow-x-auto pb-3.5 pt-1"
          style={{ scrollSnapType: "x mandatory", scrollBehavior: "smooth" }}
        >
          {itens.map((c, i) => (
            <EconomiaCartaoCotacao key={c.id} cotacao={c} index={i} onAbrirConferencia={() => setConferenciaAberta(c)} />
          ))}
          {carregandoMais && (
            <div className="flex h-[242px] w-[120px] flex-none items-center justify-center text-xs text-t3">
              Carregando...
            </div>
          )}
        </div>
      )}

      <ConferenciaNotaModal cotacao={conferenciaAberta} onClose={() => setConferenciaAberta(null)} />
    </div>
  );
}
