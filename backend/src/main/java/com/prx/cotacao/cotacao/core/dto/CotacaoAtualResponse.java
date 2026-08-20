package com.prx.cotacao.cotacao.core.dto;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Card "Cotação atual" da landing da Entrada de Dados (tenant-wide, ver
// CotacaoAtualService) — a cotação RASCUNHO/EM_ANDAMENTO mais recente do tenant.
public record CotacaoAtualResponse(
        UUID id,
        String titulo,
        CanalOrigem canalOrigem,
        OffsetDateTime ultimaAtividadeEm,
        OffsetDateTime criadoEm,
        long itensListaBase,
        long itensCotados,
        List<FornecedorRespostaResumo> fornecedores
) {
}
