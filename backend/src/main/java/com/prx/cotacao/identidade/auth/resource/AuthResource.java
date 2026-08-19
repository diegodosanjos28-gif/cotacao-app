package com.prx.cotacao.identidade.auth.resource;

import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.identidade.auth.dto.SelecionarTenantRequest;
import com.prx.cotacao.identidade.auth.dto.TokenResponse;
import com.prx.cotacao.identidade.auth.service.AuthService;
import com.prx.cotacao.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.Objects;

@RestController
@RequestMapping("/auth")
public class AuthResource {

    private static final String COOKIE_NAME = "refresh_token";
    // Context-path é /api (application.yml) — a rota real servida é /api/auth/**, e é
    // esse o path que o navegador precisa casar pra reenviar o cookie. Path=/auth (sem
    // o prefixo) nunca bateria numa requisição de verdade pra /api/auth/refresh.
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final CurrentUser currentUser;

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @Value("${app.cookie.secure}")
    private boolean cookieSecure;

    public AuthResource(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse servletResponse) {
        TokenResponse tokens = authService.login(request, clientIp(servletRequest));
        setRefreshCookie(servletResponse, tokens.refreshToken());
        return ResponseEntity.ok(tokens);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest servletRequest,
                                                  HttpServletResponse servletResponse) {
        TokenResponse tokens = authService.refresh(readRefreshCookie(servletRequest), clientIp(servletRequest));
        setRefreshCookie(servletResponse, tokens.refreshToken());
        return ResponseEntity.ok(tokens);
    }

    // Não está sob /admin/** (é uma ação de sessão, não de gestão do painel), então
    // precisa da checagem de papel explícita aqui — SecurityConfig só cobre prefixos
    // de rota, não este endpoint específico dentro de /auth/**.
    @PreAuthorize("hasRole('ADMIN_PRX')")
    @PostMapping("/selecionar-tenant")
    public ResponseEntity<TokenResponse> selecionarTenant(@RequestBody SelecionarTenantRequest request,
                                                            HttpServletResponse servletResponse) {
        TokenResponse tokens = authService.selecionarTenant(currentUser.usuarioId(), request.tenantId());
        setRefreshCookie(servletResponse, tokens.refreshToken());
        return ResponseEntity.ok(tokens);
    }

    // Sem @PreAuthorize/Authorization — precisa funcionar mesmo com o access token já
    // expirado (é literalmente o caso mais comum de logout). authService.logout é
    // best-effort/idempotente: cookie ausente ou inválido não vira erro.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        authService.logout(readRefreshCookie(servletRequest));
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString());
        return ResponseEntity.noContent().build();
    }

    private String readRefreshCookie(HttpServletRequest servletRequest) {
        var cookie = WebUtils.getCookie(servletRequest, COOKIE_NAME);
        return cookie != null ? cookie.getValue() : null;
    }

    private void setRefreshCookie(HttpServletResponse servletResponse, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ofMillis(refreshTokenExpirationMs))
                .build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie clearedRefreshCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    // getRemoteAddr() não deveria retornar null em runtime real (Tomcat), mas é um
    // contrato frágil pra confiar sem guarda — sem isso, um valor nulo vira NPE dentro
    // do ConcurrentHashMap do rate limiter, derrubando /auth/login inteiro por 500 em
    // vez de simplesmente processar a requisição.
    private String clientIp(HttpServletRequest servletRequest) {
        return Objects.requireNonNullElse(servletRequest.getRemoteAddr(), "unknown");
    }
}
