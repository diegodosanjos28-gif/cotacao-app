package com.prx.cotacao.cotacao.respostafornecedor.dto;

import java.util.List;

/**
 * Resposta de {@code POST /cotacoes/{id}/fornecedores/{fornId}/resposta} — preview de
 * classificação (roda parser + matching, NÃO persiste nada em
 * cotacao_produto_fornecedor). Só vira persistência de fato em
 * {@code POST .../confirmar} (ver plano, seção 3).
 */
public record PreviewRespostaResponse(
        ContadoresConferencia contadores,
        List<ItemConferenciaResponse> itens
) {}
