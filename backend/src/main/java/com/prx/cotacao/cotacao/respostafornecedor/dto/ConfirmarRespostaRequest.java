package com.prx.cotacao.cotacao.respostafornecedor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * {@code texto} é reenviado (idêntico ao usado no preview) para que o servidor
 * recompute o mesmo pipeline de parser+conciliação+classificação — não há estado de
 * rascunho persistido entre o preview e a confirmação (decisão B do plano). Preço
 * final dos itens OK/ATENCAO vem dessa recomputação, nunca de dado ecoado pelo
 * cliente; só os itens REVISAR usam {@code resolucoes} para decidir o candidato ou
 * receber edição manual.
 */
public record ConfirmarRespostaRequest(
        @NotBlank String texto,
        @Valid List<ResolucaoItemRequest> resolucoes
) {}
