package com.prx.cotacao.cotacao.core.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemListaResponse(
        UUID id,
        String textoOriginal,
        BigDecimal quantidade,
        String unidade,
        UUID produtoIdEncontrado,
        BigDecimal scoreMatch,
        boolean matched,
        // Prompt 12: true quando já existe cotacao_produto_fornecedor vinculado (item
        // conferido/confirmado) — trava edição de produto/quantidade/unidade no grid.
        boolean temRespostaFornecedorConfirmada
) {}
