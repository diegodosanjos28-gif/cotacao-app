package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import java.math.BigDecimal;

/**
 * Uma linha da resposta do fornecedor candidata a corresponder a um item base da
 * cotação (ou um item extra, sem item base associado). Espelha os campos usados em
 * buildSupplierReview do protótipo (rl/br/pr/ds nos "alts", e os equivalentes inline
 * para match único).
 */
public record CandidatoResposta(
        String textoOriginal,
        String marcaOferecida,
        BigDecimal precoInformado,
        BigDecimal confiancaMatch,
        boolean semEstoque
) {}
