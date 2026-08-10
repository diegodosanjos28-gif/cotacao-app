package com.prx.cotacao.cotacao.respostafornecedor.enums;

/**
 * Motivos de divergência entre o item base da cotação e o que o fornecedor ofereceu —
 * espelham os códigos de string usados em buildSupplierReview/_srCT do protótipo
 * ('brand_changed', 'weight_changed', etc.).
 */
public enum MotivoConferencia {
    BRAND_CHANGED,
    WEIGHT_CHANGED,
    WEIGHT_ADDED,
    VOLUME_ADDED,
    PACKAGE_QTY_ADDED,
    PACKAGE_QTY_CHANGED,
    PACKAGE_PRICE_SUSPECTED,
    MULTIPLE_OPTIONS,
    EXTRA_ITEM,
    LOW_CONFIDENCE_MATCH
}
