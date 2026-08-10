package com.prx.cotacao.whatsapp.webhook.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.prx.cotacao.whatsapp.canal.dto.MensagemNormalizada;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Espelha o formato do Cloud API da Meta (entry[].changes[].value.messages[]) — puramente
 * estrutural, sem lógica. {@code @JsonIgnoreProperties(ignoreUnknown = true)} em todos os
 * níveis porque o payload real da Meta tem muito mais campos (contacts, metadata,
 * statuses, etc.) do que os poucos que este sistema usa.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsappWebhookPayload(String object, List<Entry> entry) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String id, List<Change> changes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(Value value, String field) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(List<MensagemMeta> messages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MensagemMeta(String from, String id, String timestamp, TextoMeta text, String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextoMeta(String body) {}

    /**
     * Extrai as mensagens de texto do payload inteiro, isolando o resto do pipeline do
     * formato verboso da Meta. Mensagens sem {@code text.body} (ex.: imagem, áudio,
     * status de entrega) são ignoradas — este canal só reconhece texto (seção 10.1 da
     * doc técnica: sem fluxo conversacional, sem mídia).
     */
    public List<MensagemNormalizada> extrairMensagens() {
        List<MensagemNormalizada> resultado = new ArrayList<>();
        if (entry == null) {
            return resultado;
        }
        for (Entry e : entry) {
            if (e.changes() == null) continue;
            for (Change c : e.changes()) {
                if (c.value() == null || c.value().messages() == null) continue;
                for (MensagemMeta m : c.value().messages()) {
                    if (m.text() == null || m.text().body() == null || m.from() == null || m.id() == null) {
                        continue;
                    }
                    Instant timestamp = parseTimestamp(m.timestamp());
                    resultado.add(new MensagemNormalizada(m.id(), m.from(), m.text().body(), timestamp));
                }
            }
        }
        return resultado;
    }

    private static Instant parseTimestamp(String epochSegundos) {
        if (epochSegundos == null) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSegundos));
        } catch (NumberFormatException e) {
            return Instant.now();
        }
    }
}
