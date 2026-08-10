package com.prx.cotacao.whatsapp.webhook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Registro de idempotência por message_id (wamid) da Meta — não estende
 * TenantAuditEntity de propósito.
 *
 * Por que sem tenant_id? O tenant só é conhecido DEPOIS de resolver o número do
 * remetente. A checagem de idempotência roda ANTES disso (no webhook handler, antes de
 * identificar o usuário/tenant), então o tenant não está disponível neste ponto.
 * Mensagem duplicada de um número desconhecido também precisa ser reconhecida como
 * duplicata sem reprocessar o descarte/log — a PK (message_id) é suficiente, pois toda
 * mensagem recebida tem um message_id único da Meta.
 *
 * Ver V24__create_whatsapp_mensagem_processada.sql para detalhes e precedente em
 * V11__create_refresh_tokens.sql (mesmo padrão: acesso apenas por PK).
 */
@Entity
@Table(name = "whatsapp_mensagem_processada")
public class MensagemProcessadaWhatsapp {

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "numero_origem", nullable = false)
    private String numeroOrigem;

    @Column(name = "processado_em", nullable = false, updatable = false)
    private OffsetDateTime processadoEm = OffsetDateTime.now();

    public MensagemProcessadaWhatsapp() {}

    public MensagemProcessadaWhatsapp(String messageId, String numeroOrigem) {
        this.messageId = messageId;
        this.numeroOrigem = numeroOrigem;
    }

    public String getMessageId() { return messageId; }
    public String getNumeroOrigem() { return numeroOrigem; }
    public OffsetDateTime getProcessadoEm() { return processadoEm; }
}
