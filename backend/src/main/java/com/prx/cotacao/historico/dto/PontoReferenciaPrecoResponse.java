package com.prx.cotacao.historico.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PontoReferenciaPrecoResponse(
        UUID cotacaoId,
        OffsetDateTime finalizadaEm,
        BigDecimal preco,
        UUID fornecedorId,
        String nomeFornecedor
) {
}
