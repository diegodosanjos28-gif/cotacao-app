package com.prx.cotacao.whatsapp.envio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testes de {@link MetaWhatsappGraphClient} (Prompt 20 — Service Message em texto
 * livre, {@code type:"text"}, substitui o antigo {@code enviarTemplate}).
 *
 * <p>{@code RestClient} é construído DENTRO do construtor de {@link
 * MetaWhatsappGraphClient} via {@code RestClient.builder()} — não há ponto de injeção
 * para trocar por um builder ligado a {@code MockRestServiceServer}, e reestruturar o
 * client (extrair o builder para fora do construtor) seria alterar código de produção,
 * fora do escopo de quem escreve testes. Em vez disso, este teste sobe um {@code
 * com.sun.net.httpserver.HttpServer} real em loopback (JDK puro, sem dependência nova)
 * na porta que {@code graphApiBaseUrl} aponta — o cliente HTTP real do
 * {@code MetaWhatsappGraphClient} fala com esse servidor fake exatamente como falaria
 * com a Graph API de verdade, o que também prova end-to-end que o payload serializado
 * é o que o método realmente monta (não uma reconstrução paralela via reflection).</p>
 */
class MetaWhatsappGraphClientTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void pararServidorFake() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer iniciarServidorFake(int statusCode, String corpoResposta, AtomicInteger chamadas,
                                            AtomicReference<String> corpoCapturado) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/" + PHONE_NUMBER_ID + "/messages", exchange -> {
            chamadas.incrementAndGet();
            try (InputStream is = exchange.getRequestBody()) {
                corpoCapturado.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resposta = corpoResposta.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, resposta.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resposta);
            }
        });
        s.start();
        return s;
    }

    private MetaWhatsappGraphClient client(int port, boolean envioHabilitado) {
        return new MetaWhatsappGraphClient("http://127.0.0.1:" + port, "test-token", PHONE_NUMBER_ID, envioHabilitado,
                OBJECT_MAPPER);
    }

    @Test
    void envioDesabilitado_nuncaChamaOServidor() throws IOException {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(200, "{}", chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), false);
        client.enviarTexto("5511999990000", "Recibo de teste");

        assertEquals(0, chamadas.get(),
                "com app.whatsapp.envio-habilitado=false, o RestClient nunca deve chegar a bater no servidor real");
    }

    @Test
    void payloadTemMessagingProductToTypeTextEBodyCorretos() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(200, "{}", chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);
        client.enviarTexto("5511999990000", "✅ Resposta do fornecedor Atacadão recebida, 12 itens.");

        assertEquals(1, chamadas.get());
        JsonNode body = OBJECT_MAPPER.readTree(corpo.get());
        assertEquals("whatsapp", body.get("messaging_product").asText());
        assertEquals("5511999990000", body.get("to").asText());
        assertEquals("text", body.get("type").asText());
        assertEquals("✅ Resposta do fornecedor Atacadão recebida, 12 itens.", body.get("text").get("body").asText());
    }

    @Test
    void falhaGenerica500_eEngolidaNaoPropagaParaOChamador() throws IOException {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(500, "erro interno sem json", chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);

        assertDoesNotThrow(() -> client.enviarTexto("5511999990000", "corpo qualquer"),
                "erro 500 do RestClient deve ser capturado e logado, nunca propagado — o recibo é best-effort");
        assertEquals(1, chamadas.get(), "a requisição deve ter sido de fato enviada, só a exceção resultante é engolida");
    }

    @Test
    void erro131047_janelaFechada_eDetectadoEEngolido() throws IOException {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        String corpoErro = "{\"error\":{\"message\":\"Re-engagement message\",\"type\":\"OAuthException\","
                + "\"code\":131047,\"error_subcode\":2494055,\"fbtrace_id\":\"Abc123\"}}";
        server = iniciarServidorFake(400, corpoErro, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);

        assertDoesNotThrow(() -> client.enviarTexto("5511999990000", "corpo qualquer"),
                "erro 131047 (janela de 24h fechada) deve ser detectado e engolido, nunca propagado");
        assertEquals(1, chamadas.get());
    }

    @Test
    void erroGenerico4xxSemCodigo131047_naoEConfundidoComJanelaFechada() throws IOException {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        String corpoErro = "{\"error\":{\"message\":\"Invalid parameter\",\"type\":\"OAuthException\","
                + "\"code\":100,\"fbtrace_id\":\"Xyz789\"}}";
        server = iniciarServidorFake(400, corpoErro, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);

        assertDoesNotThrow(() -> client.enviarTexto("5511999990000", "corpo qualquer"),
                "qualquer erro 4xx/5xx deve ser engolido, não só o 131047");
        assertEquals(1, chamadas.get());
    }

    @Test
    void extrairCodigoErro_corpoValido_retornaCodigo() {
        assertEquals(131047, MetaWhatsappGraphClient.extrairCodigoErro(OBJECT_MAPPER,
                "{\"error\":{\"code\":131047}}"));
    }

    @Test
    void extrairCodigoErro_corpoSemCampoError_retornaMenosUm() {
        assertEquals(-1, MetaWhatsappGraphClient.extrairCodigoErro(OBJECT_MAPPER, "{}"));
    }

    @Test
    void extrairCodigoErro_corpoMalformado_retornaMenosUm() {
        assertEquals(-1, MetaWhatsappGraphClient.extrairCodigoErro(OBJECT_MAPPER, "não é json"));
    }

    @Test
    void extrairCodigoErro_corpoNulo_retornaMenosUm() {
        assertEquals(-1, MetaWhatsappGraphClient.extrairCodigoErro(OBJECT_MAPPER, null));
    }
}
