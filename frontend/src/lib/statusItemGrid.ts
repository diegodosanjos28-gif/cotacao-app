import { ItemListaResponse } from "./types";
import { comecaComQuantidadeEUnidade } from "./validarLista";
import { UNIDADES } from "./unidades";

const UNIDADES_VALIDAS = new Set<string>(UNIDADES);

export interface StatusItemGrid {
  semProduto: boolean;
  mensagemErro: string | null;
  corLinha: string;
  // Usado pra ordenar a coluna Status no grid — 0 é o mais urgente. Erro e travado
  // podem coexistir (item já conferido cujo produto foi desassociado por algum outro
  // caminho, por exemplo); erro pesa mais na ordenação porque é mais urgente resolver
  // do que a trava em si.
  rank: number;
}

// 3 tipos de erro de linha (spec 3.4) + trava de edição pós-conferência (spec 3.3) —
// única fonte de verdade pro tingimento/mensagem de LinhaGridProdutos e pra ordenação
// por Status de GridProdutosSection, pra nunca ordenar por um critério e tingir por
// outro.
export function classificarStatusItemGrid(item: ItemListaResponse): StatusItemGrid {
  const semProduto = !item.produtoIdEncontrado;
  // O heurístico "qtd+unidade+nome" (comecaComQuantidadeEUnidade) só faz sentido pra
  // texto que tentou seguir esse formato — uma linha colada de verdade sempre começa
  // com um número. Um item manual pode ter textoOriginal preenchido com só o nome do
  // produto (o autocomplete usa o nome digitado como texto original quando cria um
  // produto novo — ver NovaLinhaGridProdutos), que nunca teria dígito nenhum; marcar
  // isso como "formato não reconhecido" seria um falso positivo constante. Só roda o
  // heurístico quando o texto tem pelo menos um dígito (indício de que era mesmo uma
  // tentativa de linha colada).
  const pareceLinhaColada = /\d/.test(item.textoOriginal);
  const formatoInvalido = pareceLinhaColada && !comecaComQuantidadeEUnidade(item.textoOriginal);
  const unidadeInvalida = !UNIDADES_VALIDAS.has(item.unidade);

  const corLinha = semProduto
    ? "border-l-[3px] border-l-er bg-er/5"
    : formatoInvalido || unidadeInvalida
      ? "border-l-[3px] border-l-wa bg-wa/6"
      : "";

  const mensagemErro = semProduto
    ? "Sem produto identificado — selecione um."
    : formatoInvalido
      ? "Formato de texto não reconhecido pelo parser — confira quantidade e unidade."
      : unidadeInvalida
        ? `Unidade "${item.unidade}" fora do padrão conhecido.`
        : null;

  const rank = semProduto
    ? 0
    : formatoInvalido || unidadeInvalida
      ? 1
      : item.temRespostaFornecedorConfirmada
        ? 2
        : 3;

  return { semProduto, mensagemErro, corLinha, rank };
}
