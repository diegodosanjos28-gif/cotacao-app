package com.prx.cotacao.cotacao.respostafornecedor.dto;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.CandidatoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.entity.ItemConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Item da tabela de Conferência (seção 2.2 do plano) — um por item base da cotação com
 * pelo menos 1 candidato de resposta, mais um por item "extra" (resposta sem item base
 * correspondente, {@code itemBaseId == null}), mais um por item já confirmado numa
 * rodada anterior e não mencionado na resposta atual ({@code preservado == true}, via
 * {@code mesclarComConfirmacoesAnteriores}). Itens base sem nenhum candidato e nunca
 * confirmados antes não aparecem aqui (decisão F do plano — segue o protótipo à risca).
 */
public record ItemConferenciaResponse(
        UUID itemBaseId,
        String nomeItemBase,
        StatusConferencia status,
        List<MotivoConferencia> motivos,
        List<CandidatoResposta> candidatos,
        boolean preservado,
        BigDecimal precoAnteriorConfirmado
) {
    public static ItemConferenciaResponse from(ItemConferencia item, String nomeItemBase) {
        return new ItemConferenciaResponse(
                item.itemBaseId(), nomeItemBase, item.status(), item.motivos(), item.candidatos(),
                item.preservado(), item.precoAnteriorConfirmado());
    }
}
