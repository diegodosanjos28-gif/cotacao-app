package com.prx.cotacao.cotacao.comparativo.dto;

import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ComparativoItemResponse(
        UUID cotacaoProdutoId,
        UUID produtoId,
        String nomeProduto,
        BigDecimal quantidade,
        String unidade,
        List<PrecoFornecedor> precosPorFornecedor
) {
    public record PrecoFornecedor(
            UUID fornecedorId,
            String nomeFornecedor,
            BigDecimal precoInformado,
            BigDecimal precoUnitarioCalculado,
            boolean semEstoque,
            StatusItem status,
            boolean divergenciaComparativa
    ) {}
}
