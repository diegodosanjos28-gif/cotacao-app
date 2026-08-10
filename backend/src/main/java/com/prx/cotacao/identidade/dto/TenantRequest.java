package com.prx.cotacao.identidade.dto;

import com.prx.cotacao.identidade.enums.TenantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantRequest(
        @NotBlank String nomeFantasia,
        String razaoSocial,
        @Size(max = 18) String cnpj,
        TenantStatus status,
        String plano
) {}
