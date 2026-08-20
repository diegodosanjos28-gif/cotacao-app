package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;

// KPIs de "Economia de Cotações" (Dashboard) agregados sobre TODAS as cotações
// FINALIZADA do tenant — não só uma janela paginada. Espelha exatamente
// frontend/src/lib/comparativo.ts (totalEconomia/percentualEconomia/
// fornecedorMaisCompetitivo), agora calculado no banco em vez de precisar buscar
// comparativo() de cada cotação no frontend.
public record EconomiaResumoResponse(
        long cotacoesProcessadas,
        BigDecimal economiaAcumulada,
        BigDecimal mediaEconomiaPct,
        String fornecedorMaisCompetitivoNome,
        Integer fornecedorMaisCompetitivoContagem
) {
}
