package com.prx.cotacao.whatsapp.envio;

import java.util.List;

/** Envio de mensagem WhatsApp via Meta Template API (doc técnica, seção 10.7). */
public interface WhatsappMessageSender {
    void enviarTemplate(String numeroDestino, String nomeTemplateMeta, String idioma, List<String> parametrosPosicionais);
}
