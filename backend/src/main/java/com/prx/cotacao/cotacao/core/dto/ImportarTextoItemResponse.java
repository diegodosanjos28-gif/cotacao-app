package com.prx.cotacao.cotacao.core.dto;

import java.math.BigDecimal;
import java.util.UUID;
import com.prx.cotacao.cotacao.core.service.CotacaoListaService;

// duplicado=true significa que o produto resolvido já estava vivo na cotação e a linha
// NÃO foi inserida (id vem nulo) — ver CotacaoListaService#importarTexto.
public record ImportarTextoItemResponse(
        UUID id,
        String textoOriginal,
        BigDecimal quantidade,
        String unidade,
        UUID produtoIdEncontrado,
        boolean matched,
        boolean duplicado
) {}
