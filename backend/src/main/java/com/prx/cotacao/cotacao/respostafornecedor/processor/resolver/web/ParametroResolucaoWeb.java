package com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.web;

import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ParametroResolucaoFornecedor;

import java.util.UUID;

/**
 * Parâmetro do canal Web: o operador já escolheu o fornecedor num dropdown — ver
 * {@link ResolvedorFornecedorWebStrategy}.
 */
public class ParametroResolucaoWeb extends ParametroResolucaoFornecedor {

    private final UUID fornecedorIdSelecionado;

    public ParametroResolucaoWeb(UUID fornecedorIdSelecionado) {
        this.fornecedorIdSelecionado = fornecedorIdSelecionado;
    }

    public UUID fornecedorIdSelecionado() {
        return fornecedorIdSelecionado;
    }
}
