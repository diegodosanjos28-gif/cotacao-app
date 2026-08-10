package com.prx.cotacao.historico.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record HistoricoPrecoProdutoResponse(
        UUID produtoId,
        String nomeProduto,
        BigDecimal quantidadeReferencia,
        String unidadeReferencia,
        List<PontoReferenciaPrecoResponse> pontos
) {
}
