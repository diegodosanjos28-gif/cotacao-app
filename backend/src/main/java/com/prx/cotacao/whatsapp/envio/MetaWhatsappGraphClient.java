package com.prx.cotacao.whatsapp.envio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.whatsapp.canal.dto.TelefoneLogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Envio via Meta Cloud API (POST /{phone-number-id}/messages, {@code type=text}) — Service
 * Message em texto livre, dentro da Customer Service Window de 24h (Prompt 20, substitui
 * o antigo envio por Message Template do Prompt 18/19, doc técnica seção 10.7). Quando
 * {@code app.whatsapp.envio-habilitado=false} (default dev/test), só loga a mensagem que
 * enviaria — dispensa credenciais Meta válidas em dev/test.
 */
@Component
public class MetaWhatsappGraphClient implements WhatsappMessageSender {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsappGraphClient.class);

    // Código de erro da Graph API pra "mensagem fora da janela de 24h de Customer
    // Service Window" — distinto de qualquer outra falha de envio (rede, credencial
    // inválida, etc.), tratado com um log próprio (ver enviarTexto).
    private static final int ERRO_JANELA_24H_FECHADA = 131047;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String phoneNumberId;
    private final boolean envioHabilitado;

    public MetaWhatsappGraphClient(@Value("${app.whatsapp.graph-api-base-url}") String graphApiBaseUrl,
                                    @Value("${app.whatsapp.access-token}") String accessToken,
                                    @Value("${app.whatsapp.phone-number-id}") String phoneNumberId,
                                    @Value("${app.whatsapp.envio-habilitado}") boolean envioHabilitado,
                                    ObjectMapper objectMapper) {
        this.phoneNumberId = phoneNumberId;
        this.envioHabilitado = envioHabilitado;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(graphApiBaseUrl)
                .defaultHeader("Authorization", "Bearer " + accessToken)
                .build();
    }

    @Override
    public void enviarTexto(String numeroDestino, String corpo) {
        if (!envioHabilitado) {
            log.info("[WhatsApp envio desabilitado] destino={}, corpo={}",
                    TelefoneLogUtils.mascarar(numeroDestino), corpo);
            return;
        }
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", numeroDestino,
                "type", "text",
                "text", Map.of("body", corpo));
        try {
            restClient.post()
                    .uri("/{phoneNumberId}/messages", phoneNumberId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Corpo de erro da Graph API é JSON ({"error":{"code":..., "message":...}}) —
            // distingue janela de 24h fechada (esperável, checagem defensiva em
            // WhatsappTextoLivreMensageriaService só reduz a frequência, não elimina) de
            // qualquer outra falha 4xx/5xx.
            if (extrairCodigoErro(objectMapper, e.getResponseBodyAsString()) == ERRO_JANELA_24H_FECHADA) {
                log.warn("Envio WhatsApp recusado pela Meta: janela de 24h fechada (erro 131047): destino={}",
                        TelefoneLogUtils.mascarar(numeroDestino));
            } else {
                log.warn("Falha ao enviar mensagem WhatsApp: destino={}, resposta={}",
                        TelefoneLogUtils.mascarar(numeroDestino), e.getResponseBodyAsString(), e);
            }
        } catch (RuntimeException e) {
            // Falha no envio do recibo não pode derrubar o processamento do webhook —
            // a mensagem já foi processada (item já criado/anexado); só o recibo falhou.
            log.warn("Falha ao enviar mensagem WhatsApp: destino={}", TelefoneLogUtils.mascarar(numeroDestino), e);
        }
    }

    // package-private: testável isoladamente sem precisar de um HttpServer de loopback
    // pra cada variação de corpo de erro (código presente, ausente, JSON malformado).
    static int extrairCodigoErro(ObjectMapper objectMapper, String corpoResposta) {
        try {
            JsonNode raiz = objectMapper.readTree(corpoResposta);
            return raiz.path("error").path("code").asInt(-1);
        } catch (Exception e) {
            return -1;
        }
    }
}
