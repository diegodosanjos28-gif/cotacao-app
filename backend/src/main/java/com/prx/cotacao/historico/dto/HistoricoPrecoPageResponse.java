package com.prx.cotacao.historico.dto;

import org.springframework.data.domain.Page;

// Única exceção ao padrão de devolver Page<T> cru (ver CotacaoResource) — os 3
// contadores são agregados de todo o catálogo do tenant, não da página exibida, então
// não cabem dentro do Page<HistoricoPrecoProdutoResponse> sem distorcer seu significado.
public record HistoricoPrecoPageResponse(
        Page<HistoricoPrecoProdutoResponse> pagina,
        long produtosComHistorico,
        long acimaDaUltimaReferencia,
        long oportunidades
) {
}
