package com.prx.cotacao.cotacao.respostafornecedor.dto;

import jakarta.validation.constraints.NotBlank;

public record EnviarRespostaRequest(@NotBlank String texto) {}
