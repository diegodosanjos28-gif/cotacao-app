package com.prx.cotacao.whatsapp.envio;

/** Confirmação mínima de recebimento (doc técnica, seção 10.7) — um recibo por entrada processada. */
public interface WhatsappMessageSender {
    void enviar(String numeroDestino, String texto);
}
