package com.prx.cotacao.fornecedor.dto;

import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.enums.OrigemCadastro;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FornecedorResponse(
        UUID id,
        String nome,
        String prazoEntregaPadrao,
        String condicaoPagamentoPadrao,
        BigDecimal pedidoMinimoPadrao,
        String observacoesPadrao,
        FornecedorStatus status,
        OrigemCadastro origemCadastro,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm
) {
    public static FornecedorResponse from(Fornecedor f) {
        return new FornecedorResponse(
                f.getId(), f.getNome(), f.getPrazoEntregaPadrao(), f.getCondicaoPagamentoPadrao(),
                f.getPedidoMinimoPadrao(), f.getObservacoesPadrao(), f.getStatus(), f.getOrigemCadastro(),
                f.getCriadoEm(), f.getAtualizadoEm());
    }
}
