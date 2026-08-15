package com.prx.cotacao.notificacao;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Dado de negócio necessário para montar QUALQUER notificação — sem nada específico de
 * um canal concreto (nome de template Meta, phone_number_id, idioma, etc. ficam no
 * adaptador que implementa {@link MensageriaService}).
 *
 * @param destinatario endereço genérico do destinatário; hoje sempre um número WhatsApp
 *                      E.164, já que é o único canal existente — não modela outros tipos
 *                      de endereço até existir um 2º canal real.
 * @param acao ação de negócio que originou esta notificação — {@link AcaoClienteEnum} é
 *              genérico/canal-agnóstico (mora no mesmo pacote {@code notificacao}, ao
 *              contrário do antigo {@code EventoWhatsApp}, específico do canal
 *              WhatsApp), então não há mais motivo pra desacoplar via {@code String}
 *              solto. Nunca null: {@link AcaoClienteEnum#NAO_IDENTIFICADO} é o valor
 *              explícito pra "nenhum cenário classificado" (ex. formato de mensagem
 *              desconhecido), não mais representado por ausência de valor.
 */
public record ContextoNotificacao(
        UUID tenantId,
        String destinatario,
        AcaoClienteEnum acao,
        Map<String, String> parametros
) {
    public ContextoNotificacao {
        Objects.requireNonNull(acao, "acao não pode ser null");
    }
}
