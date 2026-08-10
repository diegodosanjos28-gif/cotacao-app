package com.prx.cotacao.whatsapp.canal.dto;

import java.time.Instant;

/**
 * Isola o resto do pipeline de roteamento do formato verboso do payload da Meta —
 * único ponto de contato com {@link WhatsappWebhookPayload} depois da extração.
 */
public record MensagemNormalizada(String messageId, String numeroOrigem, String texto, Instant timestamp) {}
