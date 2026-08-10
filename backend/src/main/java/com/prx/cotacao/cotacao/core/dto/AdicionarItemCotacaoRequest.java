package com.prx.cotacao.cotacao.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;
import com.prx.cotacao.cotacao.core.service.CotacaoProdutoItemService;

// Adição manual de item no grid unificado (Prompt 12). Exatamente um de produtoId
// (produto já existente no catálogo) / nomeProdutoLivre (nome digitado sem match —
// resolve-ou-cria pelo mesmo pipeline do parser de lista) deve ser informado; a
// validação cruzada é feita em CotacaoProdutoItemService.adicionar, não aqui (não vale
// a pena um tipo de exceção novo pra uma única checagem cross-field).
public record AdicionarItemCotacaoRequest(
        UUID produtoId,
        @Size(max = 500) String nomeProdutoLivre,
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantidade,
        @NotBlank @Size(max = 10) String unidade,
        // Opcional — item manual não tem um "texto original" de verdade (nunca veio
        // de paste), mas o operador pode querer anotar algo livre (ex.: "combinar
        // marca com fornecedor X"). Nulo/ausente vira string vazia.
        @Size(max = 500) String textoOriginal
) {}
