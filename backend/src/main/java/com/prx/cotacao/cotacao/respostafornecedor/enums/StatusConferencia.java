package com.prx.cotacao.cotacao.respostafornecedor.enums;

/**
 * Severidade de conferência de um item da resposta do fornecedor contra a lista base.
 * A ordem dos valores importa: REVISAR é sempre a severidade mais alta e, uma vez
 * atingida, nunca é rebaixada de volta para ATENCAO/OK (ver
 * ClassificacaoConferenciaService — porte de buildSupplierReview do protótipo).
 */
public enum StatusConferencia {
    OK,
    ATENCAO,
    REVISAR
}
