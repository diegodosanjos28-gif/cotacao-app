package com.prx.cotacao.historico.dto;

// Projeção da query agregada nativa (CotacaoProdutoFornecedorRepository.contarKpisHistorico)
// — os 3 contadores dos cards da tela Histórico de Preços, escopados ao tenant inteiro,
// independente de qual página da listagem está sendo exibida.
public interface HistoricoPrecoContadores {
    long getComHistorico();
    long getAcima();
    long getOportunidade();
}
