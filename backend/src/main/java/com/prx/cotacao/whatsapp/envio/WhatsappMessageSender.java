package com.prx.cotacao.whatsapp.envio;

/**
 * Envio de mensagem WhatsApp via Meta Cloud API, Service Message em texto livre
 * (doc técnica, seção 10.7, Prompt 20 — substitui o antigo envio por Message Template).
 */
public interface WhatsappMessageSender {
    void enviarTexto(String numeroDestino, String corpo);
}
