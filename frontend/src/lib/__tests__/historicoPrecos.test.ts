import { describe, expect, it } from "vitest";
import {
  LIMIAR_ESTAVEL_PCT,
  precoMaisRecente,
  produtosAcimaDaUltimaReferencia,
  produtosComHistorico,
  produtosComOportunidade,
  temHistorico,
  tendencia,
  variacaoEntrePontos,
  variacaoRecente,
} from "@/lib/historicoPrecos";
import { HistoricoPrecoProduto, PontoReferenciaPreco } from "@/lib/types";

function makePonto(overrides: Partial<PontoReferenciaPreco> = {}): PontoReferenciaPreco {
  return {
    cotacaoId: "cot-1",
    finalizadaEm: "2026-08-01T10:00:00Z",
    preco: 10,
    fornecedorId: "forn-1",
    nomeFornecedor: "Distribuidora Alfa",
    ...overrides,
  };
}

function makeProduto(overrides: Partial<HistoricoPrecoProduto> = {}): HistoricoPrecoProduto {
  return {
    produtoId: "prod-1",
    nomeProduto: "Arroz 5kg",
    quantidadeReferencia: null,
    unidadeReferencia: null,
    pontos: [],
    ...overrides,
  };
}

describe("temHistorico", () => {
  it("false quando não há nenhum ponto", () => {
    expect(temHistorico(makeProduto({ pontos: [] }))).toBe(false);
  });

  it("false quando há só 1 ponto (sem referência anterior pra comparar)", () => {
    expect(temHistorico(makeProduto({ pontos: [makePonto()] }))).toBe(false);
  });

  it("true quando há 2 ou mais pontos", () => {
    expect(temHistorico(makeProduto({ pontos: [makePonto(), makePonto()] }))).toBe(true);
  });
});

describe("precoMaisRecente", () => {
  it("null quando não há pontos", () => {
    expect(precoMaisRecente(makeProduto({ pontos: [] }))).toBeNull();
  });

  it("retorna o preço do primeiro ponto (mais recente, ordenado pelo backend)", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 12.5 }), makePonto({ preco: 10 })] });
    expect(precoMaisRecente(produto)).toBe(12.5);
  });
});

describe("variacaoEntrePontos", () => {
  it("null quando não há ponto no índice pedido", () => {
    const produto = makeProduto({ pontos: [makePonto()] });
    expect(variacaoEntrePontos(produto, 1)).toBeNull();
  });

  it("null quando não há ponto anterior (índice+1) pra comparar", () => {
    const produto = makeProduto({ pontos: [makePonto()] });
    expect(variacaoEntrePontos(produto, 0)).toBeNull();
  });

  it("null quando o preço do ponto anterior é 0 (divisão por zero)", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 10 }), makePonto({ preco: 0 })] });
    expect(variacaoEntrePontos(produto, 0)).toBeNull();
  });

  it("calcula a variação percentual entre pontos[indice] e pontos[indice+1]", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 11 }), makePonto({ preco: 10 })] });
    expect(variacaoEntrePontos(produto, 0)).toBeCloseTo(10, 10);
  });

  it("variação negativa quando o preço caiu", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 9 }), makePonto({ preco: 10 })] });
    expect(variacaoEntrePontos(produto, 0)).toBeCloseTo(-10, 10);
  });
});

describe("variacaoRecente", () => {
  it("null quando o produto tem menos de 2 pontos", () => {
    expect(variacaoRecente(makeProduto({ pontos: [makePonto()] }))).toBeNull();
  });

  it("igual a variacaoEntrePontos(p, 0) quando há histórico suficiente", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 20 }), makePonto({ preco: 10 })] });
    expect(variacaoRecente(produto)).toBe(variacaoEntrePontos(produto, 0));
    expect(variacaoRecente(produto)).toBeCloseTo(100, 10);
  });
});

