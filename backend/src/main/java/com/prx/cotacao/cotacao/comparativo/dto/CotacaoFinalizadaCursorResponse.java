package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// Um card do carrossel "Cotações Registradas" (Dashboard). Campos agregados já vêm
// prontos do banco (ver CotacaoFinalizadaCursorProjecao) — o frontend não recalcula
// nada, só formata.
public record CotacaoFinalizadaCursorResponse(
        UUID id,
        OffsetDateTime finalizadaEm,
        long itensCotados,
        BigDecimal valorTotalComprado,
        BigDecimal economizado,
        BigDecimal economizadoPct,
        long fornecedoresCount
) {
}
