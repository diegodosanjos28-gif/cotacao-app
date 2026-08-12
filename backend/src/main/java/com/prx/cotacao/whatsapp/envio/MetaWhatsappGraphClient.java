package com.prx.cotacao.whatsapp.envio;

import com.prx.cotacao.whatsapp.canal.dto.TelefoneLogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Envio via Meta Cloud API (POST /{phone-number-id}/messages, {@code type=template}).
 * Quando {@code app.whatsapp.envio-habilitado=false} (default dev/test), só loga a
 * mensagem que enviaria — dispensa credenciais Meta válidas em dev/test.
 */
@Component
public class MetaWhatsappGraphClient implements WhatsappMessageSender {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsappGraphClient.class);

    private final RestClient restClient;
    private final String phoneNumberId;
    private final boolean envioHabilitado;

    public MetaWhatsappGraphClient(@Value("${app.whatsapp.graph-api-base-url}") String graphApiBaseUrl,
                                    @Value("${app.whatsapp.access-token}") String accessToken,
                                    @Value("${app.whatsapp.phone-number-id}") String phoneNumberId,
                                    @Value("${app.whatsapp.envio-habilitado}") boolean envioHabilitado) {
        this.phoneNumberId = phoneNumberId;
        this.envioHabilitado = envioHabilitado;
        this.restClient = RestClient.builder()
                .baseUrl(graphApiBaseUrl)
                .defaultHeader("Authorization", "Bearer " + accessToken)
                .build();
    }

    @Override
    public void enviarTemplate(String numeroDestino, String nomeTemplateMeta, String idioma,
                                List<String> parametrosPosicionais) {
        if (!envioHabilitado) {
            log.info("[WhatsApp envio desabilitado] destino={}, template={}",
                    TelefoneLogUtils.mascarar(numeroDestino), nomeTemplateMeta);
            return;
        }
        Map<String, Object> language = Map.of("code", idioma);
        Map<String, Object> template = parametrosPosicionais.isEmpty()
                ? Map.of("name", nomeTemplateMeta, "language", language)
                : Map.of("name", nomeTemplateMeta, "language", language, "components", List.of(
                        Map.of("type", "body", "parameters", parametrosPosicionais.stream()
                                .map(p -> Map.of("type", "text", "text", p))
                                .toList())));
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", numeroDestino,
                "type", "template",
                "template", template);
        try {
            restClient.post()
                    .uri("/{phoneNumberId}/messages", phoneNumberId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // Falha no envio do recibo não pode derrubar o processamento do webhook —
            // a mensagem já foi processada (item já criado/anexado); só o recibo falhou.
            log.warn("Falha ao enviar template WhatsApp: destino={}, template={}",
                    TelefoneLogUtils.mascarar(numeroDestino), nomeTemplateMeta, e);
        }
    }
}
