package com.prx.cotacao.whatsapp.webhook.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Valida o header X-Hub-Signature-256 (HMAC-SHA256 do corpo bruto do webhook, com
 * app_secret do WhatsApp Business App como chave). appSecret chega via construtor, não
 * via @Value direto num método — é o que permite instanciar e testar esta classe sem
 * contexto Spring (new WhatsappAssinaturaValidator("segredo-de-teste")).
 */
@Component
public class WhatsappAssinaturaValidator {

    private static final String ALGORITMO = "HmacSHA256";
    private static final String PREFIXO_HEADER = "sha256=";

    private final String appSecret;

    public WhatsappAssinaturaValidator(@Value("${app.whatsapp.app-secret}") String appSecret) {
        this.appSecret = appSecret;
    }

    /**
     * @param corpoBruto        bytes exatos do body recebido, como String — nunca o
     *                          resultado de reserializar um DTO (a ordem/espaçamento do
     *                          JSON reserializado não é garantidamente idêntica ao
     *                          original, o que quebraria a validação silenciosamente).
     * @param headerAssinatura  valor cru do header X-Hub-Signature-256, formato
     *                          "sha256=&lt;hex&gt;".
     */
    public boolean valida(String corpoBruto, String headerAssinatura) {
        if (corpoBruto == null || headerAssinatura == null || !headerAssinatura.startsWith(PREFIXO_HEADER)) {
            return false;
        }
        String hexRecebido = headerAssinatura.substring(PREFIXO_HEADER.length());
        String hexEsperado = calcularHmacHex(corpoBruto);
        // Comparação em tempo constante — evita vazar, via timing, quantos bytes do
        // hex bateram antes da assinatura ser rejeitada.
        return MessageDigest.isEqual(
                hexEsperado.getBytes(StandardCharsets.UTF_8),
                hexRecebido.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Formato pronto para o header X-Hub-Signature-256 ("sha256=&lt;hex&gt;"). Usado
     * pela ferramenta de simulação de dev (WhatsappSimulacaoController) para assinar um
     * payload sintético com o MESMO segredo configurado, sem duplicar o cálculo de HMAC.
     */
    public String assinar(String corpoBruto) {
        return PREFIXO_HEADER + calcularHmacHex(corpoBruto);
    }

    private String calcularHmacHex(String corpoBruto) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), ALGORITMO));
            byte[] hash = mac.doFinal(corpoBruto.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Falha ao calcular HMAC-SHA256 do webhook WhatsApp", e);
        }
    }
}
