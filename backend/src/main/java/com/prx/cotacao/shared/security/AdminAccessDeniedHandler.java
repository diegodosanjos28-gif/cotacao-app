package com.prx.cotacao.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sem isso, um usuário autenticado sem ROLE_ADMIN_PRX batendo em /admin/** cai no
 * AccessDeniedHandlerImpl default do Spring Security — resposta 403 sem corpo JSON,
 * inconsistente com o formato ProblemDetail usado no resto da API
 * (GlobalExceptionHandler, RestAuthenticationEntryPoint).
 */
@Component
public class AdminAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public AdminAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Você não tem permissão para acessar este recurso.");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // OutputStream (não getWriter()) — Jackson grava UTF-8 por padrão aqui; via
        // Writer o container usa o encoding default do response (ISO-8859-1 se
        // nenhum charset foi setado), corrompendo acentos no `detail`.
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
