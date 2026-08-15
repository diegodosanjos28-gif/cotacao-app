package com.prx.cotacao.whatsapp.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

// `acaoClienteId` só é usado em criar() — a vaga já existe (uma linha de acao_cliente)
// e é imutável depois de criada, atualizar() ignora esse campo se vier preenchido.
public record TemplateMensagemAdminRequest(
        @NotNull UUID acaoClienteId,
        @NotBlank String nomeTemplateMeta,
        @NotBlank String idioma,
        String conteudo,
        String descricaoParametros,
        List<String> parametrosOrdenados,
        Boolean ativo
) {}
