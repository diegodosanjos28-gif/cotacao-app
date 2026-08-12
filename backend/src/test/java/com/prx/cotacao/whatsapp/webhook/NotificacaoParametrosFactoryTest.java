package com.prx.cotacao.whatsapp.webhook;

import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoLista;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoResposta;
import com.prx.cotacao.whatsapp.webhook.service.NotificacaoParametrosFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testes unitários puros (sem mocks, sem Spring) de {@link NotificacaoParametrosFactory}
 * — POJO sem dependências, então basta instanciar com {@code new} e conferir o
 * {@code Map} devolvido por cada um dos 5 métodos, chave a chave, contra o texto exato
 * usado hoje pelo fonte (nenhum "parecido" — a ideia é travar o texto que o operador
 * efetivamente vê no WhatsApp).
 */
class NotificacaoParametrosFactoryTest {

    private final NotificacaoParametrosFactory factory = new NotificacaoParametrosFactory();

    @Test
    void paraListaSucesso_montaTipoMensagemEDetalheComContagens() {
        ResultadoProcessamentoLista resultado = new ResultadoProcessamentoLista(UUID.randomUUID(), 5, 3);

        Map<String, String> parametros = factory.paraListaSucesso(resultado);

        assertEquals(2, parametros.size());
        assertEquals("Lista de produtos", parametros.get("tipoMensagem"));
        assertEquals("5 itens adicionados (3 reconhecidos)", parametros.get("detalhe"));
    }

    @Test
    void paraListaErro_montaTipoMensagemEDetalheFixoDeErro() {
        Map<String, String> parametros = factory.paraListaErro();

        assertEquals(2, parametros.size());
        assertEquals("Lista de produtos", parametros.get("tipoMensagem"));
        assertEquals("Não foi possível processar sua lista. Um operador vai verificar em breve.",
                parametros.get("detalhe"));
    }

    @Test
    void paraRespostaSucesso_montaTipoMensagemEDetalheComNomeFornecedorETotalItens() {
        ResultadoProcessamentoResposta resultado =
                new ResultadoProcessamentoResposta(UUID.randomUUID(), "Distribuidora Silva Ltda", 4);

        Map<String, String> parametros = factory.paraRespostaSucesso(resultado);

        assertEquals(2, parametros.size());
        assertEquals("Resposta de fornecedor", parametros.get("tipoMensagem"));
        assertEquals("Fornecedor Distribuidora Silva Ltda, 4 itens — aguardando conferência do operador.",
                parametros.get("detalhe"));
    }

    @Test
    void paraRespostaErro_montaTipoMensagemEDetalheFixoDeErro() {
        Map<String, String> parametros = factory.paraRespostaErro();

        assertEquals(2, parametros.size());
        assertEquals("Resposta de fornecedor", parametros.get("tipoMensagem"));
        assertEquals("Não foi possível processar sua resposta. Um operador vai verificar em breve.",
                parametros.get("detalhe"));
    }

    @Test
    void paraFormatoDesconhecido_montaTipoMensagemDesconhecidoEDetalheDeOrientacao() {
        Map<String, String> parametros = factory.paraFormatoDesconhecido();

        assertEquals(2, parametros.size());
        assertEquals("Desconhecido", parametros.get("tipoMensagem"));
        assertEquals("Comece a mensagem com LISTA_PRODUTOS ou RESPOSTA_FORNECEDOR na primeira linha.",
                parametros.get("detalhe"));
    }
}
