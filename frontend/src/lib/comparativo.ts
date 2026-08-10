// Agregações derivadas client-side sobre ComparativoItemResponse[]. O backend não expõe
// nenhum campo agregado (melhor preço, spread, cobertura) — ver TODO-PROTOTYPE.md item 1
// (histórico) e o diff de UX/UI: toda essa lógica é portável sem mudança de backend porque
// é pura leitura de precosPorFornecedor. Espelha bestOf/minOf/maxOf/diffPct/econ/statusBadge/
// coverage/bestCount do protótipo COTA_TESTE_29_06_-_V5.html.

import { ComparativoItemResponse, PrecoFornecedor } from "./types";

export interface Oferta {
  fornecedorId: string;
  nomeFornecedor: string;
  preco: number;
}

export type StatusCobertura = "sem_cotacao" | "cobertura_parcial" | "alta_variacao" | "melhor_compra";

const LIMIAR_ALTA_VARIACAO_PCT = 20;
const LIMIAR_COBERTURA_PARCIAL = 3;

function ofertaValida(p: PrecoFornecedor): boolean {
  return !p.semEstoque && p.status === "OK";
}

export function ofertasValidas(item: ComparativoItemResponse): Oferta[] {
  return item.precosPorFornecedor
    .filter(ofertaValida)
    .map((p) => ({ fornecedorId: p.fornecedorId, nomeFornecedor: p.nomeFornecedor, preco: p.precoUnitarioCalculado }));
}

export function menorOferta(item: ComparativoItemResponse): Oferta | null {
  const ofertas = ofertasValidas(item);
  if (ofertas.length === 0) return null;
  return ofertas.reduce((menor, o) => (o.preco < menor.preco ? o : menor));
}

export function maiorOferta(item: ComparativoItemResponse): Oferta | null {
  const ofertas = ofertasValidas(item);
  if (ofertas.length === 0) return null;
  return ofertas.reduce((maior, o) => (o.preco > maior.preco ? o : maior));
}

export function diferencaPercentual(item: ComparativoItemResponse): number | null {
  const menor = menorOferta(item);
  const maior = maiorOferta(item);
  if (!menor || !maior || menor.preco === 0) return null;
  return ((maior.preco - menor.preco) / menor.preco) * 100;
}

export function economiaPotencial(item: ComparativoItemResponse): number {
  const menor = menorOferta(item);
  const maior = maiorOferta(item);
  if (!menor || !maior) return 0;
  return (maior.preco - menor.preco) * item.quantidade;
}

export function statusCobertura(item: ComparativoItemResponse, totalFornecedores: number): StatusCobertura {
  const ofertas = ofertasValidas(item);
  if (ofertas.length === 0) return "sem_cotacao";
  if (totalFornecedores > 0 && ofertas.length < Math.min(LIMIAR_COBERTURA_PARCIAL, totalFornecedores)) {
    return "cobertura_parcial";
  }
  const diff = diferencaPercentual(item);
  if (diff !== null && diff > LIMIAR_ALTA_VARIACAO_PCT) return "alta_variacao";
  return "melhor_compra";
}

export function totalEconomia(itens: ComparativoItemResponse[]): number {
  return itens.reduce((soma, item) => soma + economiaPotencial(item), 0);
}

export function totalRecomendado(itens: ComparativoItemResponse[]): number {
  return itens.reduce((soma, item) => {
    const menor = menorOferta(item);
    return soma + (menor ? menor.preco * item.quantidade : 0);
  }, 0);
}

/** Economia como % do total que seria pago sem escolher o menor preço em cada item (base = recomendado + economia). */
export function percentualEconomia(itens: ComparativoItemResponse[]): number {
  const economia = totalEconomia(itens);
  const base = totalRecomendado(itens) + economia;
  return base > 0 ? (economia / base) * 100 : 0;
}

export function fornecedoresDoItem(item: ComparativoItemResponse): Map<string, string> {
  const mapa = new Map<string, string>();
  item.precosPorFornecedor.forEach((p) => mapa.set(p.fornecedorId, p.nomeFornecedor));
  return mapa;
}

export function todosFornecedores(itens: ComparativoItemResponse[]): Map<string, string> {
  const mapa = new Map<string, string>();
  itens.forEach((item) => item.precosPorFornecedor.forEach((p) => mapa.set(p.fornecedorId, p.nomeFornecedor)));
  return mapa;
}

/** Quantos itens um fornecedor venceu (menor preço válido) entre os cotados por ele. */
export function melhorContagem(itens: ComparativoItemResponse[], fornecedorId: string): number {
  return itens.filter((item) => menorOferta(item)?.fornecedorId === fornecedorId).length;
}

/** Fornecedor com mais itens vencidos (menor preço) na cotação — null se nenhum item tem oferta válida. */
export function fornecedorMaisCompetitivo(itens: ComparativoItemResponse[]): { nome: string; contagem: number } | null {
  return Array.from(todosFornecedores(itens).entries()).reduce<{ nome: string; contagem: number } | null>(
    (melhor, [id, nome]) => {
      const contagem = melhorContagem(itens, id);
      return contagem > 0 && (!melhor || contagem > melhor.contagem) ? { nome, contagem } : melhor;
    },
    null,
  );
}

