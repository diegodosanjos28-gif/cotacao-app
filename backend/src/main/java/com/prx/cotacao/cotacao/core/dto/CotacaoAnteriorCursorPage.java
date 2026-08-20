package com.prx.cotacao.cotacao.core.dto;

import java.util.List;

// Página por cursor/keyset do carrossel "Cotações anteriores" — mesmo shape de
// CotacaoFinalizadaCursorPage (Dashboard), DTO próprio porque os campos são
// diferentes (sem dado de economia, com itens da lista base). nextCursor é um token
// opaco que o frontend só repassa de volta, nunca interpreta (ver
// CotacaoAnterioresService.paginar).
public record CotacaoAnteriorCursorPage(
        List<CotacaoAnteriorCursorResponse> items,
        String nextCursor,
        boolean hasMore,
        long totalElements
) {
}
