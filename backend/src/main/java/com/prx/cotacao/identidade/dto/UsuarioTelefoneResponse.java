package com.prx.cotacao.identidade.dto;

import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UsuarioTelefoneResponse(
        UUID id,
        String numeroWhatsapp,
        String nomeContato,
        boolean ativo,
        OffsetDateTime criadoEm
) {
    public static UsuarioTelefoneResponse from(UsuarioTelefoneAutorizado t) {
        return new UsuarioTelefoneResponse(
                t.getId(), t.getNumeroWhatsapp(), t.getNomeContato(), t.isAtivo(), t.getCriadoEm());
    }
}
