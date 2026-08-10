package com.prx.cotacao.catalogo.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ProdutoRequest(
        @NotBlank String nome,
        String marca,
        BigDecimal pesoVolumeValor,
        String pesoVolumeUnidade,
        String unidadePadrao
) {}
