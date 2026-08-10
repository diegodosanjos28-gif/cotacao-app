package com.prx.cotacao.catalogo.dto;

import com.prx.cotacao.catalogo.entity.Produto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProdutoResponse(
        UUID id,
        String nome,
        String marca,
        BigDecimal pesoVolumeValor,
        String pesoVolumeUnidade,
        String unidadePadrao,
        Integer embalagemQtdSugerida,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm
) {
    public static ProdutoResponse from(Produto p) {
        return new ProdutoResponse(
                p.getId(), p.getNome(), p.getMarca(), p.getPesoVolumeValor(), p.getPesoVolumeUnidade(),
                p.getUnidadePadrao(), p.getEmbalagemQtdSugerida(), p.getCriadoEm(), p.getAtualizadoEm());
    }
}
