package com.prx.cotacao.cotacao.respostafornecedor.parser;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.PrazoEntregaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrazoEntregaParserTest {

    @ParameterizedTest
    @CsvSource({
            "2 dias úteis, 2",
            "até 5 dias, 5",
            "3dias, 3",
            "entrega em 10 DIAS, 10",
            "1 dia, 1",
    })
    void extrai_numero_de_dias_do_texto_livre(String texto, int esperado) {
        assertEquals(esperado, PrazoEntregaParser.extrairDias(texto));
    }

    @ParameterizedTest
    @ValueSource(strings = {"entrega imediata", "combinar com o vendedor", "rápido"})
    void retorna_null_quando_nao_ha_numero_de_dias_reconhecivel(String texto) {
        assertNull(PrazoEntregaParser.extrairDias(texto));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void retorna_null_para_texto_nulo_ou_vazio(String texto) {
        assertNull(PrazoEntregaParser.extrairDias(texto));
    }

    @Test
    void pega_o_primeiro_numero_seguido_de_dias_quando_ha_mais_de_um_no_texto() {
        // O regex exige dígito diretamente seguido de "dia(s)" (com espaço opcional
        // entre eles) — "2 a 4 dias" não casa em "2" (seguido de " a 4...", não de
        // "dias"), só em "4 dias". Este teste documenta esse limite da heurística.
        assertEquals(4, PrazoEntregaParser.extrairDias("2 a 4 dias, dependendo da região"));
        assertEquals(3, PrazoEntregaParser.extrairDias("3 dias ou 5 dias, dependendo do pedido"));
    }
}
