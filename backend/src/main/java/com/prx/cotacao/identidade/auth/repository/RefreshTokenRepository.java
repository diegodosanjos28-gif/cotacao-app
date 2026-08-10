package com.prx.cotacao.identidade.auth.repository;

import com.prx.cotacao.identidade.auth.dto.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.usado = true WHERE rt.usuarioId = :usuarioId")
    void revokeAllByUsuarioId(@Param("usuarioId") UUID usuarioId);
}