/** Cobertura de um fornecedor: quantos itens ele cotou (com oferta válida) e o percentual sobre o total. */
export function cobertura(itens: ComparativoItemResponse[], fornecedorId: string): { quantidade: number; percentual: number } {
  const quantidade = itens.filter((item) =>
    item.precosPorFornecedor.some((p) => p.fornecedorId === fornecedorId && ofertaValida(p)),
  ).length;
  const percentual = itens.length > 0 ? (quantidade / itens.length) * 100 : 0;
  return { quantidade, percentual };
}

/** Total hipotético se todos os itens cotados por esse fornecedor fossem comprados dele. */
export function totalSeTudoDeUmFornecedor(itens: ComparativoItemResponse[], fornecedorId: string): number {
  return itens.reduce((soma, item) => {
    const oferta = item.precosPorFornecedor.find((p) => p.fornecedorId === fornecedorId && ofertaValida(p));
    return soma + (oferta ? oferta.precoUnitarioCalculado * item.quantidade : 0);
  }, 0);
}

export function itensSemCotacao(itens: ComparativoItemResponse[]): ComparativoItemResponse[] {
  return itens.filter((item) => ofertasValidas(item).length === 0);
}

export interface DetalheFornecedorItem {
  cotacaoProdutoId: string;
  nomeProduto: string;
  quantidade: number;
  unidade: string;
  precoUnitario: number;
  total: number;
}

export interface DetalheFornecedor {
  fornecedorId: string;
  nomeFornecedor: string;
  itens: DetalheFornecedorItem[];
  totalFornecedor: number;
}

/** Reshape item-cêntrico → fornecedor-cêntrico (Conferência de Nota): um bloco por fornecedor
 * com oferta válida em ao menos um item, itens na ordem da cotação, sem arredondamento
 * explícito (mesmo estilo de totalEconomia/totalRecomendado — soma floats, formata na exibição). */
export function detalhamentoPorFornecedor(itens: ComparativoItemResponse[]): DetalheFornecedor[] {
  const ordem = todosFornecedores(itens);
  const porFornecedor = new Map<string, DetalheFornecedor>();
  itens.forEach((item) => {
    ofertasValidas(item).forEach((oferta) => {
      if (!porFornecedor.has(oferta.fornecedorId)) {
        porFornecedor.set(oferta.fornecedorId, {
          fornecedorId: oferta.fornecedorId,
          nomeFornecedor: oferta.nomeFornecedor,
          itens: [],
          totalFornecedor: 0,
        });
      }
      const detalhe = porFornecedor.get(oferta.fornecedorId)!;
      const total = oferta.preco * item.quantidade;
      detalhe.itens.push({
        cotacaoProdutoId: item.cotacaoProdutoId,
        nomeProduto: item.nomeProduto,
        quantidade: item.quantidade,
        unidade: item.unidade,
        precoUnitario: oferta.preco,
        total,
      });
      detalhe.totalFornecedor += total;
    });
  });
  return Array.from(ordem.keys())
    .map((id) => porFornecedor.get(id))
    .filter((d): d is DetalheFornecedor => !!d && d.itens.length > 0);
}

export function itensAltaVariacao(itens: ComparativoItemResponse[], totalFornecedores: number): ComparativoItemResponse[] {
  return itens.filter((item) => statusCobertura(item, totalFornecedores) === "alta_variacao");
}

export function itensCoberturaParcial(itens: ComparativoItemResponse[], totalFornecedores: number): ComparativoItemResponse[] {
  return itens.filter((item) => statusCobertura(item, totalFornecedores) === "cobertura_parcial");
}

export function maiorEconomiaItem(itens: ComparativoItemResponse[]): { item: ComparativoItemResponse; valor: number } | null {
  return itens.reduce<{ item: ComparativoItemResponse; valor: number } | null>((maior, item) => {
    const valor = economiaPotencial(item);
    return valor > 0 && (!maior || valor > maior.valor) ? { item, valor } : maior;
  }, null);
}

/** Percentual de itens vencidos (menor preço) pelo fornecedor líder — alerta de concentração se muito alto. */
export function concentracaoFornecedorLider(itens: ComparativoItemResponse[]): { fornecedorId: string; nome: string; percentual: number } | null {
  const fornecedores = todosFornecedores(itens);
  const comOferta = itens.filter((item) => ofertasValidas(item).length > 0);
  if (comOferta.length === 0) return null;

  const lider = Array.from(fornecedores.entries()).reduce<{ fornecedorId: string; nome: string; contagem: number } | null>(
    (melhor, [id, nome]) => {
      const contagem = melhorContagem(itens, id);
      return !melhor || contagem > melhor.contagem ? { fornecedorId: id, nome, contagem } : melhor;
    },
    null,
  );
  if (!lider) return null;
  return { fornecedorId: lider.fornecedorId, nome: lider.nome, percentual: (lider.contagem / comOferta.length) * 100 };
}
