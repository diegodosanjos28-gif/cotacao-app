package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;

/**
 * Resultado da detecção de embalagem por TEXTO (regex de "caixa/cx", "fardo/fd",
 * "pacote/pct/pack", "embalagem" + tentativa de extrair quantidade interna, ex.:
 * "caixa com 12"). Espelha o retorno de {@code detectarEmbalagem(texto)} do protótipo —
 * não confundir com {@link MatchingProdutoService#detectarEmbalagemPorPreco}, que é a
 * heurística de razão de preço já existente neste backend.
 */
public record EmbalagemDetectada(
        String tipoEmbalagem,
        Integer qtdInterna
) {}
