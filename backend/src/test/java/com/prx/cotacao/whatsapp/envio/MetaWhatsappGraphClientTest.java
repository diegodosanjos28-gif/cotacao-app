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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Testes de {@link MetaWhatsappGraphClient}. Não existia teste anterior desse client
 * (só o método novo {@code enviarTemplate}, não há resquício de um {@code enviar}
 * antigo no repo).
 *
 * <p>{@code RestClient} é construído DENTRO do construtor de {@link
 * MetaWhatsappGraphClient} via {@code RestClient.builder()} — não há ponto de injeção
 * para trocar por um builder ligado a {@code MockRestServiceServer}, e reestruturar o
 * client (extrair o builder para fora do construtor) seria alterar código de produção,
 * fora do escopo de quem escreve testes. Em vez disso, este teste sobe um {@code
 * com.sun.net.httpserver.HttpServer} real em loopback (JDK puro, sem dependência nova)
 * na porta {@code graphApiBaseUrl} aponta — o cliente HTTP real do
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

    private HttpServer iniciarServidorFake(int statusCode, AtomicInteger chamadas, AtomicReference<String> corpoCapturado)
            throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/" + PHONE_NUMBER_ID + "/messages", exchange -> {
            chamadas.incrementAndGet();
            try (InputStream is = exchange.getRequestBody()) {
                corpoCapturado.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resposta = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, resposta.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resposta);
            }
        });
        s.start();
        return s;
    }

    private MetaWhatsappGraphClient client(int port, boolean envioHabilitado) {
        return new MetaWhatsappGraphClient("http://127.0.0.1:" + port, "test-token", PHONE_NUMBER_ID, envioHabilitado);
    }

    @Test
    void envioDesabilitado_nuncaChamaOServidor() throws IOException {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(200, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), false);
        client.enviarTemplate("5511999990000", "tpl_teste", "pt_BR", List.of("a", "b"));

        assertEquals(0, chamadas.get(),
                "com app.whatsapp.envio-habilitado=false, o RestClient nunca deve chegar a bater no servidor real");
    }

    @Test
    void payloadTemMessagingProductToTypeTemplateENomeEIdiomaCorretos() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(200, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);
        client.enviarTemplate("5511999990000", "tpl_confirmacao", "pt_BR", List.of("a", "b"));

        assertEquals(1, chamadas.get());
        JsonNode body = OBJECT_MAPPER.readTree(corpo.get());
        assertEquals("whatsapp", body.get("messaging_product").asText());
        assertEquals("5511999990000", body.get("to").asText());
        assertEquals("template", body.get("type").asText());
        assertEquals("tpl_confirmacao", body.get("template").get("name").asText());
        assertEquals("pt_BR", body.get("template").get("language").get("code").asText());
    }

    @Test
    void parametrosPosicionaisVazios_omiteComponentsDoPayload() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(200, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);
        client.enviarTemplate("5511999990000", "tpl_sem_parametros", "pt_BR", List.of());

        JsonNode body = OBJECT_MAPPER.readTree(corpo.get());
        assertFalse(body.get("template").has("components"),
                "sem parâmetros posicionais, a chave components deve ficar totalmente ausente do payload");
    }

    @Test
    void parametrosPosicionaisPreenchidos_componentsBateComOrdemExataDosParametros() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(200, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);
        client.enviarTemplate("5511999990000", "tpl_com_parametros", "pt_BR",
                List.of("Lista de produtos", "5 itens adicionados (3 reconhecidos)"));

        JsonNode body = OBJECT_MAPPER.readTree(corpo.get());
        JsonNode components = body.get("template").get("components");
        assertEquals(1, components.size());
        assertEquals("body", components.get(0).get("type").asText());

        JsonNode parameters = components.get(0).get("parameters");
        assertEquals(2, parameters.size());
        assertEquals("text", parameters.get(0).get("type").asText());
        assertEquals("Lista de produtos", parameters.get(0).get("text").asText());
        assertEquals("text", parameters.get(1).get("type").asText());
        assertEquals("5 itens adicionados (3 reconhecidos)", parameters.get(1).get("text").asText());
    }

    @Test
    void falhaDoServidorRemoto_eEngolidaNaoPropagaParaOChamador() throws IOException {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        server = iniciarServidorFake(500, chamadas, corpo);

        MetaWhatsappGraphClient client = client(server.getAddress().getPort(), true);

        assertDoesNotThrow(() -> client.enviarTemplate("5511999990000", "tpl_falha", "pt_BR", List.of("x")),
                "erro 500 do RestClient deve ser capturado e logado, nunca propagado — o recibo é best-effort");
        assertEquals(1, chamadas.get(), "a requisição deve ter sido de fato enviada, só a exceção resultante é engolida");
    }
}
