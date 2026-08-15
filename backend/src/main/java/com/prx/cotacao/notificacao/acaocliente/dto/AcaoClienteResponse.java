package com.prx.cotacao.notificacao.acaocliente.dto;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;

import java.util.UUID;

public record AcaoClienteResponse(UUID id, AcaoClienteEnum acao, ResultadoAcaoCliente resultado, String descricao) {
    public static AcaoClienteResponse from(AcaoCliente c) {
        return new AcaoClienteResponse(c.getId(), c.getAcao(), c.getResultado(), c.getDescricao());
    }
}
