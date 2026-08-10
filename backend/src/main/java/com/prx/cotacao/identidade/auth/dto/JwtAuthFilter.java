package com.prx.cotacao.identidade.auth.dto;

import com.prx.cotacao.identidade.auth.service.JwtService;
import com.prx.cotacao.shared.tenant.TenantDetails;
import com.prx.cotacao.identidade.enums.Papel;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        Claims claims = jwtService.parseToken(token);
        if (!jwtService.isAccessToken(claims)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID usuarioId = jwtService.extractUsuarioId(claims);
        UUID tenantId = jwtService.extractTenantId(claims);
        Papel papel = jwtService.extractPapel(claims);

        var auth = new UsernamePasswordAuthenticationToken(
                usuarioId.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + papel.name()))
        );
        auth.setDetails(new TenantDetails(
                tenantId != null ? tenantId.toString() : null,
                papel.name()
        ));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }
}
