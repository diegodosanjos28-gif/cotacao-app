package com.prx.cotacao.fornecedor.resource;

import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoResponse;
import com.prx.cotacao.cotacao.hall.dto.SeloConfiabilidade;
import com.prx.cotacao.cotacao.hall.service.HallFornecedoresService;
import com.prx.cotacao.fornecedor.service.FornecedorService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FornecedorResource#historico()} — mesmo padrão de
 * {@code TemplateMensagemAdminControllerTest}/{@code CotacaoResourceAtualAnterioresTest}:
 * controller instanciado direto com o service mockado, sem MockMvc.
 */
class FornecedorResourceHistoricoTest {

    @Test
    void historico_devolve_exatamente_o_que_o_service_retorna() {
        HallFornecedoresService hallFornecedoresService = mock(HallFornecedoresService.class);
        List<FornecedorHistoricoResponse> esperado = List.of(
                new FornecedorHistoricoResponse(UUID.randomUUID(), "Fornecedor X", 3, 66.6, 45.0, SeloConfiabilidade.AGIL));
        when(hallFornecedoresService.historico()).thenReturn(esperado);
        FornecedorResource resource = new FornecedorResource(mock(FornecedorService.class), hallFornecedoresService);

        List<FornecedorHistoricoResponse> resposta = resource.historico();

        assertSame(esperado, resposta);
    }
}
