package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.util.UUID;

// Projeção da query nativa de spread por produto (CotacaoProdutoFornecedorRepository.
// buscarTopSpread) — traz só ids de produto/fornecedor; nome é resolvido em
// VariacaoPrecoService (mesmo padrão de EconomiaResumoService: consulta fica só com
// números, resolução de nome/INATIVO fica em Java).
public interface VariacaoPrecoProjecao {
    UUID getProdutoId();
    BigDecimal getMenorPreco();
    BigDecimal getMaiorPreco();
    BigDecimal getSpreadPct();
    UUID getFornecedorMenorId();
    UUID getFornecedorMaiorId();
    long getTotalElegiveis();
}
