package com.prx.cotacao.whatsapp.template.dto;

import com.prx.cotacao.whatsapp.template.ResultadoNotificacao;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TemplateMensagemAdminResponse(
        UUID id,
        UUID tenantId,
        ResultadoNotificacao resultado,
        String nomeTemplateMeta,
        String idioma,
        String conteudo,
        String descricaoParametros,
        boolean ativo,
        OffsetDateTime criadoEm
) {
    public static TemplateMensagemAdminResponse from(TemplateMensagem t) {
        return new TemplateMensagemAdminResponse(
                t.getId(), t.getTenantId(), t.getResultado(), t.getNomeTemplateMeta(), t.getIdioma(),
                t.getConteudo(), t.getDescricaoParametros(), t.isAtivo(), t.getCriadoEm());
    }
}
