package com.prx.cotacao.cotacao.core.dto;

import jakarta.validation.constraints.NotBlank;

public record CriarCotacaoRequest(@NotBlank String titulo) {}
