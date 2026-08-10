package com.prx.cotacao.cotacao.respostafornecedor.parser;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.DivergenciaVolume;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ItemConciliado;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConciliacaoRespostaServiceTest {

    private ConciliacaoRespostaService service;

    @BeforeEach
    void setUp() {
        service = new ConciliacaoRespostaService(new MatchingProdutoService(MarcaRepositoryFixtures.comMarcasDefault()));
    }

    // Helper: linha de fornecedor mínima, com os campos relevantes por teste.
    private LinhaFornecedor linha(String nomeProduto, boolean semEstoque) {
        return new LinhaFornecedor(
                nomeProduto, nomeProduto, new BigDecimal("1.00"), semEstoque, false,
                null, null, "unidade", false, null, null
        );
    }

    // --- conciliarEnhanced ---

    @Test
    void score_alto_gera_status_confirmado() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(new ProdutoCatalogo(id, "sazon legumes 60g"));
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("sazon legumes 60g", false)), base
        );
        assertEquals(1, resultado.size());
        ItemConciliado ic = resultado.get(0);
        assertEquals(ItemConciliado.STATUS_CONFIRMADO, ic.status());
        assertEquals(id, ic.produtoId());
        assertEquals(ItemConciliado.TIPO_EXATO, ic.tipoCorrespondencia());
    }

    @Test
    void score_medio_gera_status_provavel() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(
                new ProdutoCatalogo(id, "arroz integral organico premium especial fino 1kg")
        );
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("arroz popular 1kg", false)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertEquals(ItemConciliado.STATUS_PROVAVEL, ic.status());
        assertTrue(ic.confianca().compareTo(new BigDecimal("0.35")) >= 0);
        assertTrue(ic.confianca().compareTo(new BigDecimal("0.55")) < 0);
        assertEquals(id, ic.produtoId());
    }

    @Test
    void score_baixo_gera_status_nao_identificado() {
        List<ProdutoCatalogo> base = List.of(
                new ProdutoCatalogo(UUID.randomUUID(), "arroz integral 1kg")
        );
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("detergente limpol 500ml", false)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertEquals(ItemConciliado.STATUS_NAO_IDENTIFICADO, ic.status());
        assertNull(ic.produtoId());
    }

    @Test
    void segunda_passada_aceita_match_com_divergencia_de_volume_real() {
        // "arroz integral 1kg" (base) vs "arroz integral 5kg" (ofertado): a 1ª passada
        // zera (mesma dimensão, valor diferente). A 2ª passada (sem volume) enxerga que
        // o resto do texto é idêntico, aceita o match com score*0.6 e marca a divergência.
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(new ProdutoCatalogo(id, "arroz integral 1kg"));
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("arroz integral 5kg", false)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertEquals(id, ic.produtoId());
        assertNotNull(ic.divergenciaVolume());
        assertTrue(ic.divergenciaVolume().divergente());
        // score do 2º passe (1.0, texto idêntico sem a medida) * fator de penalidade 0.6
        assertEquals(0, new BigDecimal("0.60").compareTo(ic.confianca()));
    }

    @Test
    void item_sem_estoque_usa_limiar_04_e_confirma_match() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(
                new ProdutoCatalogo(id, "arroz integral organico premium especial fino 1kg")
        );
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("arroz popular 1kg", true)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertEquals(ItemConciliado.STATUS_SEM_ESTOQUE, ic.status());
        assertEquals(ItemConciliado.TIPO_SEM_ESTOQUE, ic.tipoCorrespondencia());
        assertEquals(id, ic.produtoId());
    }

    @Test
    void item_sem_estoque_nao_passa_pela_segunda_passada_de_volume() {
        // Sem o flag semEstoque, "arroz integral 5kg" seria resgatado pela 2ª passada
        // (ver segunda_passada_aceita_match_com_divergencia_de_volume_real). Com
        // semEstoque=true, a 2ª passada nunca roda: score direto (zerado pela divergência
        // de medida) fica abaixo do limiar 0.4 e o item vira não identificado.
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(new ProdutoCatalogo(id, "arroz integral 1kg"));
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("arroz integral 5kg", true)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertEquals(ItemConciliado.STATUS_NAO_IDENTIFICADO, ic.status());
        assertNull(ic.produtoId());
        assertEquals(ItemConciliado.TIPO_SEM_ESTOQUE, ic.tipoCorrespondencia());
    }

    @Test
    void marca_diferente_rebaixa_confirmado_para_provavel_quando_score_abaixo_de_075() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(
                new ProdutoCatalogo(id, "Ketchup Heinz Tradicional 400g")
        );
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("Ketchup Predilecta Suave 400g", false)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertTrue(ic.confianca().compareTo(new BigDecimal("0.55")) >= 0);
        assertTrue(ic.confianca().compareTo(new BigDecimal("0.75")) < 0);
        assertEquals(ItemConciliado.TIPO_MARCA_ALTERNATIVA, ic.tipoCorrespondencia());
        assertEquals(ItemConciliado.STATUS_PROVAVEL, ic.status());
    }

    @Test
    void marca_diferente_nao_rebaixa_confirmado_quando_score_075_ou_mais() {
        UUID id = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(new ProdutoCatalogo(id, "Ketchup Heinz 400g"));
        List<ItemConciliado> resultado = service.conciliarEnhanced(
                List.of(linha("Ketchup Predilecta 400g", false)), base
        );
        ItemConciliado ic = resultado.get(0);
        assertTrue(ic.confianca().compareTo(new BigDecimal("0.75")) >= 0);
        assertEquals(ItemConciliado.TIPO_MARCA_ALTERNATIVA, ic.tipoCorrespondencia());
        assertEquals(ItemConciliado.STATUS_CONFIRMADO, ic.status());
    }

    // --- agrupar ---

    private LinhaFornecedor linhaAgrupar(String texto) {
        return new LinhaFornecedor(texto, texto, new BigDecimal("1.00"), false, false,
                null, null, "unidade", false, null, null);
    }

    private ItemConciliado itemConciliado(String descricao, UUID produtoId, String status) {
        ItemConciliado ic = new ItemConciliado(linhaAgrupar(descricao), descricao);
        ic.setProdutoId(produtoId);
        ic.setStatus(status);
        return ic;
    }

    @Test
    void agrupar_demove_candidatos_divergentes_de_volume_para_o_pool_extra() {
        UUID p1 = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(new ProdutoCatalogo(p1, "arroz integral 1kg"));

        ItemConciliado direto = itemConciliado("arroz integral 1kg", p1, ItemConciliado.STATUS_CONFIRMADO);
        ItemConciliado divergente = itemConciliado("arroz integral 5kg", p1, ItemConciliado.STATUS_PROVAVEL);
        divergente.setDivergenciaVolume(new DivergenciaVolume("1kg", "5kg", true));
        divergente.setTipoCorrespondencia(ItemConciliado.TIPO_VOLUME_DIFERENTE);

        ConciliacaoRespostaService.ResultadoAgrupamento resultado =
                service.agrupar(List.of(direto, divergente), base);

        Map<UUID, List<ItemConciliado>> porProduto = resultado.porProduto();
        assertEquals(1, porProduto.get(p1).size());
        assertSame(direto, porProduto.get(p1).get(0));

        assertEquals(1, resultado.extras().size());
        assertSame(divergente, resultado.extras().get(0));
        assertNull(divergente.produtoId());
    }

    @Test
    void agrupar_redistribui_candidato_ambiguo_para_a_linha_base_que_ele_realmente_casa() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(
                new ProdutoCatalogo(p1, "arroz integral 1kg"),
                new ProdutoCatalogo(p2, "feijao carioca 1kg")
        );

        // Ambos "ganharam" p1 numa 1ª passada hipotética, mas item2 na verdade é
        // melhor match para p2 — a redistribuição deve resolver a ambiguidade.
        ItemConciliado item1 = itemConciliado("arroz integral 1kg", p1, ItemConciliado.STATUS_CONFIRMADO);
        ItemConciliado item2 = itemConciliado("feijao carioca 1kg", p1, ItemConciliado.STATUS_CONFIRMADO);

        ConciliacaoRespostaService.ResultadoAgrupamento resultado =
                service.agrupar(List.of(item1, item2), base);

        Map<UUID, List<ItemConciliado>> porProduto = resultado.porProduto();
        assertEquals(List.of(item1), porProduto.get(p1));
        assertEquals(List.of(item2), porProduto.get(p2));
        assertEquals(p2, item2.produtoId());
        assertTrue(resultado.extras().isEmpty());
    }

    @Test
    void agrupar_linha_ja_preenchida_nao_rouba_item_de_outra() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        List<ProdutoCatalogo> base = List.of(
                new ProdutoCatalogo(p1, "arroz integral 1kg"),
                new ProdutoCatalogo(p2, "feijao carioca 1kg")
        );

        // p2 já está preenchida com seu próprio candidato legítimo.
        ItemConciliado itemP2Proprio = itemConciliado("feijao carioca 1kg", p2, ItemConciliado.STATUS_CONFIRMADO);
        // p1 tem 2 candidatos ambíguos; item1b seria um match perfeito para p2, mas p2
        // já está ocupada e não pode "roubar" o item de p1.
        ItemConciliado item1a = itemConciliado("arroz integral 1kg", p1, ItemConciliado.STATUS_CONFIRMADO);
        ItemConciliado item1b = itemConciliado("feijao carioca 1kg", p1, ItemConciliado.STATUS_CONFIRMADO);

        ConciliacaoRespostaService.ResultadoAgrupamento resultado = service.agrupar(
                List.of(itemP2Proprio, item1a, item1b), base
        );

        Map<UUID, List<ItemConciliado>> porProduto = resultado.porProduto();
        assertEquals(2, porProduto.get(p1).size());
        assertTrue(porProduto.get(p1).contains(item1a));
        assertTrue(porProduto.get(p1).contains(item1b));
        assertEquals(List.of(itemP2Proprio), porProduto.get(p2));
        assertEquals(p1, item1b.produtoId());
    }
}
