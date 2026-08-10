import { Dispatch, SetStateAction, DependencyList, useEffect, useState } from "react";
import { getErrorMessage } from "@/lib/errors";

interface UseAsyncResult<T> {
  data: T | null;
  setData: Dispatch<SetStateAction<T | null>>;
  erro: string | null;
  setErro: Dispatch<SetStateAction<string | null>>;
  carregando: boolean;
}

interface UseAsyncOptions {
  // Zera `data` no início de cada busca (não só na primeira) — para telas que
  // trocam de parâmetro (ex: cenário do Mapa de Compra) e não querem mostrar o
  // resultado antigo enquanto o novo carrega.
  limparAoRecarregar?: boolean;
  // Depois desse tempo sem o fetcher assentar (nem resolver nem rejeitar), mostra um
  // erro visível em vez de deixar "Carregando..." preso pra sempre — ex: refresh de
  // token travado, requisição pendurada. Se o fetcher assentar depois do timeout, o
  // resultado real (sucesso ou erro) ainda substitui essa mensagem normalmente.
  timeoutMs?: number;
}

const TIMEOUT_PADRAO_MS = 20_000;

// Padrão repetido em várias páginas: buscar no mount/troca de parâmetro, guardar
// loading/erro, e ignorar resposta tardia se o componente desmontar ou as deps
// mudarem de novo antes da promise resolver.
export function useAsync<T>(
  fetcher: () => Promise<T>,
  deps: DependencyList,
  erroPadrao: string,
  options: UseAsyncOptions = {},
): UseAsyncResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(true);

  useEffect(() => {
    let ignorar = false;
    let assentou = false;
    // Reset síncrono de loading/erro (e opcionalmente data) no início de cada
    // busca — inerente ao padrão "recarregar quando as deps mudam", não um
    // efeito derivável de outro jeito.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCarregando(true);
    setErro(null);
    if (options.limparAoRecarregar) setData(null);

    const timeoutId = setTimeout(() => {
      if (!ignorar && !assentou) {
        setErro("Tempo esgotado ao carregar. Tente novamente.");
        setCarregando(false);
      }
    }, options.timeoutMs ?? TIMEOUT_PADRAO_MS);

    fetcher()
      .then((resultado) => {
        assentou = true;
        if (!ignorar) {
          setData(resultado);
          setErro(null);
        }
      })
      .catch((err) => {
        assentou = true;
        if (!ignorar) setErro(getErrorMessage(err, erroPadrao));
      })
      .finally(() => {
        clearTimeout(timeoutId);
        if (!ignorar) setCarregando(false);
      });

    return () => {
      ignorar = true;
      clearTimeout(timeoutId);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, setData, erro, setErro, carregando };
}
