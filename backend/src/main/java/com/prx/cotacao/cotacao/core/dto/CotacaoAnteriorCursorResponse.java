package com.prx.cotacao.cotacao.core.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

// Um mini card do carrossel "Cotações anteriores" da landing (Entrada de Dados) —
// só cotações FINALIZADA (ver CotacaoAnterioresService). Campos agregados já vêm
// prontos do banco, o frontend não recalcula nada, só formata.
public record CotacaoAnteriorCursorResponse(
        UUID id,
        OffsetDateTime finalizadaEm,
        long itensListaBase,
        long itensCotados,
        long fornecedoresCount
) {
}
