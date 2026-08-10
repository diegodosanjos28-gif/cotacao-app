package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.respostafornecedor.entity.ItemConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.EmbalagemDetectada;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ItemConciliado;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.MarcaRepositoryFixtures;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.mensagem.service.PrecoReferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.service.ClassificacaoConferenciaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClassificacaoConferenciaServiceTest {

    private ClassificacaoConferenciaService service;

    @BeforeEach
    void setUp() {
        service = new ClassificacaoConferenciaService(
                new MatchingProdutoService(MarcaRepositoryFixtures.comMarcasDefault()), new PrecoReferenciaService());
    }

    // Helper: linha de fornecedor com controle total dos campos relevantes por teste.
    private LinhaFornecedor linha(String nomeProduto, BigDecimal preco, String marcaOferecida,
                                   EmbalagemDetectada embalagemDetectada, String precoBase, boolean precoPendente) {
        return new LinhaFornecedor(
                nomeProduto, nomeProduto, preco, false, false,
                embalagemDetectada, marcaOferecida, precoBase, precoPendente, null, null
        );
    }

    private ItemConciliado itemUnico(String descricao, UUID produtoId, String status, BigDecimal confianca,
                                      LinhaFornecedor linhaOriginal) {
        ItemConciliado ic = new ItemConciliado(linhaOriginal, descricao);
        ic.setProdutoId(produtoId);
        ic.setStatus(status);
        ic.setConfianca(confianca);
        return ic;
    }

    private ConciliacaoRespostaService.ResultadoAgrupamento agrupamento(
            Map<UUID, List<ItemConciliado>> porProduto, List<ItemConciliado> extras) {
        return new ConciliacaoRespostaService.ResultadoAgrupamento(porProduto, extras);
    }

    // --- match único sem divergência ---

    @Test
    void match_unico_sem_divergencia_gera_ok_sem_motivos() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "sazon legumes 60g");
        LinhaFornecedor l = linha("sazon legumes 60g", new BigDecimal("2.89"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("sazon legumes 60g", p.id(), ItemConciliado.STATUS_CONFIRMADO, BigDecimal.ONE, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        assertEquals(1, resultado.size());
        ItemConferencia r = resultado.get(0);
        assertEquals(p.id(), r.itemBaseId());
        assertEquals(StatusConferencia.OK, r.status());
        assertTrue(r.motivos().isEmpty());
    }

    // --- BRAND_CHANGED ---

    @Test
    void marca_diferente_gera_brand_changed_atencao() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Ketchup Heinz 400g");
        LinhaFornecedor l = linha("Ketchup Predilecta 400g", new BigDecimal("8.90"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Ketchup Predilecta 400g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.ATENCAO, r.status());
        assertEquals(List.of(MotivoConferencia.BRAND_CHANGED), r.motivos());
    }

    // --- WEIGHT_CHANGED ---

    @Test
    void medida_divergente_gera_weight_changed_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Arroz Integral 1kg");
        LinhaFornecedor l = linha("Arroz Integral 5kg", new BigDecimal("18.90"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Arroz Integral 5kg", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.WEIGHT_CHANGED), r.motivos());
    }

    @Test
    void weight_changed_nao_e_rebaixado_para_atencao_por_low_confidence_match() {
        // WEIGHT_CHANGED (REVISAR) + LOW_CONFIDENCE_MATCH (sozinho só levaria a ATENCAO)
        // — o status final tem que continuar REVISAR, nunca "voltar" para ATENCAO.
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Arroz Integral 1kg");
        LinhaFornecedor l = linha("Arroz Integral 5kg", new BigDecimal("18.90"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Arroz Integral 5kg", p.id(), ItemConciliado.STATUS_PROVAVEL,
                new BigDecimal("0.3"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertTrue(r.motivos().contains(MotivoConferencia.WEIGHT_CHANGED));
        assertTrue(r.motivos().contains(MotivoConferencia.LOW_CONFIDENCE_MATCH));
    }

    // --- WEIGHT_ADDED / VOLUME_ADDED ---

    @Test
    void medida_de_peso_so_na_resposta_gera_weight_added_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Bombom Nestle");
        LinhaFornecedor l = linha("Bombom Nestle 250g", new BigDecimal("4.50"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Bombom Nestle 250g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.WEIGHT_ADDED), r.motivos());
    }

    @Test
    void medida_de_volume_so_na_resposta_gera_volume_added_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Refrigerante Antarctica");
        LinhaFornecedor l = linha("Refrigerante Antarctica 2L", new BigDecimal("7.00"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Refrigerante Antarctica 2L", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.VOLUME_ADDED), r.motivos());
    }

    // --- PACKAGE_QTY_ADDED / PACKAGE_QTY_CHANGED ---

    @Test
    void quantidade_de_embalagem_so_na_resposta_gera_package_qty_added_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Bombom Nestle");
        LinhaFornecedor l = linha("Bombom Nestle Caixa com 12", new BigDecimal("40.00"), null,
                new EmbalagemDetectada("caixa", 12), "unidade", false);
        ItemConciliado ic = itemUnico("Bombom Nestle Caixa com 12", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.PACKAGE_QTY_ADDED), r.motivos());
    }

    @Test
    void quantidade_de_embalagem_diferente_gera_package_qty_changed_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Bombom Nestle Caixa com 6");
        LinhaFornecedor l = linha("Bombom Nestle Caixa com 12", new BigDecimal("40.00"), null,
                new EmbalagemDetectada("caixa", 12), "unidade", false);
        ItemConciliado ic = itemUnico("Bombom Nestle Caixa com 12", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.PACKAGE_QTY_CHANGED), r.motivos());
    }

    // --- PACKAGE_PRICE_SUSPECTED ---

    @Test
    void preco_pendente_gera_package_price_suspected_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        LinhaFornecedor l = linha("Sazon Legumes 60g", null, null, null, "sem_preco", true);
        ItemConciliado ic = itemUnico("Sazon Legumes 60g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.PACKAGE_PRICE_SUSPECTED), r.motivos());
    }

    // --- B.3 (pacote de ajustes pós-call): PACKAGE_PRICE_SUSPECTED também para item
    // COM preço, quando ele é >=1.5x a referência (mediana dos demais fornecedores já
    // confirmados nesta cotação) — ativa uma via antes deliberadamente inerte. ---

    @Test
    void preco_com_valor_maior_ou_igual_a_1_5x_a_referencia_gera_package_price_suspected_revisar() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        // Preço normal, reconhecido (não "consultar", não precoBase suspeito no texto)
        // — só a referência externa (outros fornecedores) sinaliza a suspeita.
        LinhaFornecedor l = linha("Sazon Legumes 60g", new BigDecimal("15.00"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Sazon Legumes 60g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
                , Map.of(p.id(), new BigDecimal("10.00"))
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status(),
                "15.00 >= 1.5x referência (10.00) = 15.00 deveria escalar para REVISAR");
        assertEquals(List.of(MotivoConferencia.PACKAGE_PRICE_SUSPECTED), r.motivos());
    }

    @Test
    void preco_abaixo_de_1_5x_a_referencia_nao_gera_package_price_suspected() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        LinhaFornecedor l = linha("Sazon Legumes 60g", new BigDecimal("14.99"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Sazon Legumes 60g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
                , Map.of(p.id(), new BigDecimal("10.00"))
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.OK, r.status());
        assertTrue(r.motivos().isEmpty());
    }

    @Test
    void sem_referencia_disponivel_nao_gera_package_price_suspected() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        LinhaFornecedor l = linha("Sazon Legumes 60g", new BigDecimal("999.00"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Sazon Legumes 60g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l);

        // referenciaPorItemBase vazio — nenhum outro fornecedor confirmado ainda.
        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
                , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.OK, r.status());
        assertTrue(r.motivos().isEmpty());
    }

    // --- LOW_CONFIDENCE_MATCH ---

    @Test
    void confianca_baixa_com_status_provavel_gera_low_confidence_match_atencao() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        LinhaFornecedor l = linha("Sazon Legumes 60g", new BigDecimal("2.89"), null, null, "unidade", false);
        ItemConciliado ic = itemUnico("Sazon Legumes 60g", p.id(), ItemConciliado.STATUS_PROVAVEL,
                new BigDecimal("0.3"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.ATENCAO, r.status());
        assertEquals(List.of(MotivoConferencia.LOW_CONFIDENCE_MATCH), r.motivos());
    }

    // --- MULTIPLE_OPTIONS ---

    @Test
    void mais_de_um_candidato_gera_multiple_options_revisar_com_todos_os_candidatos() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        LinhaFornecedor l1 = linha("Sazon Legumes 60g", new BigDecimal("2.89"), null, null, "unidade", false);
        LinhaFornecedor l2 = linha("Sazon Ervas 60g", new BigDecimal("2.99"), null, null, "unidade", false);
        ItemConciliado ic1 = itemUnico("Sazon Legumes 60g", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                new BigDecimal("0.9"), l1);
        ItemConciliado ic2 = itemUnico("Sazon Ervas 60g", p.id(), ItemConciliado.STATUS_PROVAVEL,
                new BigDecimal("0.5"), l2);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic1, ic2), agrupamento(Map.of(p.id(), List.of(ic1, ic2)), List.of())
        , Map.of()
        );

        assertEquals(1, resultado.size());
        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.MULTIPLE_OPTIONS), r.motivos());
        assertEquals(2, r.candidatos().size());
        assertEquals("Sazon Legumes 60g", r.candidatos().get(0).textoOriginal());
        assertEquals("Sazon Ervas 60g", r.candidatos().get(1).textoOriginal());
    }

    // --- item base sem candidato ---

    @Test
    void nenhum_item_base_com_candidato_retorna_lista_vazia() {
        ProdutoCatalogo p1 = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        ProdutoCatalogo p2 = new ProdutoCatalogo(UUID.randomUUID(), "Bombom Nestle 250g");

        List<ItemConferencia> resultado = service.classificar(
                List.of(p1, p2), List.of(), agrupamento(Map.of(), List.of())
        , Map.of()
        );

        assertTrue(resultado.isEmpty());
    }

    @Test
    void item_base_sem_candidato_no_meio_de_outros_nao_aparece_e_nao_quebra_os_demais() {
        ProdutoCatalogo p1 = new ProdutoCatalogo(UUID.randomUUID(), "Sazon Legumes 60g");
        ProdutoCatalogo p2 = new ProdutoCatalogo(UUID.randomUUID(), "Bombom Nestle 250g");
        ProdutoCatalogo p3 = new ProdutoCatalogo(UUID.randomUUID(), "Arroz Integral 1kg");

        LinhaFornecedor l1 = linha("Sazon Legumes 60g", new BigDecimal("2.89"), null, null, "unidade", false);
        LinhaFornecedor l3 = linha("Arroz Integral 1kg", new BigDecimal("18.90"), null, null, "unidade", false);
        ItemConciliado ic1 = itemUnico("Sazon Legumes 60g", p1.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l1);
        ItemConciliado ic3 = itemUnico("Arroz Integral 1kg", p3.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l3);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p1, p2, p3), List.of(ic1, ic3),
                agrupamento(Map.of(p1.id(), List.of(ic1), p3.id(), List.of(ic3)), List.of())
        , Map.of()
        );

        assertEquals(2, resultado.size());
        List<UUID> ids = resultado.stream().map(ItemConferencia::itemBaseId).toList();
        assertTrue(ids.contains(p1.id()));
        assertTrue(ids.contains(p3.id()));
        assertFalse(ids.contains(p2.id()));
    }

    // --- EXTRA_ITEM ---

    @Test
    void candidato_do_pool_extra_sempre_gera_extra_item_revisar_nunca_atencao() {
        LinhaFornecedor l = linha("Detergente Ype 500ml", new BigDecimal("3.50"), null, null, "unidade", false);
        ItemConciliado extra = itemUnico("Detergente Ype 500ml", null, ItemConciliado.STATUS_PROVAVEL,
                new BigDecimal("0.5"), l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(), List.of(), agrupamento(Map.of(), List.of(extra))
        , Map.of()
        );

        assertEquals(1, resultado.size());
        ItemConferencia r = resultado.get(0);
        assertNull(r.itemBaseId());
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertNotEquals(StatusConferencia.ATENCAO, r.status());
        assertEquals(List.of(MotivoConferencia.EXTRA_ITEM), r.motivos());
    }

    @Test
    void linha_nao_identificada_com_preco_gera_extra_item_revisar() {
        LinhaFornecedor l = linha("Produto Desconhecido XYZ", new BigDecimal("9.99"), null, null, "unidade", false);
        ItemConciliado naoIdentificado = itemUnico("Produto Desconhecido XYZ", null,
                ItemConciliado.STATUS_NAO_IDENTIFICADO, BigDecimal.ZERO, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(), List.of(naoIdentificado), agrupamento(Map.of(), List.of())
        , Map.of()
        );

        assertEquals(1, resultado.size());
        ItemConferencia r = resultado.get(0);
        assertNull(r.itemBaseId());
        assertEquals(StatusConferencia.REVISAR, r.status());
        assertEquals(List.of(MotivoConferencia.EXTRA_ITEM), r.motivos());
    }

    @Test
    void linha_nao_identificada_sem_preco_nao_gera_item_extra() {
        LinhaFornecedor l = linha("blablabla nao reconhecido", null, null, null, null, false);
        ItemConciliado naoIdentificado = itemUnico("blablabla nao reconhecido", null,
                ItemConciliado.STATUS_NAO_IDENTIFICADO, BigDecimal.ZERO, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(), List.of(naoIdentificado), agrupamento(Map.of(), List.of())
        , Map.of()
        );

        assertTrue(resultado.isEmpty());
    }

    // --- B.2 (pacote de ajustes pós-call): item identificado sem preço não gera mais
    // aviso nem escala pra REVISAR — reverte o comportamento de PRICE_MISSING
    // (commit 87228f3), removido do enum MotivoConferencia. ---

    @Test
    void item_identificado_sem_preco_gera_ok_sem_motivos() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sal Grosso 1kg");
        // "1 fardo sal realta" — linha casa com o item base mas não traz preço
        // reconhecível (nem "consultar/a combinar", que teria precoBase="sem_preco" e
        // precoPendente=true — esse é o caso PACKAGE_PRICE_SUSPECTED, não este).
        LinhaFornecedor l = linha("Sal Grosso 1kg", null, null, null, null, false);
        ItemConciliado ic = itemUnico("Sal Grosso 1kg", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        assertEquals(1, resultado.size());
        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.OK, r.status(),
                "Item sem preço não deve mais escalar para REVISAR (B.2)");
        assertTrue(r.motivos().isEmpty(), "Item sem preço não deve gerar nenhum motivo/aviso (B.2)");
    }

    @Test
    void item_sem_estoque_sem_preco_continua_ok_sem_motivos() {
        ProdutoCatalogo p = new ProdutoCatalogo(UUID.randomUUID(), "Sal Grosso 1kg");
        LinhaFornecedor l = new LinhaFornecedor(
                "Sal Grosso 1kg", "Sal Grosso 1kg", null, true, false,
                null, null, null, false, null, null);
        ItemConciliado ic = itemUnico("Sal Grosso 1kg", p.id(), ItemConciliado.STATUS_CONFIRMADO,
                BigDecimal.ONE, l);

        List<ItemConferencia> resultado = service.classificar(
                List.of(p), List.of(ic), agrupamento(Map.of(p.id(), List.of(ic)), List.of())
        , Map.of()
        );

        ItemConferencia r = resultado.get(0);
        assertEquals(StatusConferencia.OK, r.status());
        assertTrue(r.motivos().isEmpty());
    }
}
