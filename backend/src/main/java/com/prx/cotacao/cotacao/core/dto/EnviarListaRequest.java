package com.prx.cotacao.cotacao.core.dto;

import jakarta.validation.constraints.NotBlank;

public record EnviarListaRequest(@NotBlank String texto) {}
