package com.prx.cotacao.cotacao.respostafornecedor.parser;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.*;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchingProdutoServiceTest {

    private MatchingProdutoService service;

    @BeforeEach
    void setUp() {
        service = new MatchingProdutoService(MarcaRepositoryFixtures.comMarcasDefault());
    }

    // --- normTxt ---

    @Test
    void normTxt_remove_acentos() {
        assertEquals("sazon legumes 60g", service.normTxt("Sazon Legumes 60g"));
    }

    @Test
    void normTxt_converte_maiusculo_minusculo() {
        assertEquals("bombom nestle", service.normTxt("BOMBOM NESTLE"));
    }

    @Test
    void normTxt_nao_remove_pontuacao_so_acento_e_caixa() {
        // normTxt agora espelha o protótipo literalmente: só remove acento, minúsculo,
        // colapsa espaço — a remoção de pontuação passou a ser responsabilidade da
        // tokenização (calcSimilaridade/calcSimilaridadeSemVolume), não de normTxt.
        assertEquals("achocolatado nescau® 400g!", service.normTxt("Achocolatado Nescau® 400g!"));
    }

    @Test
    void normTxt_colapsa_espacos_multiplos() {
        assertEquals("sal refinado", service.normTxt("sal   refinado"));
    }

    @Test
    void normTxt_remove_acento_variado() {
        assertEquals("indisponivel", service.normTxt("indisponível"));
    }

    @Test
    void normTxt_nulo_retorna_vazio() {
        assertEquals("", service.normTxt(null));
    }

    // --- extrairPesoVolume ---

    @Test
    void extrai_gramas() {
        PesoVolume pv = service.extrairPesoVolume("sazon legumes 60g");
        assertNotNull(pv);
        assertEquals(new BigDecimal("60"), pv.valor());
        assertEquals("g", pv.unidade());
    }

    @Test
    void extrai_kg() {
        PesoVolume pv = service.extrairPesoVolume("feijao carioca 1kg");
        assertNotNull(pv);
        assertEquals(new BigDecimal("1"), pv.valor());
        assertEquals("kg", pv.unidade());
    }

    @Test
    void extrai_ml() {
        PesoVolume pv = service.extrairPesoVolume("oleo girassol 500ml");
        assertNotNull(pv);
        assertEquals(new BigDecimal("500"), pv.valor());
        assertEquals("ml", pv.unidade());
    }

    @Test
    void extrai_litro_l() {
        PesoVolume pv = service.extrairPesoVolume("agua mineral 2l");
        assertNotNull(pv);
        assertEquals(new BigDecimal("2"), pv.valor());
        assertEquals("l", pv.unidade());
    }

    @Test
    void extrai_litro_lt() {
        // "lt" vem antes de "l" no regex para capturar o sufixo completo antes do mais curto,
        // mas a unidade retornada é normalizada pro padrão ("l") via MEDIDA_ALIAS_TO_STD.
        PesoVolume pv = service.extrairPesoVolume("refrigerante coca 2lt");
        assertNotNull(pv);
        assertEquals(new BigDecimal("2"), pv.valor());
        assertEquals("l", pv.unidade());
        assertEquals("volume", pv.dimensao());
        assertEquals(new BigDecimal("2000"), pv.valorBase());
    }

    @Test
    void extrai_peso_com_virgula() {
        PesoVolume pv = service.extrairPesoVolume("produto 1,5kg");
        assertNotNull(pv);
        assertEquals(new BigDecimal("1.5"), pv.valor());
        assertEquals("kg", pv.unidade());
    }

    @Test
    void retorna_nulo_sem_peso() {
        PesoVolume pv = service.extrairPesoVolume("bombom nestle");
        assertNull(pv);
    }

    @Test
    void unidade_normalizada_para_minusculo() {
        PesoVolume pv = service.extrairPesoVolume("produto 500ML");
        assertNotNull(pv);
        assertEquals("ml", pv.unidade());
    }

    // --- calcSimilaridade ---

    @Test
    void similaridade_identica_retorna_um() {
        BigDecimal score = service.calcSimilaridade("sazon legumes 60g", "sazon legumes 60g");
        assertEquals(0, new BigDecimal("1.00").compareTo(score));
    }

    @Test
    void similaridade_zero_quando_peso_difere_mesma_unidade() {
        // Caso especial: mesma unidade (g), valor diferente → retorna 0
        BigDecimal score = service.calcSimilaridade("sazon legumes 60g", "sazon legumes 500g");
        assertEquals(0, BigDecimal.ZERO.compareTo(score));
    }

    @Test
    void similaridade_bonus_quando_peso_bate_exato() {
        // "sazon legumes 60g" vs "sazon 60g" — peso bate, recebe bônus
        BigDecimal score = service.calcSimilaridade("sazon legumes 60g", "sazon 60g");
        // Tokens: {sazon, legumes, 60g} vs {sazon, 60g} → intersecao=2, max=3 → 0.667 + 0.2 = 0.867 → limitado
        assertTrue(score.compareTo(new BigDecimal("0.60")) > 0);
    }

    @Test
    void similaridade_sem_correspondencia() {
        BigDecimal score = service.calcSimilaridade("sal refinado", "bombom nestle");
        assertEquals(0, BigDecimal.ZERO.compareTo(score));
    }

    @Test
    void similaridade_parcial() {
        // "bombom nestle" vs "achocolatado nestle" — compartilham 'nestle'
        BigDecimal score = service.calcSimilaridade("bombom nestle", "achocolatado nestle");
        // Intersecao={nestle}, max=2 → 0.5
        assertTrue(score.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(score.compareTo(BigDecimal.ONE) < 0);
    }

    @Test
    void similaridade_normaliza_texto_antes_comparar() {
        // Deve normalizar e encontrar similaridade alta
        BigDecimal score = service.calcSimilaridade("Sazon Legumes 60g", "sazon legumes 60g");
        assertEquals(0, new BigDecimal("1.00").compareTo(score));
    }

    // --- conciliarProdutosCotados ---

    @Test
    void concilia_produto_com_match_acima_limiar() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> catalogo = List.of(
                new ProdutoCatalogo(id, "sazon legumes 60g")
        );
        List<ResultadoConciliacao> resultado = service.conciliarProdutosCotados(
                catalogo, List.of("sazon legumes 60g")
        );
        assertEquals(1, resultado.size());
        assertTrue(resultado.get(0).matched());
        assertEquals(id, resultado.get(0).produtoIdEncontrado());
    }

    @Test
    void concilia_produto_sem_match_retorna_not_matched() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> catalogo = List.of(
                new ProdutoCatalogo(id, "sazon legumes 60g")
        );
        List<ResultadoConciliacao> resultado = service.conciliarProdutosCotados(
                catalogo, List.of("macarrao espaguete 500g")
        );
        assertEquals(1, resultado.size());
        assertFalse(resultado.get(0).matched());
        assertNull(resultado.get(0).produtoIdEncontrado());
    }

    @Test
    void concilia_varios_produtos() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<ProdutoCatalogo> catalogo = List.of(
                new ProdutoCatalogo(id1, "sazon legumes 60g"),
                new ProdutoCatalogo(id2, "bombom nestle 250g")
        );
        List<ResultadoConciliacao> resultado = service.conciliarProdutosCotados(
                catalogo, List.of("sazon legumes 60g", "bombom nestle 250g")
        );
        assertEquals(2, resultado.size());
        assertTrue(resultado.get(0).matched());
        assertTrue(resultado.get(1).matched());
        assertEquals(id1, resultado.get(0).produtoIdEncontrado());
        assertEquals(id2, resultado.get(1).produtoIdEncontrado());
    }

    @Test
    void score_retornado_esta_entre_zero_e_um() {
        List<ProdutoCatalogo> catalogo = List.of(
                new ProdutoCatalogo(UUID.randomUUID(), "sazon legumes 60g")
        );
        List<ResultadoConciliacao> resultado = service.conciliarProdutosCotados(
                catalogo, List.of("sal refinado")
        );
        BigDecimal score = resultado.get(0).score();
        assertTrue(score.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(score.compareTo(BigDecimal.ONE) <= 0);
    }

    // --- detectarEmbalagem ---

    @Test
    void detecta_embalagem_por_razao_preco() {
        // 30.00 / mediana(10.00) = 3.0 → true
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("30.00"),
                List.of(new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("10.00")),
                "sazon legumes 60g"
        );
        assertTrue(resultado);
    }

    @Test
    void nao_detecta_embalagem_razao_abaixo_limite() {
        // 15.00 / mediana(10.00) = 1.5 → false
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("15.00"),
                List.of(new BigDecimal("10.00"), new BigDecimal("10.00")),
                "sazon legumes 60g"
        );
        assertFalse(resultado);
    }

    @Test
    void detecta_embalagem_por_palavra_caixa() {
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("5.00"),
                List.of(new BigDecimal("5.00")),
                "1cx bombom nestle"
        );
        assertTrue(resultado);
    }

    @Test
    void detecta_embalagem_por_palavra_fardo() {
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("5.00"),
                List.of(new BigDecimal("5.00")),
                "fardo refrigerante"
        );
        assertTrue(resultado);
    }

    @Test
    void detecta_embalagem_por_palavra_fd() {
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("5.00"),
                List.of(new BigDecimal("5.00")),
                "1fd agua mineral"
        );
        assertTrue(resultado);
    }

    @Test
    void detecta_embalagem_por_palavra_pacote() {
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("5.00"),
                List.of(new BigDecimal("5.00")),
                "pacote macarrao"
        );
        assertTrue(resultado);
    }

    @Test
    void detecta_embalagem_por_palavra_pct() {
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("5.00"),
                List.of(new BigDecimal("5.00")),
                "3pct sal refinado"
        );
        assertTrue(resultado);
    }

    @Test
    void nao_detecta_embalagem_sem_indicadores() {
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("2.89"),
                List.of(new BigDecimal("2.50"), new BigDecimal("3.00")),
                "sazon legumes 60g"
        );
        assertFalse(resultado);
    }

    @Test
    void mediana_calculada_corretamente_com_par_de_elementos() {
        // mediana de [8.00, 12.00] = 10.00 → 40.00/10.00 = 4.0 >= 3.0 → true
        boolean resultado = service.detectarEmbalagemPorPreco(
                new BigDecimal("40.00"),
                List.of(new BigDecimal("8.00"), new BigDecimal("12.00")),
                "produto sem palavra embalagem"
        );
        assertTrue(resultado);
    }

    // --- extrairMarca ---

    @Test
    void extrai_marca_da_lista_fixa_com_hifen() {
        assertEquals("coca-cola", service.extrairMarca("Refrigerante Coca-Cola 2L"));
    }

    @Test
    void extrai_marca_da_lista_fixa_grafia_com_espaco() {
        // Sem hífen — o texto não bate "coca-cola" (não contém o hífen), então cai
        // para a segunda grafia da lista fixa, "coca cola".
        assertEquals("coca cola", service.extrairMarca("Refrigerante Coca Cola 2L"));
    }

    @Test
    void extrai_marca_da_lista_fixa_em_produto_diferente() {
        assertEquals("heinz", service.extrairMarca("Ketchup Heinz 400g"));
    }

    @Test
    void extrai_marca_fallback_palavra_capitalizada_quando_nao_bate_lista_fixa() {
        // "Suco" não está na lista fixa de marcas — cai para a heurística de palavra
        // capitalizada, primeira que qualifica.
        assertEquals("suco", service.extrairMarca("Suco Especial Uva 1L"));
    }

    @Test
    void extrai_marca_fallback_exclui_palavras_nao_marca() {
        // "Com" é uma das palavras excluídas — a heurística pula para a próxima
        // palavra capitalizada válida.
        assertEquals("leite", service.extrairMarca("Com Leite Integral"));
    }

    @Test
    void extrai_marca_fallback_exclui_sabor_e_tipo() {
        assertEquals("morango", service.extrairMarca("Sabor Morango Tipo Iogurte"));
    }

    @Test
    void extrai_marca_retorna_nulo_sem_marca_fixa_e_sem_palavra_capitalizada() {
        assertNull(service.extrairMarca("produto generico teste"));
    }

    @Test
    void extrai_marca_nulo_para_texto_nulo() {
        assertNull(service.extrairMarca(null));
    }

    // --- calcSimilaridadeSemVolume ---

    @Test
    void similaridade_sem_volume_ignora_divergencia_de_medida_que_zeraria_calcSimilaridade() {
        // calcSimilaridade zera esse par por causa só da medida (60g != 500g) — a
        // versão "sem volume" ignora a medida e enxerga que o resto do texto é idêntico.
        BigDecimal comMedida = service.calcSimilaridade("sazon legumes 60g", "sazon legumes 500g");
        assertEquals(0, BigDecimal.ZERO.compareTo(comMedida));

        BigDecimal semMedida = service.calcSimilaridadeSemVolume("sazon legumes 60g", "sazon legumes 500g");
        assertEquals(0, new BigDecimal("1.00").compareTo(semMedida));
    }

    @Test
    void similaridade_sem_volume_zero_quando_so_sobra_medida() {
        // Depois de remover a medida, não sobra texto nenhum de nenhum dos lados.
        BigDecimal score = service.calcSimilaridadeSemVolume("60g", "500ml");
        assertEquals(0, BigDecimal.ZERO.compareTo(score));
    }

    @Test
    void similaridade_sem_volume_sem_correspondencia_textual() {
        BigDecimal score = service.calcSimilaridadeSemVolume("sal refinado 1kg", "bombom nestle 250g");
        assertEquals(0, BigDecimal.ZERO.compareTo(score));
    }

    // --- detectarDivergenciaVolume ---

    @Test
    void divergencia_volume_mesma_dimensao_peso_valores_diferentes() {
        DivergenciaVolume dv = service.detectarDivergenciaVolume("arroz integral 5kg", "arroz integral 1kg");
        assertNotNull(dv);
        assertTrue(dv.divergente());
        assertEquals("5kg", dv.solicitado());
        assertEquals("1kg", dv.oferecido());
    }

    @Test
    void divergencia_volume_mesma_dimensao_volume_valores_diferentes() {
        DivergenciaVolume dv = service.detectarDivergenciaVolume("refrigerante 2l", "refrigerante 600ml");
        assertNotNull(dv);
        assertTrue(dv.divergente());
    }

    @Test
    void divergencia_volume_nula_quando_dimensoes_diferentes() {
        // peso (kg) vs volume (ml) — dimensões diferentes, não é divergência de medida.
        DivergenciaVolume dv = service.detectarDivergenciaVolume("agua 1kg", "agua 500ml");
        assertNull(dv);
    }

    @Test
    void divergencia_volume_nula_quando_lado_solicitado_sem_medida() {
        DivergenciaVolume dv = service.detectarDivergenciaVolume("agua mineral", "agua mineral 500ml");
        assertNull(dv);
    }

    @Test
    void divergencia_volume_nula_quando_lado_oferecido_sem_medida() {
        DivergenciaVolume dv = service.detectarDivergenciaVolume("agua mineral 500ml", "agua mineral");
        assertNull(dv);
    }

    @Test
    void divergencia_volume_nula_quando_valores_equivalentes_com_grafias_diferentes() {
        // 1L == 1000ml — mesmo valorBase, apesar de escritos diferente.
        DivergenciaVolume dv = service.detectarDivergenciaVolume("agua mineral 1l", "agua mineral 1000ml");
        assertNull(dv);
    }

    // --- detectarEmbalagemPorTexto ---

    @Test
    void detecta_embalagem_texto_caixa_com_quantidade() {
        EmbalagemDetectada e = service.detectarEmbalagemPorTexto("Arroz Caixa com 12 unidades");
        assertNotNull(e);
        assertEquals("caixa", e.tipoEmbalagem());
        assertEquals(12, e.qtdInterna());
    }

    @Test
    void detecta_embalagem_texto_fardo_com_quantidade() {
        EmbalagemDetectada e = service.detectarEmbalagemPorTexto("Refrigerante Fardo com 6 unidades");
        assertNotNull(e);
        assertEquals("fardo", e.tipoEmbalagem());
        assertEquals(6, e.qtdInterna());
    }

    @Test
    void detecta_embalagem_texto_pacote_sem_quantidade_explicita() {
        EmbalagemDetectada e = service.detectarEmbalagemPorTexto("Pacote de arroz 1kg");
        assertNotNull(e);
        assertEquals("pacote", e.tipoEmbalagem());
        assertNull(e.qtdInterna());
    }

    @Test
    void detecta_embalagem_texto_generica() {
        EmbalagemDetectada e = service.detectarEmbalagemPorTexto("Embalagem econômica");
        assertNotNull(e);
        assertEquals("embalagem", e.tipoEmbalagem());
    }

    @Test
    void detecta_embalagem_texto_quantidade_via_padrao_unidades_por_caixa() {
        EmbalagemDetectada e = service.detectarEmbalagemPorTexto("12 unidades por caixa");
        assertNotNull(e);
        assertEquals("caixa", e.tipoEmbalagem());
        assertEquals(12, e.qtdInterna());
    }

    @Test
    void detecta_embalagem_texto_retorna_nulo_sem_palavra_de_embalagem() {
        assertNull(service.detectarEmbalagemPorTexto("Sazon Legumes 60g"));
    }

    @Test
    void detecta_embalagem_texto_retorna_nulo_para_texto_nulo() {
        assertNull(service.detectarEmbalagemPorTexto(null));
    }
}
