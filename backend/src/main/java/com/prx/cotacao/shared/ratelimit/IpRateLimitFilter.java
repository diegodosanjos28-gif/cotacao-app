package com.prx.cotacao.shared.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

// Gate global de flood/DoS por IP, aplicado a TODA requisição — registrado fora da
// cadeia do Spring Security (ver RateLimitFilterConfig, HIGHEST_PRECEDENCE), então uma
// rajada é rejeitada antes de gastar CPU em CORS/JWT/TenantFilter/JPA. Independente do
// LoginRateLimiter, que protege só /auth/login e /auth/refresh com limites bem mais
// apertados e lockout por conta — este aqui é só um teto alto e genérico.
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final GlobalIpRateLimiter limiter;
    private final ObjectMapper objectMapper;
    private final CorsConfigurationSource corsConfigurationSource;

    public IpRateLimitFilter(GlobalIpRateLimiter limiter, ObjectMapper objectMapper,
                              CorsConfigurationSource corsConfigurationSource) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Preflight não executa lógica de negócio — não faz sentido gastar token do
        // balde com ele (dobraria o custo efetivo de toda chamada cross-origin com
        // header custom, ex: Authorization, que sempre dispara preflight).
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String ip = Objects.requireNonNullElse(request.getRemoteAddr(), "unknown");
        if (limiter.tryConsume(ip)) {
            chain.doFilter(request, response);
            return;
        }

        // Sem os headers de CORS aqui, o browser descarta a resposta como erro de CORS
        // antes do JS enxergar o 429 — precisa poder ler o corpo pra mostrar mensagem útil.
        applyCorsHeadersIfAllowed(request, response);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições. Tente novamente em instantes.");
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private void applyCorsHeadersIfAllowed(HttpServletRequest request, HttpServletResponse response) {
        CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(request);
        String origin = request.getHeader("Origin");
        if (config == null || origin == null || config.getAllowedOrigins() == null) return;
        if (config.getAllowedOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            if (Boolean.TRUE.equals(config.getAllowCredentials())) {
                response.setHeader("Access-Control-Allow-Credentials", "true");
            }
        }
    }
}
