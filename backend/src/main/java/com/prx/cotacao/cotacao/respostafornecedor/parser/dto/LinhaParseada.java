package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import java.math.BigDecimal;

public record LinhaParseada(
        String textoOriginal,
        BigDecimal quantidade,
        String unidade,
        String nomeProduto,
        boolean parseOk
) {}
