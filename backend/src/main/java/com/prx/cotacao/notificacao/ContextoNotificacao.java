package com.prx.cotacao.notificacao;

import java.util.Map;
import java.util.UUID;

/**
 * Dado de negócio necessário para montar QUALQUER notificação — sem nada específico de
 * um canal concreto (nome de template Meta, phone_number_id, idioma, etc. ficam no
 * adaptador que implementa {@link MensageriaService}).
 *
 * @param destinatario endereço genérico do destinatário; hoje sempre um número WhatsApp
 *                      E.164, já que é o único canal existente — não modela outros tipos
 *                      de endereço até existir um 2º canal real.
 */
public record ContextoNotificacao(
        UUID tenantId,
        String destinatario,
        Map<String, String> parametros
) {}
