package com.prx.cotacao.identidade.auth.resource;

import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.identidade.auth.dto.RefreshRequest;
import com.prx.cotacao.identidade.auth.dto.SelecionarTenantRequest;
import com.prx.cotacao.identidade.auth.dto.TokenResponse;
import com.prx.cotacao.identidade.auth.service.AuthService;
import com.prx.cotacao.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/auth")
public class AuthResource {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthResource(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest) {
        return ResponseEntity.ok(authService.login(request, clientIp(servletRequest)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                  HttpServletRequest servletRequest) {
        return ResponseEntity.ok(authService.refresh(request, clientIp(servletRequest)));
    }

    // Não está sob /admin/** (é uma ação de sessão, não de gestão do painel), então
    // precisa da checagem de papel explícita aqui — SecurityConfig só cobre prefixos
    // de rota, não este endpoint específico dentro de /auth/**.
    @PreAuthorize("hasRole('ADMIN_PRX')")
    @PostMapping("/selecionar-tenant")
    public ResponseEntity<TokenResponse> selecionarTenant(@RequestBody SelecionarTenantRequest request) {
        return ResponseEntity.ok(authService.selecionarTenant(currentUser.usuarioId(), request.tenantId()));
    }

    // getRemoteAddr() não deveria retornar null em runtime real (Tomcat), mas é um
    // contrato frágil pra confiar sem guarda — sem isso, um valor nulo vira NPE dentro
    // do ConcurrentHashMap do rate limiter, derrubando /auth/login inteiro por 500 em
    // vez de simplesmente processar a requisição.
    private String clientIp(HttpServletRequest servletRequest) {
        return Objects.requireNonNullElse(servletRequest.getRemoteAddr(), "unknown");
    }
}
