package com.prx.cotacao.whatsapp.canal;

import com.prx.cotacao.whatsapp.canal.dto.ResultadoClassificacao;
import com.prx.cotacao.whatsapp.canal.enums.EventoWhatsApp;
import com.prx.cotacao.whatsapp.canal.service.ClassificadorMensagemWhatsapp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClassificadorMensagemWhatsappTest {

    private ClassificadorMensagemWhatsapp classificador;

    @BeforeEach
    void setUp() {
        classificador = new ClassificadorMensagemWhatsapp();
    }

    @Test
    void header_lista_produtos_exato_classifica_como_lista_e_extrai_corpo() {
        Optional<ResultadoClassificacao> r = classificador.classificar("LISTA_PRODUTOS\n3 un feijao");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.LISTA_PRODUTOS, r.get().tipo());
        assertEquals("3 un feijao", r.get().corpo());
    }

    @Test
    void header_resposta_fornecedor_exato_classifica_como_resposta_e_extrai_corpo() {
        Optional<ResultadoClassificacao> r = classificador.classificar(
                "RESPOSTA_FORNECEDOR\nFornecedor X\nsazon legumes 60g - R$ 4,89");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.RESPOSTA_FORNECEDOR, r.get().tipo());
        assertEquals("Fornecedor X\nsazon legumes 60g - R$ 4,89", r.get().corpo());
    }

    @Test
    void header_case_insensitive_reconhecido() {
        Optional<ResultadoClassificacao> r = classificador.classificar("lista_produtos\n3 un feijao");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.LISTA_PRODUTOS, r.get().tipo());
    }

    @Test
    void header_com_hifen_como_separador_reconhecido() {
        Optional<ResultadoClassificacao> r = classificador.classificar("RESPOSTA-FORNECEDOR\nFornecedor X\npreco 4,89");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.RESPOSTA_FORNECEDOR, r.get().tipo());
    }

    @Test
    void header_com_acento_reconhecido() {
        // Acento acidental — NFD + strip de marcas combinantes normaliza pro mesmo
        // alvo canônico (score 1.0, nem precisa da tolerância fuzzy).
        Optional<ResultadoClassificacao> r = classificador.classificar("RESPÓSTA FÓRNECEDOR\nFornecedor X\npreco 4,89");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.RESPOSTA_FORNECEDOR, r.get().tipo());
    }

    @Test
    void header_com_letra_faltando_reconhecido_por_fuzzy_lista() {
        // "LISTA PRODUTO" (sem o S final) contra "LISTA PRODUTOS" (14 chars): distância
        // 1 -> similaridade 1 - 1/14 ≈ 0.9286, acima do limiar 0.75. Confirmado calculando
        // a distância manualmente antes de escrever este teste.
        Optional<ResultadoClassificacao> r = classificador.classificar("LISTA PRODUTO\n3 un feijao");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.LISTA_PRODUTOS, r.get().tipo());
    }

    @Test
    void header_com_letra_faltando_reconhecido_por_fuzzy_resposta() {
        // "RESPOSTA FORNCEDOR" (sem o E de "FORNECEDOR") contra "RESPOSTA FORNECEDOR"
        // (19 chars): distância 1 -> similaridade 1 - 1/19 ≈ 0.9474, acima do limiar.
        Optional<ResultadoClassificacao> r = classificador.classificar("RESPOSTA FORNCEDOR\nFornecedor X\npreco 4,89");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.RESPOSTA_FORNECEDOR, r.get().tipo());
    }

    @Test
    void header_muito_diferente_nao_reconhecido() {
        Optional<ResultadoClassificacao> r = classificador.classificar("oi tudo bem, segue a lista");
        assertTrue(r.isEmpty());
    }

    @Test
    void header_abaixo_do_limiar_nao_reconhecido() {
        // "LISTA XPTO" contra os dois canônicos: 0.5714 (lista) e 0.2632 (resposta) —
        // ambos abaixo do limiar 0.75, confirmado calculando manualmente.
        Optional<ResultadoClassificacao> r = classificador.classificar("LISTA XPTO\nalgo aqui");
        assertTrue(r.isEmpty());
    }

    @Test
    void texto_nulo_retorna_vazio() {
        assertTrue(classificador.classificar(null).isEmpty());
    }

    @Test
    void texto_em_branco_retorna_vazio() {
        assertTrue(classificador.classificar("").isEmpty());
        assertTrue(classificador.classificar("   ").isEmpty());
    }

    @Test
    void header_apenas_sem_corpo_retorna_corpo_vazio() {
        Optional<ResultadoClassificacao> r = classificador.classificar("LISTA_PRODUTOS");
        assertTrue(r.isPresent());
        assertEquals(EventoWhatsApp.LISTA_PRODUTOS, r.get().tipo());
        assertTrue(r.get().corpo().isEmpty());
    }

    @Test
    void header_seguido_de_linha_em_branco_preserva_corpo_intacto() {
        // Só a 1ª linha (o marcador) é removida — nada além disso é stripado do corpo,
        // nem linha em branco.
        Optional<ResultadoClassificacao> r = classificador.classificar("LISTA_PRODUTOS\n\n3 un feijao");
        assertTrue(r.isPresent());
        assertEquals("\n3 un feijao", r.get().corpo());
    }
}
