package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Projeção da query agregada nativa (CotacaoProdutoFornecedorRepository.resumoEconomiaFinalizadas)
// — resultado cru do banco, antes de resolver o nome do fornecedor vencedor (ver
// EconomiaResumoService). fornecedorVencedorId/Contagem vêm null quando nenhuma
// cotação finalizada tem sequer um item com oferta válida.
public interface EconomiaResumoProjecao {
    long getCotacoesProcessadas();
    BigDecimal getEconomiaAcumulada();
    BigDecimal getMediaEconomiaPct();
    UUID getFornecedorVencedorId();
    Integer getFornecedorVencedorContagem();
}
