package com.prx.cotacao.whatsapp.webhook;

import com.prx.cotacao.notificacao.ContextoNotificacao;
import com.prx.cotacao.notificacao.MensageriaService;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoClassificacao;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoLista;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoResposta;
import com.prx.cotacao.whatsapp.canal.dto.TelefoneAutorizado;
import com.prx.cotacao.whatsapp.canal.enums.TipoMensagemWhatsapp;
import com.prx.cotacao.whatsapp.canal.service.ClassificadorMensagemWhatsapp;
import com.prx.cotacao.whatsapp.canal.service.IdentificacaoWhatsappService;
import com.prx.cotacao.whatsapp.canal.service.WhatsappListaProdutosService;
import com.prx.cotacao.whatsapp.canal.service.WhatsappRespostaFornecedorService;
import com.prx.cotacao.whatsapp.webhook.service.IdempotenciaWhatsappService;
import com.prx.cotacao.whatsapp.webhook.service.NotificacaoParametrosFactory;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappWebhookPayload;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testes unitários de {@link WhatsappWebhookService} — Mockito puro, sem
 * {@code @SpringBootTest} e sem Postgres. {@link IdentificacaoWhatsappService} e
 * {@link IdempotenciaWhatsappService} são classes concretas (não interfaces), mas
 * nenhuma é {@code final}, então o Mockito mocka normalmente sem precisar delas serem
 * interfaces — nenhuma chamada real a {@code DataSource}/JDBC acontece.
 *
 * <p>{@link NotificacaoParametrosFactory} é usada como instância REAL (não mockada):
 * é um POJO puro sem dependências (5 métodos determinísticos, já cobertos
 * isoladamente por {@code NotificacaoParametrosFactoryTest}) — usar a instância real
 * aqui prova que os parâmetros que chegam em {@link MensageriaService} são
 * exatamente os que a fábrica de verdade produziria, sem duplicar a lógica de
 * montagem do mapa esperado dentro deste teste.</p>
 *
 * <p>{@code processar(WhatsappWebhookPayload)} despacha cada mensagem numa thread
 * virtual própria com {@code join()} síncrono (ver javadoc de
 * {@code WhatsappWebhookService}) — isso é 100% compatível com mocks Mockito puros
 * (nenhum dos mocks aqui toca Spring/JPA/DataSource), e o teste de "exceção da
 * mensageria não propaga" (
 * {@link #mensageriaServiceLancandoExcecao_naoPropagaParaForaDeProcessar()}) depende
 * justamente desse isolamento por thread: uma exceção não capturada dentro da thread
 * virtual nunca chega ao {@code join()} do chamador — ela só é reportada pelo
 * {@code UncaughtExceptionHandler} padrão da JVM (stack trace no stderr), então basta
 * chamar {@code processar(...)} de ponta a ponta e confirmar que ele retorna
 * normalmente.</p>
 */
class WhatsappWebhookServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USUARIO_ID = UUID.randomUUID();
    private static final String NUMERO_ORIGEM = "5511999990000";
    private static final String MESSAGE_ID = "wamid.teste-123";

    private IdentificacaoWhatsappService identificacaoService;
    private IdempotenciaWhatsappService idempotenciaService;
    private ClassificadorMensagemWhatsapp classificador;
    private WhatsappListaProdutosService listaService;
    private WhatsappRespostaFornecedorService respostaFornecedorService;
    private MensageriaService mensageriaService;
    private NotificacaoParametrosFactory parametrosFactory;

    private WhatsappWebhookService service;

    @BeforeEach
    void setup() {
        identificacaoService = mock(IdentificacaoWhatsappService.class);
        idempotenciaService = mock(IdempotenciaWhatsappService.class);
        classificador = mock(ClassificadorMensagemWhatsapp.class);
        listaService = mock(WhatsappListaProdutosService.class);
        respostaFornecedorService = mock(WhatsappRespostaFornecedorService.class);
        mensageriaService = mock(MensageriaService.class);
        parametrosFactory = new NotificacaoParametrosFactory();

        service = new WhatsappWebhookService(identificacaoService, idempotenciaService, classificador,
                listaService, respostaFornecedorService, mensageriaService, parametrosFactory);

        when(identificacaoService.buscarPorNumero(NUMERO_ORIGEM))
                .thenReturn(Optional.of(new TelefoneAutorizado(USUARIO_ID, TENANT_ID)));
        when(idempotenciaService.jaProcessadaOuRegistrar(eq(MESSAGE_ID), eq(NUMERO_ORIGEM))).thenReturn(false);
    }

    private WhatsappWebhookPayload payloadComUmaMensagem(String texto) {
        var textoMeta = new WhatsappWebhookPayload.TextoMeta(texto);
        var mensagemMeta = new WhatsappWebhookPayload.MensagemMeta(NUMERO_ORIGEM, MESSAGE_ID, "1700000000", textoMeta, "text");
        var value = new WhatsappWebhookPayload.Value(List.of(mensagemMeta));
        var change = new WhatsappWebhookPayload.Change(value, "messages");
        var entry = new WhatsappWebhookPayload.Entry("entry-1", List.of(change));
        return new WhatsappWebhookPayload("whatsapp_business_account", List.of(entry));
    }

    // ── LISTA_PRODUTOS processada com sucesso ──────────────────────────────────

    @Test
    void mensagemListaProdutos_processadaComSucesso_chamaEnviarMensagemSucessoComParametrosDaFabrica() {
        String corpo = "3 cx Sazon Legumes 60g";
        when(classificador.classificar(anyString()))
                .thenReturn(Optional.of(new ResultadoClassificacao(TipoMensagemWhatsapp.LISTA_PRODUTOS, corpo)));
        UUID cotacaoId = UUID.randomUUID();
        ResultadoProcessamentoLista resultado = new ResultadoProcessamentoLista(cotacaoId, 3, 2);
        when(listaService.processar(USUARIO_ID, corpo)).thenReturn(resultado);

        service.processar(payloadComUmaMensagem("LISTA_PRODUTOS\n" + corpo));

        ArgumentCaptor<ContextoNotificacao> captor = ArgumentCaptor.forClass(ContextoNotificacao.class);
        verify(mensageriaService).enviarMensagemSucesso(captor.capture());
        verify(mensageriaService, never()).enviarMensagemErro(any());

        ContextoNotificacao contexto = captor.getValue();
        assertEquals(TENANT_ID, contexto.tenantId());
        assertEquals(NUMERO_ORIGEM, contexto.destinatario());
        assertEquals(parametrosFactory.paraListaSucesso(resultado), contexto.parametros());
    }

    // ── RESPOSTA_FORNECEDOR processada com sucesso ─────────────────────────────

    @Test
    void mensagemRespostaFornecedor_processadaComSucesso_chamaEnviarMensagemSucessoComParametrosDaFabrica() {
        String corpo = "Distribuidora Silva\n3 cx Sazon Legumes 60g R$ 45,00";
        when(classificador.classificar(anyString()))
                .thenReturn(Optional.of(new ResultadoClassificacao(TipoMensagemWhatsapp.RESPOSTA_FORNECEDOR, corpo)));
        UUID cotacaoId = UUID.randomUUID();
        ResultadoProcessamentoResposta resultado = new ResultadoProcessamentoResposta(cotacaoId, "Distribuidora Silva", 1);
        when(respostaFornecedorService.processar(USUARIO_ID, corpo)).thenReturn(resultado);

        service.processar(payloadComUmaMensagem("RESPOSTA_FORNECEDOR\n" + corpo));

        ArgumentCaptor<ContextoNotificacao> captor = ArgumentCaptor.forClass(ContextoNotificacao.class);
        verify(mensageriaService).enviarMensagemSucesso(captor.capture());
        verify(mensageriaService, never()).enviarMensagemErro(any());

        ContextoNotificacao contexto = captor.getValue();
        assertEquals(TENANT_ID, contexto.tenantId());
        assertEquals(NUMERO_ORIGEM, contexto.destinatario());
        assertEquals(parametrosFactory.paraRespostaSucesso(resultado), contexto.parametros());
    }

    // ── exceção de qualquer um dos dois services de processamento → erro, não sucesso ──

    @Test
    void listaServiceLancaRuntimeException_chamaEnviarMensagemErroComParametrosDeErroDeLista() {
        String corpo = "3 cx Sazon Legumes 60g";
        when(classificador.classificar(anyString()))
                .thenReturn(Optional.of(new ResultadoClassificacao(TipoMensagemWhatsapp.LISTA_PRODUTOS, corpo)));
        when(listaService.processar(USUARIO_ID, corpo)).thenThrow(new RuntimeException("falha simulada no parser"));

        service.processar(payloadComUmaMensagem("LISTA_PRODUTOS\n" + corpo));

        ArgumentCaptor<ContextoNotificacao> captor = ArgumentCaptor.forClass(ContextoNotificacao.class);
        verify(mensageriaService).enviarMensagemErro(captor.capture());
        verify(mensageriaService, never()).enviarMensagemSucesso(any());
        assertEquals(parametrosFactory.paraListaErro(), captor.getValue().parametros());
    }

    @Test
    void respostaFornecedorServiceLancaRuntimeException_chamaEnviarMensagemErroComParametrosDeErroDeResposta() {
        String corpo = "Distribuidora Silva\n3 cx Sazon Legumes 60g R$ 45,00";
        when(classificador.classificar(anyString()))
                .thenReturn(Optional.of(new ResultadoClassificacao(TipoMensagemWhatsapp.RESPOSTA_FORNECEDOR, corpo)));
        when(respostaFornecedorService.processar(USUARIO_ID, corpo)).thenThrow(new RuntimeException("falha simulada no matching"));

        service.processar(payloadComUmaMensagem("RESPOSTA_FORNECEDOR\n" + corpo));

        ArgumentCaptor<ContextoNotificacao> captor = ArgumentCaptor.forClass(ContextoNotificacao.class);
        verify(mensageriaService).enviarMensagemErro(captor.capture());
        verify(mensageriaService, never()).enviarMensagemSucesso(any());
        assertEquals(parametrosFactory.paraRespostaErro(), captor.getValue().parametros());
    }

    // ── classificação vazia (formato não reconhecido) ──────────────────────────

    @Test
    void classificacaoVazia_chamaEnviarMensagemErroComParametrosDeFormatoDesconhecidoENaoTocaOsServicesDeProcessamento() {
        when(classificador.classificar(anyString())).thenReturn(Optional.empty());

        service.processar(payloadComUmaMensagem("oi, segue a lista"));

        ArgumentCaptor<ContextoNotificacao> captor = ArgumentCaptor.forClass(ContextoNotificacao.class);
        verify(mensageriaService).enviarMensagemErro(captor.capture());
        verify(mensageriaService, never()).enviarMensagemSucesso(any());
        assertEquals(parametrosFactory.paraFormatoDesconhecido(), captor.getValue().parametros());
        assertEquals("Desconhecido", captor.getValue().parametros().get("tipoMensagem"));

        verifyNoInteractions(listaService, respostaFornecedorService);
    }

    // ── exceção do próprio MensageriaService não propaga para fora de processar() ──

    @Test
    void mensageriaServiceLancandoExcecao_naoPropagaParaForaDeProcessar() {
        when(classificador.classificar(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("falha simulada no envio do recibo"))
                .when(mensageriaService).enviarMensagemErro(any());

        assertDoesNotThrow(() -> service.processar(payloadComUmaMensagem("oi, segue a lista")),
                "falha da mensageria roda isolada na thread virtual da mensagem — nunca deve derrubar o webhook");
    }
}
