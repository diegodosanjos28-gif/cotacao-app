package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import java.math.BigDecimal;

/**
 * Medida de peso/volume extraída de um texto (ex.: "60g", "1,5 litros").
 *
 * @param valor     valor numérico bruto, na unidade em que foi escrito (ex.: 1.5 em "1,5kg")
 * @param unidade   unidade já normalizada para a forma padrão (via MEDIDA_ALIAS_TO_STD do
 *                  protótipo — ex.: "litro"/"lt" viram "l", "quilo"/"kilo" viram "kg")
 * @param dimensao  "peso", "volume", ou null se a unidade não estiver mapeada em MEDIDA_DIM
 * @param valorBase valor convertido para a unidade-base da dimensão (g para peso, ml para
 *                  volume — ex.: 1kg vira valorBase=1000); igual a {@code valor} quando
 *                  {@code dimensao} é null
 */
public record PesoVolume(
        BigDecimal valor,
        String unidade,
        String dimensao,
        BigDecimal valorBase
) {}
