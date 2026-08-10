package com.prx.cotacao.identidade.auth.service;

import com.prx.cotacao.identidade.enums.Papel;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final String CLAIM_TENANT_ID = "tenant_id";
    private static final String CLAIM_PAPEL = "papel";
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret deve ter no mínimo " + MIN_SECRET_BYTES + " bytes. " +
                    "Gere com: openssl rand -base64 32");
        }
    }

    public String generateAccessToken(UUID usuarioId, UUID tenantId, Papel papel) {
        Date now = new Date();
        return Jwts.builder()
                .subject(usuarioId.toString())
                .claim(CLAIM_TENANT_ID, tenantId != null ? tenantId.toString() : null)
                .claim(CLAIM_PAPEL, papel.name())
                .claim("type", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpirationMs))
                .signWith(signingKey())
                .compact();
    }

    // Refresh token carrega apenas subject + type + expiração (sem tenant/papel, re-lidos do banco no uso)
    public String generateRefreshToken(UUID usuarioId, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .subject(usuarioId.toString())
                .id(jti)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshTokenExpirationMs))
                .signWith(signingKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID extractUsuarioId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public UUID extractTenantId(Claims claims) {
        String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
        return tenantId != null ? UUID.fromString(tenantId) : null;
    }

    public Papel extractPapel(Claims claims) {
        return Papel.valueOf(claims.get(CLAIM_PAPEL, String.class));
    }

    public String extractJti(Claims claims) {
        return claims.getId();
    }

    public boolean isAccessToken(Claims claims) {
        return "access".equals(claims.get("type", String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return "refresh".equals(claims.get("type", String.class));
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
