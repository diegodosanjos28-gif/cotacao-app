package com.prx.cotacao.cotacao.respostafornecedor.dto;

import com.prx.cotacao.fornecedor.dto.FornecedorRequest;

import java.util.UUID;

/**
 * Exatamente um dos dois campos deve vir preenchido — validado em
 * CotacaoFornecedorService (não dá pra expressar XOR com Bean Validation puro).
 */
public record AdicionarFornecedorCotacaoRequest(
        UUID fornecedorId,
        FornecedorRequest novoFornecedor
) {}
