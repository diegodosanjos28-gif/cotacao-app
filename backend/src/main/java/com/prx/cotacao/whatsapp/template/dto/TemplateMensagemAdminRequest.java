package com.prx.cotacao.whatsapp.template.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// `acaoClienteId` só é usado em criar() — a vaga já existe (uma linha de acao_cliente)
// e é imutável depois de criada, atualizar() ignora esse campo se vier preenchido.
// `nomeTemplateMeta`/`idioma` são opcionais desde o Prompt 20 (texto livre) — campos
// legados de Message Template, sem uso funcional no envio real (ver entidade).
public record TemplateMensagemAdminRequest(
        @NotNull UUID acaoClienteId,
        String nomeTemplateMeta,
        String idioma,
        String conteudo,
        String descricaoParametros,
        Boolean ativo
) {}
