package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Painel "Concentração de Fornecedores" (Dashboard) — Top N fornecedores por
// participação no valor comprado dentro de uma competência (mês). limiteDependenciaPct
// vem do backend (ConcentracaoFornecedorService.LIMITE_DEPENDENCIA_PCT) pro frontend
// nunca hardcodar o limite.
public record ConcentracaoFornecedoresResponse(
        List<ItemConcentracao> fornecedores,
        int limiteDependenciaPct,
        boolean algumEmRisco
) {
    public record ItemConcentracao(
            UUID fornecedorId,
            String nomeFornecedor,
            BigDecimal valorComprado,
            BigDecimal sharePct,
            boolean acimaDoLimite
    ) {
    }
}
