package com.prx.cotacao.cotacao.respostafornecedor.parser;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ParserRespostaFornecedorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ParserRespostaFornecedorServiceTest {

    private ParserRespostaFornecedorService service;

    @BeforeEach
    void setUp() {
        service = new ParserRespostaFornecedorService(new MatchingProdutoService(MarcaRepositoryFixtures.comMarcasDefault()));
    }

    @Test
    void extrai_nome_fornecedor_da_primeira_linha() {
        String texto = "Distribuidora ABC\nsazon legumes 60g - R$ 2,89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals("Distribuidora ABC", r.nomeFornecedor());
    }

    @Test
    void parseia_preco_com_virgula() {
        String texto = "Fornecedor X\nsazon legumes 60g - R$ 4,89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        LinhaFornecedor l = r.linhas().get(0);
        assertFalse(l.ignorada());
        assertFalse(l.semEstoque());
        assertEquals(new BigDecimal("4.89"), l.preco());
        assertEquals("sazon legumes 60g", l.nomeProduto());
    }

    @Test
    void parseia_preco_com_ponto() {
        String texto = "Fornecedor X\nbombom nestle R$4.89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        assertEquals(new BigDecimal("4.89"), r.linhas().get(0).preco());
    }

    @Test
    void parseia_preco_sem_espaco_apos_cifrao() {
        String texto = "Fornecedor X\narroz tipo1 R$12.50";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(new BigDecimal("12.50"), r.linhas().get(0).preco());
    }

    @Test
    void detecta_sem_estoque() {
        String texto = "Fornecedor Y\nsazon legumes sem estoque";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        LinhaFornecedor l = r.linhas().get(0);
        assertTrue(l.semEstoque());
        assertNull(l.preco());
        assertFalse(l.ignorada());
    }

    @Test
    void detecta_nao_tem() {
        String texto = "Fornecedor Y\nbombom nestle não tem";
        ResultadoResposta r = service.parsear(texto);
        assertTrue(r.linhas().get(0).semEstoque());
    }

    @Test
    void detecta_nao_tem_sem_acento() {
        String texto = "Fornecedor Y\nbombom nestle nao tem";
        ResultadoResposta r = service.parsear(texto);
        assertTrue(r.linhas().get(0).semEstoque());
    }

    @Test
    void detecta_indisponivel() {
        String texto = "Fornecedor Y\narroz indisponível";
        ResultadoResposta r = service.parsear(texto);
        assertTrue(r.linhas().get(0).semEstoque());
    }

    @Test
    void ignora_linha_bom_dia() {
        String texto = "Fornecedor Z\nbom dia!\nsazon legumes 60g - R$ 2,89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(2, r.linhas().size());
        assertTrue(r.linhas().get(0).ignorada());
        assertFalse(r.linhas().get(1).ignorada());
    }

    @Test
    void ignora_linha_boa_tarde() {
        String texto = "Fornecedor Z\nboa tarde,\narroz R$ 5,00";
        ResultadoResposta r = service.parsear(texto);
        assertTrue(r.linhas().get(0).ignorada());
    }

    @Test
    void ignora_linha_atenciosamente() {
        String texto = "Fornecedor Z\nsazon - R$ 2,89\nAtenciosamente, João";
        ResultadoResposta r = service.parsear(texto);
        assertTrue(r.linhas().get(1).ignorada());
    }

    @Test
    void ignora_linha_observ() {
        String texto = "Fornecedor Z\nsazon - R$ 2,89\nobservação: entrega às 14h";
        ResultadoResposta r = service.parsear(texto);
        assertTrue(r.linhas().get(1).ignorada());
    }

    @Test
    void ignora_linha_em_branco() {
        String texto = "Fornecedor Z\n\nsazon - R$ 2,89\n\nbombom R$4.89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(2, r.linhas().size());
    }

    @Test
    void texto_nulo_retorna_resultado_vazio() {
        ResultadoResposta r = service.parsear(null);
        assertEquals("", r.nomeFornecedor());
        assertTrue(r.linhas().isEmpty());
    }

    @Test
    void multiplos_produtos_com_precos() {
        String texto = "Atacado Bom Preco\nsazon legumes 60g - R$ 2,89\nbombom nestle - R$ 4,50\narroz tipo1 5kg - R$ 18,90";
        ResultadoResposta r = service.parsear(texto);
        assertEquals("Atacado Bom Preco", r.nomeFornecedor());
        assertEquals(3, r.linhas().size());
        assertFalse(r.linhas().get(0).ignorada());
        assertFalse(r.linhas().get(1).ignorada());
        assertFalse(r.linhas().get(2).ignorada());
    }

    // --- Regressão do Fix 3 (preço sempre R$ 0,00) ---

    @Test
    void extrai_preco_com_texto_depois_cada() {
        String texto = "Fornecedor X\nsazon legumes 60g - R$ 4,89 cada";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        LinhaFornecedor l = r.linhas().get(0);
        assertFalse(l.ignorada());
        assertEquals(new BigDecimal("4.89"), l.preco());
    }

    @Test
    void extrai_preco_com_texto_depois_barra_unidade() {
        String texto = "Fornecedor X\nsazon legumes 60g - R$ 4,89/un";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        LinhaFornecedor l = r.linhas().get(0);
        assertFalse(l.ignorada());
        assertEquals(new BigDecimal("4.89"), l.preco());
    }

    @Test
    void extrai_preco_com_uma_casa_decimal() {
        String texto = "Fornecedor X\nbombom nestle - R$ 4,5";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        assertEquals(new BigDecimal("4.5"), r.linhas().get(0).preco());
    }

    @Test
    void extrai_preco_com_separador_de_milhar_br() {
        String texto = "Fornecedor X\narroz tipo1 fardo - R$1.234,56";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        assertEquals(new BigDecimal("1234.56"), r.linhas().get(0).preco());
    }

    @Test
    void texto_colado_sem_linha_de_cabecalho_nao_perde_a_primeira_linha() {
        // Canal web: usuário já escolhe o fornecedor via dropdown, então o texto
        // colado não tem uma linha de cabeçalho com o nome — a primeira linha já é
        // um item de produto/preço e não pode ser descartada como se fosse o nome
        // do fornecedor.
        String texto = "Sazon Legumes 60g - R$ 4,89\nBombom Nestle - R$ 4,50";
        ResultadoResposta r = service.parsear(texto);
        assertEquals("", r.nomeFornecedor());
        assertEquals(2, r.linhas().size());
        assertEquals(new BigDecimal("4.89"), r.linhas().get(0).preco());
        assertEquals("Sazon Legumes 60g", r.linhas().get(0).nomeProduto());
        assertEquals(new BigDecimal("4.50"), r.linhas().get(1).preco());
    }

    @Test
    void texto_com_linha_de_cabecalho_legitima_continua_sendo_descartada() {
        // Primeira linha claramente não é produto (sem R$, sem sem-estoque) — deve
        // continuar sendo tratada como nome do fornecedor, como antes do fix.
        String texto = "Distribuidora Central Ltda\nSazon Legumes 60g - R$ 4,89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals("Distribuidora Central Ltda", r.nomeFornecedor());
        assertEquals(1, r.linhas().size());
        assertEquals(new BigDecimal("4.89"), r.linhas().get(0).preco());
    }

    // --- embalagemDetectada ---

    @Test
    void preenche_embalagem_detectada_quando_linha_menciona_embalagem() {
        String texto = "Fornecedor X\nArroz Caixa com 12 unidades - R$ 45,00";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertNotNull(l.embalagemDetectada());
        assertEquals("caixa", l.embalagemDetectada().tipoEmbalagem());
        assertEquals(12, l.embalagemDetectada().qtdInterna());
    }

    @Test
    void embalagem_detectada_nula_quando_linha_nao_menciona_embalagem() {
        String texto = "Fornecedor X\nsazon legumes 60g - R$ 2,89";
        ResultadoResposta r = service.parsear(texto);
        assertNull(r.linhas().get(0).embalagemDetectada());
    }

    // --- marcaOferecida ---

    @Test
    void preenche_marca_oferecida_via_extrairMarca() {
        String texto = "Fornecedor X\nKetchup Heinz 400g - R$ 8,90";
        ResultadoResposta r = service.parsear(texto);
        assertEquals("heinz", r.linhas().get(0).marcaOferecida());
    }

    // --- precoBase="sem_preco" / precoPendente ---

    @Test
    void linha_consultar_gera_sem_preco_e_preco_pendente() {
        String texto = "Fornecedor X\nArroz Especial - consultar";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals("sem_preco", l.precoBase());
        assertTrue(l.precoPendente());
        assertNull(l.preco());
        assertEquals("Arroz Especial", l.nomeProduto());
    }

    @Test
    void linha_a_combinar_gera_sem_preco_e_preco_pendente() {
        String texto = "Fornecedor X\nFeijao Preto - a combinar";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals("sem_preco", l.precoBase());
        assertTrue(l.precoPendente());
        assertNull(l.preco());
    }

    @Test
    void linha_sob_consulta_gera_sem_preco_e_preco_pendente() {
        String texto = "Fornecedor X\nOleo de Soja - sob consulta";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals("sem_preco", l.precoBase());
        assertTrue(l.precoPendente());
    }

    @Test
    void linha_com_preco_normal_nao_marca_preco_pendente() {
        String texto = "Fornecedor X\nsazon legumes 60g - R$ 2,89";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals("unidade", l.precoBase());
        assertFalse(l.precoPendente());
    }

    // --- qtdInformada / unInformada ---

    @Test
    void extrai_quantidade_e_unidade_informadas_no_inicio_da_linha() {
        String texto = "Fornecedor X\n3 cx Sazon Legumes - R$ 4,89";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals(3, l.qtdInformada());
        assertEquals("cx", l.unInformada());
    }

    @Test
    void qtd_e_un_informada_nulas_quando_linha_nao_comeca_com_quantidade() {
        String texto = "Fornecedor X\nsazon legumes 60g - R$ 2,89";
        ResultadoResposta r = service.parsear(texto);
        LinhaFornecedor l = r.linhas().get(0);
        assertNull(l.qtdInformada());
        assertNull(l.unInformada());
    }

    // --- desmembramento de linha com múltiplos produtos ---

    @Test
    void desmembra_linha_com_multiplos_produtos_separados_por_pipe() {
        String texto = "Fornecedor X\nSazon Legumes 60g - R$ 2,89 | Bombom Nestle - R$ 4,50";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(2, r.linhas().size());
        assertEquals("Sazon Legumes 60g", r.linhas().get(0).nomeProduto());
        assertEquals(new BigDecimal("2.89"), r.linhas().get(0).preco());
        assertEquals("Bombom Nestle", r.linhas().get(1).nomeProduto());
        assertEquals(new BigDecimal("4.50"), r.linhas().get(1).preco());
    }

    @Test
    void desmembra_linha_com_multiplos_produtos_separados_por_ponto_e_virgula() {
        String texto = "Fornecedor X\nSazon Legumes 60g - R$ 2,89; Bombom Nestle - R$ 4,50";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(2, r.linhas().size());
        assertEquals("Sazon Legumes 60g", r.linhas().get(0).nomeProduto());
        assertEquals("Bombom Nestle", r.linhas().get(1).nomeProduto());
    }

    @Test
    void nao_desmembra_quando_menos_de_duas_partes_tem_preco_reconhecivel() {
        // Só uma das partes tem preço reconhecível — o "|" é tratado como decorativo,
        // a linha inteira permanece como um único item.
        String texto = "Fornecedor X\nSazon Legumes 60g | Bombom Nestle - R$ 4,50";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
    }

    // --- Preço sem "R$" literal (fix: 5 de 6 fornecedores reais não usam "R$") ---
    // Porte fiel de precoMatch de parseCotacaoFornecedor (protótipo
    // COTA&TESTA - 14.07 - V5.html): "R$" é opcional (R?\$?), não obrigatório.

    @Test
    void extrai_preco_sem_rifo_com_qtd_e_unidade_no_inicio() {
        String texto = "Fornecedor X\n3 cx vinagre heinig 1,89";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals(new BigDecimal("1.89"), l.preco());
        assertEquals("vinagre heinig", l.nomeProduto());
        assertEquals(3, l.qtdInformada());
        assertEquals("cx", l.unInformada());
    }

    @Test
    void extrai_preco_sem_rifo_com_ponto_final_antes_do_preco() {
        String texto = "Fornecedor X\n1 fardo polentina. 3.79";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        assertEquals(new BigDecimal("3.79"), r.linhas().get(0).preco());
    }

    @Test
    void extrai_preco_sem_rifo_com_pontos_duplos_como_separador() {
        String texto = "Fornecedor X\n1 cx margarina 500 g delícia..6,49";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        LinhaFornecedor l = r.linhas().get(0);
        assertEquals(new BigDecimal("6.49"), l.preco());
        assertEquals("margarina 500 g delícia", l.nomeProduto());
    }

    @Test
    void nao_reconhece_preco_quando_nao_ha_separador_decimal() {
        // "1 fardo polenta sinhá" e "1 fardo sal realta" não têm nenhum número com
        // separador decimal — não podem virar preço (nem via R$, nem sem R$).
        String texto = "Fornecedor X\n1 fardo polenta sinhá\n1 fardo sal realta";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(2, r.linhas().size());
        assertNull(r.linhas().get(0).preco());
        assertNull(r.linhas().get(1).preco());
        assertFalse(r.linhas().get(0).semEstoque());
        assertFalse(r.linhas().get(1).semEstoque());
    }

    @Test
    void desvio_intencional_do_prototipo_nao_confunde_peso_com_preco() {
        // DESVIO INTENCIONAL do protótipo: "Feijão 1,600kg - R$11,29" no protótipo
        // original casa com "1,60" (bug real, dentro de "1,600") porque o regex de
        // preço aceita a primeira ocorrência de separador decimal de 1-2 dígitos que
        // encontrar. Aqui, o lookahead negativo (?!\d) acrescentado a NUM_DECIMAL faz
        // a tentativa em "1,600" falhar e o preço correto (11,29) ser extraído.
        String texto = "Fornecedor X\nFeijão 1,600kg - R$11,29";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(1, r.linhas().size());
        assertEquals(new BigDecimal("11.29"), r.linhas().get(0).preco());
    }

    @Test
    void desmembra_linha_sem_rifo_literal_com_multiplos_produtos() {
        // desmembrarLinhaFornecedor (via PRECO_TESTE_RE) precisa reconhecer preço
        // mesmo sem "R$" para desmembrar corretamente uma linha com múltiplos itens.
        String texto = "Fornecedor X\nvinagre heinig 1,89 | polentina 3,79";
        ResultadoResposta r = service.parsear(texto);
        assertEquals(2, r.linhas().size());
        assertEquals(new BigDecimal("1.89"), r.linhas().get(0).preco());
        assertEquals(new BigDecimal("3.79"), r.linhas().get(1).preco());
    }
}
