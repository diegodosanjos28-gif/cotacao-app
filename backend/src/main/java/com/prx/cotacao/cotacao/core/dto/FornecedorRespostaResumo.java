package com.prx.cotacao.cotacao.core.dto;

import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// Um fornecedor dentro do card "Cotação atual" (avatar strip + Hall modo ativa) —
// ver CotacaoAtualResponse. respondeuEm é o atualizadoEm de CotacaoFornecedor quando
// o status já saiu de PENDENTE (aproximação: não existe uma coluna dedicada de
// "respondido em" no schema, ver HallFornecedoresService).
public record FornecedorRespostaResumo(
        UUID fornecedorId,
        String nome,
        CotacaoFornecedorStatus status,
        OffsetDateTime respondeuEm,
        long itensCotados
) {
}
