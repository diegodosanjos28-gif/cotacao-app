package com.prx.cotacao.fornecedor.dto;

import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record FornecedorRequest(
        @NotBlank String nome,
        String prazoEntregaPadrao,
        String condicaoPagamentoPadrao,
        BigDecimal pedidoMinimoPadrao,
        String observacoesPadrao,
        FornecedorStatus status
) {}
