package com.prx.cotacao.cotacao.respostafornecedor.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.service.ClassificacaoConferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.CandidatoResposta;

/**
 * Resultado da classificação de conferência de um item base da cotação (ou de um item
 * "extra", sem item base — {@code itemBaseId == null}) contra a resposta de um
 * fornecedor. Espelha uma entrada de {@code rv.items} em buildSupplierReview do
 * protótipo, projetada para o formato idiomático pedido no plano de porte.
 *
 * <p>{@code preservado} e {@code precoAnteriorConfirmado} são preenchidos pelo merge de
 * {@link ClassificacaoConferenciaService#mesclarComConfirmacoesAnteriores} — não pela
 * classificação em si — para reconciliar a resposta atual do fornecedor com o que já
 * estava confirmado em {@code cotacao_produto_fornecedor} de uma rodada anterior.</p>
 */
public record ItemConferencia(
        UUID itemBaseId,
        StatusConferencia status,
        List<MotivoConferencia> motivos,
        List<CandidatoResposta> candidatos,
        boolean preservado,
        BigDecimal precoAnteriorConfirmado
) {
    public ItemConferencia(UUID itemBaseId, StatusConferencia status,
            List<MotivoConferencia> motivos, List<CandidatoResposta> candidatos) {
        this(itemBaseId, status, motivos, candidatos, false, null);
    }
}
