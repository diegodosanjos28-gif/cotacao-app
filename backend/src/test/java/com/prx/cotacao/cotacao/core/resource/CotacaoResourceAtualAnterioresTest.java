package com.prx.cotacao.cotacao.core.resource;

import com.prx.cotacao.cotacao.core.dto.CotacaoAnteriorCursorPage;
import com.prx.cotacao.cotacao.core.dto.CotacaoAtualResponse;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.service.CotacaoAnterioresService;
import com.prx.cotacao.cotacao.core.service.CotacaoAtualService;
import com.prx.cotacao.cotacao.core.service.CotacaoListaService;
import com.prx.cotacao.cotacao.core.service.CotacaoProdutoItemService;
import com.prx.cotacao.cotacao.core.service.CotacaoService;
import com.prx.cotacao.cotacao.mensagem.service.MensagemService;
import com.prx.cotacao.cotacao.respostafornecedor.service.AvisoService;
import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.service.CotacaoFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.service.FornecedorRespostaService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CotacaoResource#atual()} e {@link CotacaoResource#anteriores}. Mesmo padrão
 * de {@code TemplateMensagemAdminControllerTest}: o projeto não tem convenção de
 * {@code @WebMvcTest}/MockMvc para resources de negócio fora do webhook do WhatsApp —
 * aqui o controller é instanciado direto com os serviços mockados.
 *
 * <p>Gap conhecido: a resolução do {@code @RequestParam(defaultValue = "10")} de
 * {@code size} em {@code anteriores} só é exercida pelo dispatcher do Spring MVC, não
 * por uma chamada direta ao método Java — ver ressalva no relatório do test-writer.</p>
 */
class CotacaoResourceAtualAnterioresTest {

    private CotacaoResource novoResourceComMocks(CotacaoAtualService atualService,
                                                   CotacaoAnterioresService anterioresService) {
        return new CotacaoResource(
                mock(CotacaoService.class),
                mock(CotacaoListaService.class),
                mock(FornecedorRespostaService.class),
                mock(AvisoService.class),
                mock(MensagemService.class),
                mock(CotacaoFornecedorService.class),
                mock(ConfirmacaoRespostaService.class),
                mock(CotacaoProdutoItemService.class),
                atualService,
                anterioresService);
    }

    // ── GET /cotacoes/atual ──────────────────────────────────────────────────

    @Test
    void atual_retorna_204_quando_service_nao_encontra_cotacao_em_andamento() {
        CotacaoAtualService atualService = mock(CotacaoAtualService.class);
        when(atualService.buscar()).thenReturn(Optional.empty());
        CotacaoResource resource = novoResourceComMocks(atualService, mock(CotacaoAnterioresService.class));

        ResponseEntity<CotacaoAtualResponse> resposta = resource.atual();

        assertEquals(HttpStatus.NO_CONTENT, resposta.getStatusCode());
        assertNull(resposta.getBody());
    }

    @Test
    void atual_retorna_200_com_o_corpo_do_service_quando_ha_cotacao_em_andamento() {
        CotacaoAtualResponse esperado = new CotacaoAtualResponse(
                UUID.randomUUID(), "Cotação em andamento", CanalOrigem.WEB,
                OffsetDateTime.now(), OffsetDateTime.now(), 5, 3, List.of());
        CotacaoAtualService atualService = mock(CotacaoAtualService.class);
        when(atualService.buscar()).thenReturn(Optional.of(esperado));
        CotacaoResource resource = novoResourceComMocks(atualService, mock(CotacaoAnterioresService.class));

        ResponseEntity<CotacaoAtualResponse> resposta = resource.atual();

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertSame(esperado, resposta.getBody());
    }

    // ── GET /cotacoes/anteriores ──────────────────────────────────────────────

    @Test
    void anteriores_repassa_cursor_e_size_informados_para_o_service() {
        CotacaoAnterioresService anterioresService = mock(CotacaoAnterioresService.class);
        CotacaoAnteriorCursorPage esperado = new CotacaoAnteriorCursorPage(List.of(), null, false, 0);
        String cursor = "2026-08-01T00:00:00Z_" + UUID.randomUUID();
        when(anterioresService.paginar(cursor, 25)).thenReturn(esperado);
        CotacaoResource resource = novoResourceComMocks(mock(CotacaoAtualService.class), anterioresService);

        CotacaoAnteriorCursorPage resposta = resource.anteriores(cursor, 25);

        assertSame(esperado, resposta);
        verify(anterioresService).paginar(cursor, 25);
    }

    @Test
    void anteriores_aceita_cursor_nulo_e_repassa_para_o_service_sem_traduzir_para_outro_valor() {
        CotacaoAnterioresService anterioresService = mock(CotacaoAnterioresService.class);
        CotacaoAnteriorCursorPage esperado = new CotacaoAnteriorCursorPage(List.of(), null, false, 0);
        when(anterioresService.paginar(isNull(), eq(10))).thenReturn(esperado);
        CotacaoResource resource = novoResourceComMocks(mock(CotacaoAtualService.class), anterioresService);

        CotacaoAnteriorCursorPage resposta = resource.anteriores(null, 10);

        assertSame(esperado, resposta);
        verify(anterioresService).paginar(null, 10);
    }
}
