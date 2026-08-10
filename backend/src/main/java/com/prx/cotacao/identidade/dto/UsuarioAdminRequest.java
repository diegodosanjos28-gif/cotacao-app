package com.prx.cotacao.identidade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsuarioAdminRequest(
        @NotBlank @Email String email,
        // Bean Validation não valida @Size em null — só se aplica quando o admin opta
        // por definir a senha manualmente; a gerada automaticamente já tem 12 chars.
        // 72 = limite prático do BCrypt (trunca silenciosamente acima disso).
        @Size(min = 8, max = 72) String senha,
        Boolean ativo
) {}
