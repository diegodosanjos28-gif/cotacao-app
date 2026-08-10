package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ResultadoConciliacao(
        String nomeOriginal,
        UUID produtoIdEncontrado,
        BigDecimal score,
        boolean matched
) {}
