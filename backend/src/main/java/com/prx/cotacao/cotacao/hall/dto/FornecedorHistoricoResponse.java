package com.prx.cotacao.cotacao.hall.dto;

import java.util.UUID;

// Card do Hall dos Fornecedores em modo histórico (sem cotação em andamento) —
// médias consolidadas de todas as cotações FINALIZADA em que o fornecedor
// participou (CotacaoFornecedor CONFIRMADO). Ver HallFornecedoresService.
public record FornecedorHistoricoResponse(
        UUID fornecedorId,
        String nome,
        long cotacoesParticipadas,
        double coberturaMediaPct,
        double tempoRespostaMedioMinutos,
        SeloConfiabilidade selo
) {
}
