"use client";

import { useEffect, useRef, useState } from "react";
import { cotacoesAnteriores } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { CotacaoAnteriorCursorResponse } from "@/lib/types";
import { useCotacoesOcultas } from "@/hooks/useCotacoesOcultas";
import MiniCardCotacao from "./MiniCardCotacao";

const TAMANHO_PAGINA = 10;
// Mesmo limiar de EconomiaCarrossel — distância (px) do fim da track a partir da
// qual o scroll nativo já dispara a busca da próxima página.
const LIMIAR_SCROLL_PX = 250;

// Mesma mecânica de paginação por cursor de EconomiaCarrossel (Dashboard), sem o
// filtro de período (o carrossel da landing não tem esse filtro no mockup) — só
// cotações FINALIZADA (garantido pelo backend, ver CotacaoAnterioresService).
export default function CotacoesAnterioresCarrossel() {
  const [itens, setItens] = useState<CotacaoAnteriorCursorResponse[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [carregandoMais, setCarregandoMais] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const buscandoRef = useRef(false);
  const { ocultar, estaOculto } = useCotacoesOcultas();

  async function carregarMais() {
    if (buscandoRef.current || (itens !== null && !hasMore)) return;
    buscandoRef.current = true;
    setCarregandoMais(itens !== null);
    setErro(null);
    try {
      const pagina = await cotacoesAnteriores(cursor, TAMANHO_PAGINA);
      setItens((atual) => [...(atual ?? []), ...pagina.items]);
      setCursor(pagina.nextCursor);
      setHasMore(pagina.hasMore);
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível carregar as cotações anteriores."));
    } finally {
      buscandoRef.current = false;
      setCarregandoMais(false);
    }
  }

  useEffect(() => {
    // setTimeout(0) em vez de chamada direta — carregarMais() faz setState antes do
    // primeiro await, o que dispararia o lint react-hooks/set-state-in-effect se
    // chamado sincronamente aqui (mesmo padrão de EconomiaCarrossel).
    const id = setTimeout(carregarMais, 0);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function rolar(direcao: 1 | -1) {
    trackRef.current?.scrollBy({ left: direcao * 200, behavior: "smooth" });
  }

  function onScrollTrack() {
    const el = trackRef.current;
    if (!el || !hasMore || buscandoRef.current) return;
    if (el.scrollLeft + el.clientWidth >= el.scrollWidth - LIMIAR_SCROLL_PX) {
      carregarMais();
    }
  }

  const carregandoPrimeira = itens === null;
  const visiveis = (itens ?? []).filter((c) => !estaOculto(c.id));

  return (
    <div className="flex flex-col justify-end">
      <div className="mb-3.5 flex items-center justify-between">
        <span className="text-sm font-bold uppercase tracking-wide text-t2">Cotações anteriores</span>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => rolar(-1)}
            aria-label="Ver cotações anteriores no carrossel"
            className="flex h-[38px] w-[38px] items-center justify-center rounded-lg border border-bdr text-t2 hover:border-prx hover:text-prx"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </button>
          <button
            type="button"
            onClick={() => rolar(1)}
            aria-label="Ver próximas cotações no carrossel"
            className="flex h-[38px] w-[38px] items-center justify-center rounded-lg border border-bdr text-t2 hover:border-prx hover:text-prx"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 18l6-6-6-6" />
            </svg>
          </button>
        </div>
      </div>

      {erro && <p className="mb-2.5 text-sm text-er">{erro}</p>}

      {carregandoPrimeira && <p className="py-8 text-center text-base text-t2">Carregando...</p>}

      {!carregandoPrimeira && visiveis.length === 0 && (
        <p className="py-8 text-center text-base text-t2">Nenhuma cotação anterior por aqui ainda.</p>
      )}

      {!carregandoPrimeira && visiveis.length > 0 && (
        <div
          ref={trackRef}
          onScroll={onScrollTrack}
          className="flex items-end gap-7 overflow-x-auto px-3 pb-6 pt-1"
          style={{ scrollSnapType: "x mandatory" }}
        >
          {visiveis.map((c, i) => (
            <MiniCardCotacao key={c.id} cotacao={c} index={i} onFechar={ocultar} />
          ))}
          {carregandoMais && (
            <div className="flex h-[176px] w-40 flex-none items-center justify-center text-sm text-t3">
              Carregando...
            </div>
          )}
        </div>
      )}
    </div>
  );
}
