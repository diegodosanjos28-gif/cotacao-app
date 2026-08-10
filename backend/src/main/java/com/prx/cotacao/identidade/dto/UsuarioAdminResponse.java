package com.prx.cotacao.identidade.dto;

import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Usuario;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code senhaGerada} só vem preenchido na resposta de criação/reset quando o sistema
 * gerou a senha automaticamente (admin não informou uma própria) — é a única vez que a
 * senha em texto puro é exposta, para o admin repassar ao cliente por fora do sistema.
 */
public record UsuarioAdminResponse(
        UUID id,
        UUID tenantId,
        String email,
        Papel papel,
        boolean ativo,
        OffsetDateTime criadoEm,
        String senhaGerada
) {
    public static UsuarioAdminResponse from(Usuario u, String senhaGerada) {
        return new UsuarioAdminResponse(
                u.getId(), u.getTenantId(), u.getEmail(), u.getPapel(), u.isAtivo(), u.getCriadoEm(), senhaGerada);
    }

    public static UsuarioAdminResponse from(Usuario u) {
        return from(u, null);
    }
}
