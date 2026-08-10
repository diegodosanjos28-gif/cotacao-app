package com.prx.cotacao.cotacao.mensagem.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;

/**
 * {@link PrecoReferenciaService} — extraído de {@code ComparativoService} (pacote de
 * ajustes pós-call, B.3) para compartilhar o cálculo de "preço de referência" (mediana +
 * limiar de 1.5x, direcional) entre {@code ComparativoService} e
 * {@code ClassificacaoConferenciaService}. Testado aqui isoladamente, em unidade pura
 * (sem Spring/Postgres) — {@link ComparativoServiceTest} já cobre mediana par/ímpar e
 * exclusão de {@code semEstoque} no nível de integração de
 * {@code temDivergenciaComparativa}, mas nenhum teste exercitava
 * {@link PrecoReferenciaService#referenciaParaItem} diretamente (o método de fato
 * chamado por {@code FornecedorRespostaService.gerarPreview} e
 * {@code ConfirmacaoRespostaService.confirmar} para calcular a referência da
 * Conferência).
 */
class PrecoReferenciaServiceTest {

    private PrecoReferenciaService service;
    private UUID itemBaseId;
    private UUID fornecedorAlvo;

    @BeforeEach
    void setUp() {
        service = new PrecoReferenciaService();
        itemBaseId = UUID.randomUUID();
        fornecedorAlvo = UUID.randomUUID();
    }

    // precoUnitarioCalculado também setado (igual ao preço bruto por padrão) — é esse
    // campo que referenciaParaItem de fato usa (ConfirmacaoRespostaService sempre
    // grava os dois iguais quando não há embalagem_qtd_confirmada envolvida).
    private CotacaoProdutoFornecedor resposta(UUID itemBaseId, UUID fornecedorId, String preco, boolean semEstoque) {
        CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
        cpf.setCotacaoProdutoId(itemBaseId);
        cpf.setFornecedorId(fornecedorId);
        cpf.setPrecoInformado(new BigDecimal(preco));
        cpf.setPrecoUnitarioCalculado(new BigDecimal(preco));
        cpf.setSemEstoque(semEstoque);
        return cpf;
    }

    // ── calcularMediana ──────────────────────────────────────────────────────────

    @Test
    void calcular_mediana_com_quantidade_impar_de_valores_usa_o_valor_do_meio() {
        BigDecimal mediana = service.calcularMediana(
                List.of(new BigDecimal("10.00"), new BigDecimal("50.00"), new BigDecimal("12.00")));

        assertEquals(0, new BigDecimal("12.00").compareTo(mediana),
                "Mediana de [10,12,50] ordenado é o valor do meio (12), não a média");
    }

    @Test
    void calcular_mediana_com_quantidade_par_de_valores_usa_media_dos_dois_centrais() {
        BigDecimal mediana = service.calcularMediana(List.of(new BigDecimal("10.00"), new BigDecimal("20.00")));

        assertEquals(0, new BigDecimal("15.00").compareTo(mediana));
    }

    @Test
    void calcular_mediana_com_um_unico_valor_retorna_o_proprio_valor() {
        BigDecimal mediana = service.calcularMediana(List.of(new BigDecimal("42.00")));

        assertEquals(0, new BigDecimal("42.00").compareTo(mediana));
    }

    @Test
    void calcular_mediana_nao_e_afetada_pela_ordem_de_entrada() {
        BigDecimal medianaOrdenada = service.calcularMediana(
                List.of(new BigDecimal("5.00"), new BigDecimal("10.00"), new BigDecimal("50.00")));
        BigDecimal medianaDesordenada = service.calcularMediana(
                List.of(new BigDecimal("50.00"), new BigDecimal("5.00"), new BigDecimal("10.00")));

        assertEquals(0, medianaOrdenada.compareTo(medianaDesordenada));
        assertEquals(0, new BigDecimal("10.00").compareTo(medianaDesordenada));
    }

    // ── referenciaParaItem ───────────────────────────────────────────────────────

    @Test
    void referencia_para_item_exclui_o_fornecedor_alvo_e_usa_mediana_dos_demais() {
        List<CotacaoProdutoFornecedor> todas = List.of(
                resposta(itemBaseId, fornecedorAlvo, "999.00", false), // não deve contar (é o próprio alvo)
                resposta(itemBaseId, UUID.randomUUID(), "10.00", false),
                resposta(itemBaseId, UUID.randomUUID(), "20.00", false));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertNotNull(referencia);
        assertEquals(0, new BigDecimal("15.00").compareTo(referencia),
                "Referência deve ser a mediana só dos OUTROS fornecedores, ignorando o próprio preço do alvo");
    }

