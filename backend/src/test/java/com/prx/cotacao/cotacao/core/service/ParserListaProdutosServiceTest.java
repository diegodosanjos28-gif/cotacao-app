package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaParseada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserListaProdutosServiceTest {

    private ParserListaProdutosService service;

    @BeforeEach
    void setUp() {
        service = new ParserListaProdutosService();
    }

    // --- fixtures do Guia de Formatação ---

    @Test
    void parseia_quantidade_unidade_nome_simples() {
        List<LinhaParseada> linhas = service.parsear("15un sazon legumes 60g");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("15"), l.quantidade());
        assertEquals("un", l.unidade());
        assertEquals("sazon legumes 60g", l.nomeProduto());
    }

    @Test
    void parseia_cx_caixa_bombom() {
        List<LinhaParseada> linhas = service.parsear("1cx bombom nestle");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("1"), l.quantidade());
        assertEquals("cx", l.unidade());
        assertEquals("bombom nestle", l.nomeProduto());
    }

    @Test
    void parseia_und_variante() {
        // "und" é alias de "un" no dicionário UNIT_ALIASES (normalizeUnit) — mudança de
        // comportamento deliberada da especificação técnica (seção 5): antes deste porte,
        // a unidade capturada não passava por normalização e ficava "und" literal.
        List<LinhaParseada> linhas = service.parsear("2und achocolatado nescau 400g");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("un", l.unidade());
        assertEquals("achocolatado nescau 400g", l.nomeProduto());
    }

    @Test
    void parseia_kg() {
        List<LinhaParseada> linhas = service.parsear("5kg feijao carioca");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("kg", l.unidade());
        assertEquals("feijao carioca", l.nomeProduto());
    }

    @Test
    void parseia_quantidade_decimal_com_virgula() {
        List<LinhaParseada> linhas = service.parsear("2,5kg arroz tipo1");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("2.5"), l.quantidade());
        assertEquals("kg", l.unidade());
    }

    @Test
    void parseia_pct() {
        List<LinhaParseada> linhas = service.parsear("3pct macarrao espaguete 500g");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("pct", l.unidade());
    }

    @Test
    void parseia_fd() {
        List<LinhaParseada> linhas = service.parsear("1fd refrigerante coca 2l");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("fd", l.unidade());
    }

    @Test
    void parseia_dz() {
        List<LinhaParseada> linhas = service.parsear("1dz ovos brancos");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("dz", l.unidade());
    }

    // --- aliases de unidade por extenso (seção 5 da Especificação Técnica do Motor de
    // Conferência do Fornecedor: UNIT_ALIASES / normalizeUnit) ---

    @Test
    void parseia_fardos_por_extenso() {
        // "2 fardos" já casa direto no Padrão 1 (PADRAO_PRINCIPAL usa \s* entre
        // quantidade e unidade, então o espaço entre "2" e "fardos" não força o
        // fallback do Padrão 2 — confirmado lendo o comportamento real, não assumido).
        List<LinhaParseada> linhas = service.parsear("2 fardos de detergente");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("2"), l.quantidade());
        assertEquals("fd", l.unidade());
        assertEquals("de detergente", l.nomeProduto());
    }

    @Test
    void parseia_duzias_por_extenso_com_acento() {
        List<LinhaParseada> linhas = service.parsear("3 dúzias de ovos");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("dz", l.unidade());
        assertEquals("de ovos", l.nomeProduto());
    }

    @Test
    void parseia_caixa_por_extenso() {
        List<LinhaParseada> linhas = service.parsear("1 caixa de leite condensado");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("cx", l.unidade());
        assertEquals("de leite condensado", l.nomeProduto());
    }

    @Test
    void parseia_pacotes_por_extenso() {
        List<LinhaParseada> linhas = service.parsear("2 pacotes de bolacha");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("pct", l.unidade());
        assertEquals("de bolacha", l.nomeProduto());
    }

    @Test
    void parseia_unidade_por_extenso() {
        List<LinhaParseada> linhas = service.parsear("1 unidade de sabonete");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("un", l.unidade());
        assertEquals("de sabonete", l.nomeProduto());
    }

    @Test
    void parseia_f_abreviacao_fardo_colado_na_quantidade() {
        // "f" sozinho é abreviação de fardo. Como a unidade fica colada à quantidade
        // (sem espaço), o \s* de PADRAO_PRINCIPAL também casa direto (Padrão 1).
        List<LinhaParseada> linhas = service.parsear("2f arroz");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("2"), l.quantidade());
        assertEquals("fd", l.unidade());
        assertEquals("arroz", l.nomeProduto());
    }

    @Test
    void nao_reconhece_f_dentro_de_palavra_sem_fronteira() {
        // "fio" não é uma unidade reconhecida (não deve casar "f" isolado dentro da
        // palavra "fio" por falta de fronteira/espaço obrigatório após a unidade) —
        // garante que a abreviação "f" não gera falso positivo.
        List<LinhaParseada> linhas = service.parsear("2 fio dental");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertFalse(l.parseOk());
        assertEquals("un", l.unidade());
    }

    @Test
    void ignora_linha_em_branco() {
        List<LinhaParseada> linhas = service.parsear("15un sazon legumes 60g\n\n1cx bombom nestle");
        assertEquals(2, linhas.size());
    }

    @Test
    void ignora_linha_so_numero() {
        List<LinhaParseada> linhas = service.parsear("1\n12\n15un sazon legumes 60g");
        assertEquals(1, linhas.size());
        assertEquals("sazon legumes 60g", linhas.get(0).nomeProduto());
    }

    @Test
    void fallback_linha_sem_unidade_comeca_com_numero() {
        // Número seguido de espaço e texto sem unidade reconhecida
        List<LinhaParseada> linhas = service.parsear("3 sal refinado");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertFalse(l.parseOk()); // fallback, não padrão principal
        assertEquals("un", l.unidade());
    }

    @Test
    void fallback_linha_sem_numero() {
        List<LinhaParseada> linhas = service.parsear("sal refinado iodado");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertFalse(l.parseOk());
        assertEquals(BigDecimal.ONE, l.quantidade());
        assertEquals("un", l.unidade());
        assertEquals("sal refinado iodado", l.nomeProduto());
    }

    @Test
    void parseia_multiplas_linhas() {
        String texto = "15un sazon legumes 60g\n1cx bombom nestle\n2und achocolatado nescau 400g";
        List<LinhaParseada> linhas = service.parsear(texto);
        assertEquals(3, linhas.size());
        assertTrue(linhas.get(0).parseOk());
        assertTrue(linhas.get(1).parseOk());
        assertTrue(linhas.get(2).parseOk());
    }

    @Test
    void case_insensitive_unidade() {
        List<LinhaParseada> linhas = service.parsear("2UN sal grosso");
        assertEquals(1, linhas.size());
        assertTrue(linhas.get(0).parseOk());
        // Unidade normalizada para minúsculo
        assertEquals("un", linhas.get(0).unidade());
    }

    @Test
    void texto_nulo_retorna_lista_vazia() {
        List<LinhaParseada> linhas = service.parsear(null);
        assertTrue(linhas.isEmpty());
    }

    @Test
    void texto_em_branco_retorna_lista_vazia() {
        List<LinhaParseada> linhas = service.parsear("   ");
        assertTrue(linhas.isEmpty());
    }

    // --- ampliação do dicionário UNIT_ALIASES conforme tabela oficial de unidade
    // comercial da NF-e (SEFAZ) — ver relatório de porte para lista completa ---

    @Test
    void parseia_gr_grama() {
        List<LinhaParseada> linhas = service.parsear("10gr acucar");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("10"), l.quantidade());
        assertEquals("g", l.unidade());
        assertEquals("acucar", l.nomeProduto());
    }

    @Test
    void parseia_dzs_duzia() {
        List<LinhaParseada> linhas = service.parsear("3 dzs ovos");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("dz", l.unidade());
        assertEquals("ovos", l.nomeProduto());
    }

    @Test
    void parseia_cento() {
        List<LinhaParseada> linhas = service.parsear("5cento pao");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals(new BigDecimal("5"), l.quantidade());
        assertEquals("cento", l.unidade());
        assertEquals("pao", l.nomeProduto());
    }

    @Test
    void parseia_galao() {
        List<LinhaParseada> linhas = service.parsear("2galao agua");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("galao", l.unidade());
        assertEquals("agua", l.nomeProduto());
    }

    @Test
    void parseia_bandej() {
        List<LinhaParseada> linhas = service.parsear("1bandej ovos");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("bandej", l.unidade());
        assertEquals("ovos", l.nomeProduto());
    }

    @Test
    void parseia_disp_display() {
        List<LinhaParseada> linhas = service.parsear("3disp chiclete");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("disp", l.unidade());
        assertEquals("chiclete", l.nomeProduto());
    }

    @Test
    void parseia_gl_alias_galao() {
        List<LinhaParseada> linhas = service.parsear("1gl agua mineral");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("galao", l.unidade());
    }

    @Test
    void parseia_gf_garrafa() {
        List<LinhaParseada> linhas = service.parsear("2gf vinho");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("gf", l.unidade());
    }

    @Test
    void parseia_lata() {
        List<LinhaParseada> linhas = service.parsear("6lata cerveja");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("lata", l.unidade());
    }

    @Test
    void parseia_kilo_alias_kg() {
        List<LinhaParseada> linhas = service.parsear("2kilo tomate");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertTrue(l.parseOk());
        assertEquals("kg", l.unidade());
    }

    @Test
    void unidade_desconhecida_mantem_a_si_mesma() {
        // Token de unidade não presente em UNIT_ALIASES não participa da alternação da
        // regex de PADRAO_PRINCIPAL, então a linha cai no fallback (Padrão 2), com
        // unidade fixada em "un" — confirma que um token bizarro não rejeita a linha.
        List<LinhaParseada> linhas = service.parsear("3xyz produto estranho");
        assertEquals(1, linhas.size());
        LinhaParseada l = linhas.get(0);
        assertFalse(l.parseOk());
        assertEquals("un", l.unidade());
    }
}
