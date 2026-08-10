package com.prx.cotacao.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressão do bug de 2026-07-18: sem um AuthenticationEntryPoint custom, o Spring
 * Security caía no Http403ForbiddenEntryPoint default pra qualquer requisição
 * não-autenticada (incl. access token expirado) — 403 em vez de 401. O interceptor
 * do frontend (api.ts) só dispara o fluxo de refresh em cima de 401, então o token
 * expirado nunca era renovado. Ver RestAuthenticationEntryPoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RestAuthenticationEntryPointTest {

    @Autowired private MockMvc mockMvc;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void endpointProtegido_semAuthorizationHeader_retorna401() throws Exception {
        mockMvc.perform(get("/cotacoes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void endpointProtegido_comTokenExpirado_retorna401() throws Exception {
        String tokenExpirado = Jwts.builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .claim("papel", "OPERADOR_CLIENTE")
                .claim("type", "access")
                .issuedAt(new Date(System.currentTimeMillis() - 20 * 60 * 1000))
                .expiration(new Date(System.currentTimeMillis() - 5 * 60 * 1000))
                .signWith(signingKey())
                .compact();

        mockMvc.perform(get("/cotacoes").header("Authorization", "Bearer " + tokenExpirado))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void endpointProtegido_comTokenMalformado_retorna401() throws Exception {
        mockMvc.perform(get("/cotacoes").header("Authorization", "Bearer token-invalido-completamente"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void endpointAdmin_comTokenValidoMasSemRoleAdmin_continuaRetornando403() throws Exception {
        // Autenticado (token válido), mas sem a role exigida — isso é AUTORIZAÇÃO, não
        // autenticação, e deve continuar 403 (AccessDeniedHandler default). A fix do
        // AuthenticationEntryPoint não pode confundir os dois casos.
        String tokenOperador = Jwts.builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .claim("tenant_id", "22222222-2222-2222-2222-222222222222")
                .claim("papel", "OPERADOR_CLIENTE")
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 15 * 60 * 1000))
                .signWith(signingKey())
                .compact();

        mockMvc.perform(get("/admin/qualquer-coisa").header("Authorization", "Bearer " + tokenOperador))
                .andExpect(status().isForbidden());
    }
}
