package com.prx.cotacao.whatsapp.template.dto;

import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;

import java.time.OffsetDateTime;
import java.util.UUID;

// Não denormaliza acao/resultado aqui de propósito — o frontend já carrega
// GET /admin/acoes-cliente separadamente e faz o join por acaoClienteId, evita
// duplicar fonte da verdade.
public record TemplateMensagemAdminResponse(
        UUID id,
        UUID tenantId,
        UUID acaoClienteId,
        String nomeTemplateMeta,
        String idioma,
        String conteudo,
        String descricaoParametros,
        boolean ativo,
        OffsetDateTime criadoEm
) {
    public static TemplateMensagemAdminResponse from(TemplateMensagem t) {
        return new TemplateMensagemAdminResponse(
                t.getId(), t.getTenantId(), t.getAcaoClienteId(), t.getNomeTemplateMeta(), t.getIdioma(),
                t.getConteudo(), t.getDescricaoParametros(), t.isAtivo(), t.getCriadoEm());
    }
}
