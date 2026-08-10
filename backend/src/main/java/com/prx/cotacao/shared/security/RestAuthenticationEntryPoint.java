package com.prx.cotacao.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sem isso, o Spring Security cai no Http403ForbiddenEntryPoint default pra qualquer
 * requisição não-autenticada (Authorization ausente, token expirado ou inválido) —
 * 403 em vez de 401. O interceptor do frontend (api.ts) só dispara o fluxo de
 * refresh em cima de 401; com 403 o access token expirado nunca era renovado e a
 * tela ficava presa (bug do refresh silencioso, 2026-07-18).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Credenciais ausentes, inválidas ou expiradas.");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
