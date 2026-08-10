package com.prx.cotacao.identidade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UsuarioTelefoneRequest(
        @NotBlank @Pattern(regexp = "\\+?[1-9]\\d{6,14}", message = "deve estar no formato internacional, com ou sem o +, ex: 5511987654321")
        String numeroWhatsapp,
        @Size(max = 100) String nomeContato
) {}
