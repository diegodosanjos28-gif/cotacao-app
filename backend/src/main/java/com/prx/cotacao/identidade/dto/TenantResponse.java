package com.prx.cotacao.identidade.dto;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String nomeFantasia,
        String razaoSocial,
        String cnpj,
        TenantStatus status,
        String plano,
        OffsetDateTime criadoEm
) {
    public static TenantResponse from(Tenant t) {
        return new TenantResponse(
                t.getId(), t.getNomeFantasia(), t.getRazaoSocial(), t.getCnpj(),
                t.getStatus(), t.getPlano(), t.getCriadoEm());
    }
}
