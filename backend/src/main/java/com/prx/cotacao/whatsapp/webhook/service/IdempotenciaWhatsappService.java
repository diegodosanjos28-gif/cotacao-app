package com.prx.cotacao.whatsapp.webhook.service;

import com.prx.cotacao.whatsapp.webhook.entity.MensagemProcessadaWhatsapp;
import com.prx.cotacao.whatsapp.webhook.repository.MensagemProcessadaWhatsappRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotência de mensagens do webhook por message_id (wamid) — a Meta reenvia
 * mensagens não confirmadas em ~200ms-segundos. Mesmo padrão de
 * {@code UsuarioTelefoneService.criar}: tenta inserir, trata violação da PK como "já
 * processada" em vez de erro. Tabela sem tenant_id/RLS (ver
 * MensagemProcessadaWhatsapp) — esta chamada não depende de TenantContext.
 */
@Service
public class IdempotenciaWhatsappService {

    private static final Logger log = LoggerFactory.getLogger(IdempotenciaWhatsappService.class);

    private final MensagemProcessadaWhatsappRepository repository;

    public IdempotenciaWhatsappService(MensagemProcessadaWhatsappRepository repository) {
        this.repository = repository;
    }

    /** @return true se a mensagem já tinha sido processada (duplicata); false se é a primeira vez (já registrada). */
    @Transactional
    public boolean jaProcessadaOuRegistrar(String messageId, String numeroOrigem) {
        try {
            // saveAndFlush força o INSERT a rodar agora, dentro deste try/catch, em vez
            // de ser adiado pro commit — só assim a violação da PK é capturada aqui.
            repository.saveAndFlush(new MensagemProcessadaWhatsapp(messageId, numeroOrigem));
            return false;
        } catch (DataIntegrityViolationException e) {
            log.info("Mensagem WhatsApp duplicada ignorada: messageId={}", messageId);
            return true;
        }
    }
}
