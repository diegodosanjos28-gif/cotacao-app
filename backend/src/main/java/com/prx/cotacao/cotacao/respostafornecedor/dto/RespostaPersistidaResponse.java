package com.prx.cotacao.cotacao.respostafornecedor.dto;

/**
 * Resposta de {@code GET /cotacoes/{id}/fornecedores/{fornId}/resposta-persistida} —
 * reconstrói, a partir do que já está gravado em {@code cotacao_produto_fornecedor}
 * (texto_original por linha), o texto que o operador colaria de novo pra reabrir a
 * Conferência de uma resposta que já chegou persistida sem passar pelo preview (hoje,
 * só o caminho WhatsApp — ver {@code WhatsappRespostaFornecedorService}). {@code texto}
 * vazio significa que este fornecedor não tem nenhuma linha persistida ainda.
 */
public record RespostaPersistidaResponse(String texto) {}
