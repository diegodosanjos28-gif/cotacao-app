package com.prx.cotacao.cotacao.comparativo.dto;

import java.util.List;

// Página por cursor/keyset do carrossel — nextCursor é um token opaco
// ("<finalizadaEmIso>_<id>"); o frontend só passa ele de volta, nunca o interpreta
// (ver EconomiaCarrosselService.paginar). totalElements é o total de cotações que
// batem com o filtro (inicio/fim), não o total já carregado no cliente.
public record CotacaoFinalizadaCursorPage(
        List<CotacaoFinalizadaCursorResponse> items,
        String nextCursor,
        boolean hasMore,
        long totalElements
) {
}
