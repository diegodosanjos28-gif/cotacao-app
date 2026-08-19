package com.prx.cotacao.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// CSRF global fica desabilitado (SecurityConfig) porque a API inteira autentica via
// Authorization: Bearer, imune a CSRF por natureza. Os dois endpoints abaixo são a
// única exceção: autenticam via cookie (refresh_token), que o navegador anexa
// sozinho em qualquer request cross-site. SameSite=Strict já barra a maior parte do
// risco; este header é defesa em profundidade — só um script rodando no próprio
// frontend consegue setá-lo (um <form> de outro site, o vetor clássico de CSRF, não
// consegue anexar headers customizados).
@Component
public class CsrfHeaderFilter extends OncePerRequestFilter {

    // AntPathRequestMatcher normaliza/decodifica o path do mesmo jeito que o
    // DispatcherServlet antes de comparar — comparar contra request.getRequestURI()
    // bruto (não decodificado) permitia burlar o filtro com percent-encoding (ex:
    // "/auth/%72efresh" chega ao controller como "/auth/refresh" mas não batia no
    // Set<String> de comparação literal). Achado do security-reviewer.
    private static final RequestMatcher PATHS_PROTEGIDOS = new OrRequestMatcher(
            new AntPathRequestMatcher("/auth/refresh"),
            new AntPathRequestMatcher("/auth/logout"));
    private static final String HEADER = "X-Requested-With";
    private static final String VALOR_ESPERADO = "XMLHttpRequest";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (PATHS_PROTEGIDOS.matches(request) && !VALOR_ESPERADO.equals(request.getHeader(HEADER))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Header CSRF ausente ou inválido");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
