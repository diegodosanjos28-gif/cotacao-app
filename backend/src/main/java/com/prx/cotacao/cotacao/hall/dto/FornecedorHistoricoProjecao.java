package com.prx.cotacao.cotacao.hall.dto;

import java.util.UUID;

// Projeção da query nativa de agregação do Hall histórico (ver
// CotacaoFornecedorRepository.buscarHistoricoFornecedores).
public interface FornecedorHistoricoProjecao {
    UUID getFornecedorId();
    String getNome();
    long getCotacoesParticipadas();
    double getCoberturaMediaPct();
    double getTempoRespostaMedioMinutos();
}
