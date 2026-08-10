package com.prx.cotacao.whatsapp.webhook.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.whatsapp.webhook.SimularMensagemRequest;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappAssinaturaValidator;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappWebhookPayload;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappWebhookService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Ferramenta de simulação de dev (escopo item 8) — monta um payload no formato real da
 * Meta, assina com o MESMO validador/app-secret configurado, e faz um POST real no
 * próprio {@code /whatsapp/webhook} (não chama {@link WhatsappWebhookService}
 * diretamente) — exercita o caminho completo, inclusive validação de assinatura,
 * exatamente o que "simula um payload de webhook Meta autenticado" pede.
 *
 * <p>{@code @Profile("!prod")}: o bean/rota nem existe quando
 * {@code spring.profiles.active=prod} — não é um filtro condicional que possa vazar
 * por engano, o endpoint simplesmente não é registrado no ApplicationContext de
 * produção.</p>
 */
@RestController
@RequestMapping("/dev/whatsapp")
@Profile("!prod")
public class WhatsappSimulacaoController {

    private final WhatsappAssinaturaValidator assinaturaValidator;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WhatsappSimulacaoController(WhatsappAssinaturaValidator assinaturaValidator,
                                        ObjectMapper objectMapper,
                                        @Value("${server.port:8080}") String serverPort) {
        this.assinaturaValidator = assinaturaValidator;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl("http://localhost:" + serverPort).build();
    }

    @PostMapping("/simular")
    public ResponseEntity<String> simular(@Valid @RequestBody SimularMensagemRequest request) throws Exception {
        String messageId = "wamid.simulado." + UUID.randomUUID();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        var mensagem = new WhatsappWebhookPayload.MensagemMeta(
                request.numeroOrigem(), messageId, timestamp,
                new WhatsappWebhookPayload.TextoMeta(request.texto()), "text");
        var value = new WhatsappWebhookPayload.Value(List.of(mensagem));
        var change = new WhatsappWebhookPayload.Change(value, "messages");
        var entry = new WhatsappWebhookPayload.Entry("simulado", List.of(change));
        var payload = new WhatsappWebhookPayload("whatsapp_business_account", List.of(entry));

        String corpo = objectMapper.writeValueAsString(payload);
        String assinatura = assinaturaValidator.assinar(corpo);

        ResponseEntity<Void> resposta = restClient.post()
                .uri("/whatsapp/webhook")
                .header("X-Hub-Signature-256", assinatura)
                .body(corpo)
                .retrieve()
                .toBodilessEntity();

        return ResponseEntity.status(resposta.getStatusCode())
                .body("Simulação enviada: messageId=" + messageId + ", status=" + resposta.getStatusCode());
    }
}
