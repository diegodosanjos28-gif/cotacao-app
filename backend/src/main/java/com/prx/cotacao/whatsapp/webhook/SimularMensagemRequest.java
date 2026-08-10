package com.prx.cotacao.whatsapp.webhook;

import jakarta.validation.constraints.NotBlank;

public record SimularMensagemRequest(@NotBlank String numeroOrigem, @NotBlank String texto) {}
