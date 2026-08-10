package com.prx.cotacao.cotacao.core.dto;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CotacaoResponse(
        UUID id,
        UUID criadoPor,
        String titulo,
        CotacaoStatus status,
        CanalOrigem canalOrigem,
        boolean listaRevisada,
        OffsetDateTime ultimaAtividadeEm,
        CenarioSelecionado cenarioSelecionado,
        OffsetDateTime finalizadaEm,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm
) {
    public static CotacaoResponse from(Cotacao c) {
        return new CotacaoResponse(
                c.getId(), c.getCriadoPor(), c.getTitulo(), c.getStatus(), c.getCanalOrigem(),
                c.isListaRevisada(), c.getUltimaAtividadeEm(), c.getCenarioSelecionado(),
                c.getFinalizadaEm(), c.getCriadoEm(), c.getAtualizadoEm());
    }
}
