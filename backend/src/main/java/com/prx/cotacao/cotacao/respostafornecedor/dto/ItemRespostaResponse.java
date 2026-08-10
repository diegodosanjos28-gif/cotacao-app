package com.prx.cotacao.cotacao.respostafornecedor.dto;

import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemRespostaResponse(
        UUID id,
        String textoOriginal,
        String nomeProduto,
        BigDecimal precoInformado,
        BigDecimal precoUnitarioCalculado,
        boolean semEstoque,
        BigDecimal confiancaMatch,
        StatusItem status
) {}
