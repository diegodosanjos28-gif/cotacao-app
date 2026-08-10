package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

/**
 * Divergência de peso/volume entre o item solicitado (lista base) e o item oferecido
 * (resposta do fornecedor) — mesma dimensão (peso x peso, ou volume x volume), mas
 * valorBase diferente. Espelha o retorno de {@code detectarDivergenciaVolume} do protótipo.
 */
public record DivergenciaVolume(
        String solicitado,
        String oferecido,
        boolean divergente
) {}
