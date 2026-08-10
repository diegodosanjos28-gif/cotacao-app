package com.prx.cotacao.cotacao.respostafornecedor.dto;

import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;

import java.util.UUID;

public record CotacaoFornecedorResponse(
        UUID id,
        UUID fornecedorId,
        String nomeFornecedor,
        Integer ordem,
        CotacaoFornecedorStatus status
) {
    public static CotacaoFornecedorResponse from(CotacaoFornecedor cf, String nomeFornecedor) {
        return new CotacaoFornecedorResponse(
                cf.getId(), cf.getFornecedorId(), nomeFornecedor, cf.getOrdem(), cf.getStatus());
    }
}
