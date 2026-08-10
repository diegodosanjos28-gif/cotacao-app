package com.prx.cotacao.whatsapp.webhook.repository;

import com.prx.cotacao.whatsapp.webhook.entity.MensagemProcessadaWhatsapp;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Registro de idempotência por message_id (wamid) da Meta.
 *
 * AVISO DE SEGURANÇA: Esta tabela NÃO tem tenant_id e NÃO tem RLS de propósito
 * (ver V24__create_whatsapp_mensagem_processada.sql). Acesso é exclusivamente por PK
 * (message_id) — nunca adicione findByNumeroOrigem(), findAll(), ou qualquer método
 * que busque por campos que não sejam a PK sem consultar a equipe de segurança.
 *
 * Motivo: numero_origem é um número de telefone que não pode ser isolado por tenant
 * (um número não pertence a um tenant). Qualquer método de scan expõe números de
 * diferentes tenants/usuários, violando isolamento multi-tenant. Se for necessário
 * buscar/listar por numero_origem para auditoria/admin, isso deve ser implementado
 * com segurança explícita (ex: endpoint admin-only, logs, validação de contexto).
 */
public interface MensagemProcessadaWhatsappRepository extends JpaRepository<MensagemProcessadaWhatsapp, String> {
}
