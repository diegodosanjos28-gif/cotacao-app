package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Painel "Variação de Preço por Produto" (Dashboard) — Top N produtos por spread
// dentro de uma competência (mês), regra de cálculo em VariacaoPrecoService.
public record VariacaoPrecoResponse(
        List<ItemVariacao> produtos,
        int totalProdutosComparados
) {
    public record ItemVariacao(
            UUID produtoId,
            String nomeProduto,
            BigDecimal menorPreco,
            BigDecimal maiorPreco,
            BigDecimal spreadPct,
            String fornecedorMenorNome,
            String fornecedorMaiorNome
    ) {
    }
}
