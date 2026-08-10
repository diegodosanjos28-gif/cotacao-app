package com.prx.cotacao.whatsapp.webhook.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappAssinaturaValidator;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappWebhookPayload;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Recepção do webhook Meta (doc técnica, seção 10.8). Rota pública (ver
 * SecurityConfig) — a única fronteira de autenticação é a assinatura HMAC do corpo.
 */
@RestController
@RequestMapping("/whatsapp/webhook")
public class WhatsappWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsappWebhookController.class);

    private final WhatsappAssinaturaValidator assinaturaValidator;
    private final WhatsappWebhookService webhookService;
    private final ObjectMapper objectMapper;

    @Value("${app.whatsapp.verify-token}")
    private String verifyToken;

    public WhatsappWebhookController(WhatsappAssinaturaValidator assinaturaValidator,
                                      WhatsappWebhookService webhookService,
                                      ObjectMapper objectMapper) {
        this.assinaturaValidator = assinaturaValidator;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    /** Handshake de verificação da Meta ao configurar o webhook no Business Manager. */
    @GetMapping
    public ResponseEntity<String> verificar(@RequestParam("hub.mode") String modo,
                                             @RequestParam("hub.verify_token") String token,
                                             @RequestParam("hub.challenge") String challenge) {
        boolean tokenValido = token != null && MessageDigest.isEqual(
                verifyToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if ("subscribe".equals(modo) && tokenValido) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).build();
    }

    /**
     * {@code @RequestBody String}, não um DTO Jackson: o HMAC precisa ser calculado
     * sobre os bytes exatos recebidos — se o Spring desserializasse direto para um
     * record, reserializar para validar a assinatura não garantiria bytes idênticos ao
     * original. Sempre 200, exceto assinatura inválida (403) — mesmo quando o
     * processamento interno descarta a mensagem (número desconhecido, formato não
     * reconhecido), para a Meta não reenviar infinitamente uma mensagem que o sistema
     * decidiu descartar de propósito.
     */
    @PostMapping
    public ResponseEntity<Void> receber(@RequestBody String corpoBruto,
                                         @RequestHeader(value = "X-Hub-Signature-256", required = false) String assinatura) {
        if (!assinaturaValidator.valida(corpoBruto, assinatura)) {
            log.warn("Webhook WhatsApp recebido com assinatura inválida ou ausente");
            return ResponseEntity.status(403).build();
        }

        WhatsappWebhookPayload payload;
        try {
            payload = objectMapper.readValue(corpoBruto, WhatsappWebhookPayload.class);
        } catch (Exception e) {
            log.warn("Payload de webhook WhatsApp malformado, descartado", e);
            return ResponseEntity.ok().build();
        }

        webhookService.processar(payload);
        return ResponseEntity.ok().build();
    }
}