describe("tendencia", () => {
  it("null quando o produto nunca foi cotado (0 pontos)", () => {
    expect(tendencia(makeProduto({ pontos: [] }))).toBeNull();
  });

  it("NOVO quando há preço atual mas nenhuma referência anterior pra comparar (1 ponto)", () => {
    expect(tendencia(makeProduto({ pontos: [makePonto()] }))).toBe("NOVO");
  });

  it(`ESTAVEL quando a variação fica logo abaixo do limiar (${LIMIAR_ESTAVEL_PCT}%)`, () => {
    // atual=101.99, anterior=100 -> variação = 1.99%, abaixo de 2.
    const produto = makeProduto({ pontos: [makePonto({ preco: 101.99 }), makePonto({ preco: 100 })] });
    expect(tendencia(produto)).toBe("ESTAVEL");
  });

  it(`ALTA quando a variação é exatamente o limiar (${LIMIAR_ESTAVEL_PCT}%) — o limiar é estrito (<), não inclusivo`, () => {
    // atual=102, anterior=100 -> variação = 2.0% exatos.
    const produto = makeProduto({ pontos: [makePonto({ preco: 102 }), makePonto({ preco: 100 })] });
    expect(tendencia(produto)).toBe("ALTA");
  });

  it(`ESTAVEL quando a queda fica logo abaixo do limiar em módulo (-${LIMIAR_ESTAVEL_PCT}% + epsilon)`, () => {
    // atual=98.01, anterior=100 -> variação = -1.99%.
    const produto = makeProduto({ pontos: [makePonto({ preco: 98.01 }), makePonto({ preco: 100 })] });
    expect(tendencia(produto)).toBe("ESTAVEL");
  });

  it(`QUEDA quando a variação é exatamente -${LIMIAR_ESTAVEL_PCT}%`, () => {
    // atual=98, anterior=100 -> variação = -2.0% exatos.
    const produto = makeProduto({ pontos: [makePonto({ preco: 98 }), makePonto({ preco: 100 })] });
    expect(tendencia(produto)).toBe("QUEDA");
  });

  it("ALTA quando a variação é claramente positiva e acima do limiar", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 20 }), makePonto({ preco: 10 })] });
    expect(tendencia(produto)).toBe("ALTA");
  });

  it("QUEDA quando a variação é claramente negativa e abaixo do limiar", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 5 }), makePonto({ preco: 10 })] });
    expect(tendencia(produto)).toBe("QUEDA");
  });

  it("ESTAVEL quando o preço não mudou (variação 0)", () => {
    const produto = makeProduto({ pontos: [makePonto({ preco: 10 }), makePonto({ preco: 10 })] });
    expect(tendencia(produto)).toBe("ESTAVEL");
  });
});

describe("produtosComHistorico", () => {
  it("filtra fora produtos com menos de 2 pontos", () => {
    const semHistorico = makeProduto({ produtoId: "p1", pontos: [] });
    const umPonto = makeProduto({ produtoId: "p2", pontos: [makePonto()] });
    const comHistorico = makeProduto({ produtoId: "p3", pontos: [makePonto(), makePonto()] });

    const resultado = produtosComHistorico([semHistorico, umPonto, comHistorico]);

    expect(resultado.map((p) => p.produtoId)).toEqual(["p3"]);
  });
});

describe("produtosAcimaDaUltimaReferencia / produtosComOportunidade — partição exata", () => {
  it("empate exato (variação == 0) conta como ACIMA, não como oportunidade", () => {
    const empate = makeProduto({
      produtoId: "p-empate",
      pontos: [makePonto({ preco: 10 }), makePonto({ preco: 10 })],
    });

    expect(produtosAcimaDaUltimaReferencia([empate]).map((p) => p.produtoId)).toEqual(["p-empate"]);
    expect(produtosComOportunidade([empate])).toEqual([]);
  });

  it("variação positiva vai para 'acima da referência'", () => {
    const subiu = makeProduto({
      produtoId: "p-subiu",
      pontos: [makePonto({ preco: 15 }), makePonto({ preco: 10 })],
    });

    expect(produtosAcimaDaUltimaReferencia([subiu]).map((p) => p.produtoId)).toEqual(["p-subiu"]);
    expect(produtosComOportunidade([subiu])).toEqual([]);
  });

  it("variação negativa vai para 'oportunidade'", () => {
    const caiu = makeProduto({
      produtoId: "p-caiu",
      pontos: [makePonto({ preco: 8 }), makePonto({ preco: 10 })],
    });

    expect(produtosComOportunidade([caiu]).map((p) => p.produtoId)).toEqual(["p-caiu"]);
    expect(produtosAcimaDaUltimaReferencia([caiu])).toEqual([]);
  });

  it("propriedade de partição: acima + oportunidade cobrem exatamente produtosComHistorico, sem sobreposição, num fixture misto", () => {
    const semHistorico1 = makeProduto({ produtoId: "sem-1", pontos: [] });
    const semHistorico2 = makeProduto({ produtoId: "sem-2", pontos: [makePonto()] });
    const subiu = makeProduto({ produtoId: "subiu", pontos: [makePonto({ preco: 20 }), makePonto({ preco: 10 })] });
    const empatou = makeProduto({ produtoId: "empatou", pontos: [makePonto({ preco: 10 }), makePonto({ preco: 10 })] });
    const caiu = makeProduto({ produtoId: "caiu", pontos: [makePonto({ preco: 5 }), makePonto({ preco: 10 })] });

    const produtos = [semHistorico1, semHistorico2, subiu, empatou, caiu];

    const comHistorico = produtosComHistorico(produtos);
    const acima = produtosAcimaDaUltimaReferencia(produtos);
    const oportunidade = produtosComOportunidade(produtos);

    expect(comHistorico.length).toBe(acima.length + oportunidade.length);
    expect(comHistorico.map((p) => p.produtoId).sort()).toEqual(["caiu", "empatou", "subiu"].sort());
    expect(acima.map((p) => p.produtoId).sort()).toEqual(["empatou", "subiu"].sort());
    expect(oportunidade.map((p) => p.produtoId)).toEqual(["caiu"]);

    // Sem sobreposição entre as duas partições.
    const idsAcima = new Set(acima.map((p) => p.produtoId));
    const idsOportunidade = new Set(oportunidade.map((p) => p.produtoId));
    for (const id of idsAcima) {
      expect(idsOportunidade.has(id)).toBe(false);
    }
  });
});
