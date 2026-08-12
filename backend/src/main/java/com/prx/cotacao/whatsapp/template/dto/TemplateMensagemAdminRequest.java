package com.prx.cotacao.whatsapp.template.dto;

import com.prx.cotacao.whatsapp.template.ResultadoNotificacao;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// `resultado` só é usado em criar() — a vaga (SUCESSO/ERRO) já existe e é imutável
// depois de criada, atualizar() ignora esse campo se vier preenchido.
public record TemplateMensagemAdminRequest(
        @NotNull ResultadoNotificacao resultado,
        @NotBlank String nomeTemplateMeta,
        @NotBlank String idioma,
        String conteudo,
        String descricaoParametros,
        Boolean ativo
) {}
