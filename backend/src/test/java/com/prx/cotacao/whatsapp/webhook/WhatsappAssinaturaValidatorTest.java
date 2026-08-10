package com.prx.cotacao.whatsapp.webhook;

import com.prx.cotacao.whatsapp.webhook.service.WhatsappAssinaturaValidator;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unitário puro — sem contexto Spring, sem credenciais reais da Meta. O HMAC esperado é
 * calculado manualmente aqui com o mesmo segredo passado ao validador.
 */
class WhatsappAssinaturaValidatorTest {

    private static final String SEGREDO = "segredo-de-teste";

    private final WhatsappAssinaturaValidator validator = new WhatsappAssinaturaValidator(SEGREDO);

    @Test
    void assinaturaCorretaEValidada() {
        String corpo = "{\"entry\":[{\"changes\":[]}]}";
        String header = "sha256=" + hmacHex(SEGREDO, corpo);

        assertTrue(validator.valida(corpo, header));
    }

    @Test
    void assinaturaComSegredoErradoERejeitada() {
        String corpo = "{\"entry\":[{\"changes\":[]}]}";
        String header = "sha256=" + hmacHex("segredo-errado", corpo);

        assertFalse(validator.valida(corpo, header));
    }

    @Test
    void corpoAlteradoAposAssinarERejeitado() {
        String corpoOriginal = "{\"a\":1}";
        String header = "sha256=" + hmacHex(SEGREDO, corpoOriginal);

        assertFalse(validator.valida("{\"a\":2}", header));
    }

    @Test
    void headerSemPrefixoShaESujeitoARejeicao() {
        String corpo = "{\"a\":1}";
        assertFalse(validator.valida(corpo, hmacHex(SEGREDO, corpo)));
    }

    @Test
    void headerNuloERejeitado() {
        assertFalse(validator.valida("{\"a\":1}", null));
    }

    @Test
    void corpoNuloERejeitado() {
        assertFalse(validator.valida(null, "sha256=abc"));
    }

    private static String hmacHex(String segredo, String corpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