    @Test
    void referencia_para_item_usa_preco_unitario_calculado_nao_o_preco_informado_bruto() {
        // Regressão (achado do cliente): um fornecedor de referência que já teve sua
        // suspeita de preço de fardo/caixa resolvida ("Unid./embalagem" informado) tem
        // precoInformado preservado como o bruto (R$13,00 pela caixa) mas
        // precoUnitarioCalculado como o valor corrigido (R$13,00 ÷ 12 = R$1,0833...).
        // Usar o bruto como referência inflaria a mediana artificialmente.
        UUID f1 = UUID.randomUUID();
        CotacaoProdutoFornecedor comEmbalagemResolvida = resposta(itemBaseId, f1, "13.00", false);
        comEmbalagemResolvida.setEmbalagemQtdConfirmada(12);
        comEmbalagemResolvida.setPrecoUnitarioCalculado(new BigDecimal("1.0833"));

        BigDecimal referencia = service.referenciaParaItem(
                itemBaseId, fornecedorAlvo, List.of(comEmbalagemResolvida));

        assertNotNull(referencia);
        assertEquals(0, new BigDecimal("1.0833").compareTo(referencia),
                "Referência deve usar o preço unitário JÁ CORRIGIDO (1.0833), não o bruto da caixa (13.00)");
    }

    @Test
    void referencia_para_item_com_mediana_de_dois_outros_fornecedores_par() {
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        List<CotacaoProdutoFornecedor> todas = List.of(
                resposta(itemBaseId, f1, "10.00", false),
                resposta(itemBaseId, f2, "30.00", false));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertEquals(0, new BigDecimal("20.00").compareTo(referencia));
    }

    @Test
    void referencia_para_item_com_mediana_de_tres_outros_fornecedores_impar() {
        List<CotacaoProdutoFornecedor> todas = List.of(
                resposta(itemBaseId, UUID.randomUUID(), "10.00", false),
                resposta(itemBaseId, UUID.randomUUID(), "12.00", false),
                resposta(itemBaseId, UUID.randomUUID(), "50.00", false));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertEquals(0, new BigDecimal("12.00").compareTo(referencia));
    }

    @Test
    void referencia_para_item_exclui_fornecedor_sem_estoque_da_mediana() {
        // Sem a exclusão, a mediana de [10, 20, 999] incluiria o fornecedor sem estoque
        // e mudaria o resultado — só [10, 20] (mediana 15) devem contar.
        List<CotacaoProdutoFornecedor> todas = List.of(
                resposta(itemBaseId, UUID.randomUUID(), "10.00", false),
                resposta(itemBaseId, UUID.randomUUID(), "20.00", false),
                resposta(itemBaseId, UUID.randomUUID(), "999.00", true));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertEquals(0, new BigDecimal("15.00").compareTo(referencia),
                "Fornecedor sem estoque não deve contar como referência para os outros — preço de 999.00 descartado");
    }

    @Test
    void referencia_para_item_so_com_fornecedor_sem_estoque_disponivel_retorna_null() {
        List<CotacaoProdutoFornecedor> todas = List.of(
                resposta(itemBaseId, UUID.randomUUID(), "10.00", true));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertNull(referencia, "Única referência disponível está sem estoque — não deve contar, resultado null");
    }

    @Test
    void referencia_para_item_sem_nenhum_outro_fornecedor_retorna_null() {
        List<CotacaoProdutoFornecedor> todas = List.of(resposta(itemBaseId, fornecedorAlvo, "10.00", false));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertNull(referencia);
    }

    @Test
    void referencia_para_item_ignora_respostas_de_outros_itens_base() {
        UUID outroItemBaseId = UUID.randomUUID();
        List<CotacaoProdutoFornecedor> todas = List.of(
                resposta(outroItemBaseId, UUID.randomUUID(), "1000.00", false));

        BigDecimal referencia = service.referenciaParaItem(itemBaseId, fornecedorAlvo, todas);

        assertNull(referencia, "Resposta de um item base diferente não pode contar como referência");
    }

    // ── divergeDaReferencia ──────────────────────────────────────────────────────

    @Test
    void diverge_da_referencia_e_direcional_preco_muito_abaixo_nao_diverge() {
        // Preço bem abaixo da referência é promoção legítima, não erro de lançamento —
        // o gatilho só dispara para o lado caro (comentário da própria classe).
        assertFalse(service.divergeDaReferencia(new BigDecimal("1.00"), new BigDecimal("100.00")));
    }

    @Test
    void diverge_da_referencia_dispara_quando_preco_e_exatamente_1_5x_a_referencia() {
        assertTrue(service.divergeDaReferencia(new BigDecimal("15.00"), new BigDecimal("10.00")));
    }

    @Test
    void diverge_da_referencia_nao_dispara_logo_abaixo_de_1_5x() {
        assertFalse(service.divergeDaReferencia(new BigDecimal("14.99"), new BigDecimal("10.00")));
    }

    @Test
    void diverge_da_referencia_com_preco_nulo_retorna_falso() {
        assertFalse(service.divergeDaReferencia(null, new BigDecimal("10.00")));
    }

    @Test
    void diverge_da_referencia_com_referencia_nula_retorna_falso() {
        assertFalse(service.divergeDaReferencia(new BigDecimal("999.00"), null));
    }
}
