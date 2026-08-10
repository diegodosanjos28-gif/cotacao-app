package com.prx.cotacao.cotacao.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;
import com.prx.cotacao.cotacao.core.service.CotacaoProdutoItemService;

// produtoId e nomeProdutoLivre são opcionais e mutuamente exclusivos: nenhum dos dois
// = "não alterar o match atual" (ex.: edição só de quantidade/unidade); produtoId =
// trocar pra um produto já existente no catálogo; nomeProdutoLivre = nome digitado sem
// match — resolve-ou-cria pelo mesmo pipeline do parser de lista (ver
// CotacaoProdutoItemService.editar), usado quando o item não tinha produto
// identificado (grid "Sem produto identificado") e o operador digita um nome novo em
// vez de escolher um já cadastrado. Não existe fluxo de "limpar" o produto associado.
public record EditarItemCotacaoRequest(
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantidade,
        @NotBlank @Size(max = 10) String unidade,
        UUID produtoId,
        @Size(max = 500) String nomeProdutoLivre
) {}
