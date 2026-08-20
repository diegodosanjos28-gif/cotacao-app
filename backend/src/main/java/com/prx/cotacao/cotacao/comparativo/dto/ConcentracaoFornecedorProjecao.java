package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Projeção da query nativa de concentração por fornecedor
// (CotacaoProdutoFornecedorRepository.buscarTopConcentracao) — nome resolvido em
// ConcentracaoFornecedorService, mesmo padrão dos demais painéis.
public interface ConcentracaoFornecedorProjecao {
    UUID getFornecedorId();
    BigDecimal getValorComprado();
    BigDecimal getSharePct();
}
