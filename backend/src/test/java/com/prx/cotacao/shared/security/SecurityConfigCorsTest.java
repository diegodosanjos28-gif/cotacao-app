package com.prx.cotacao.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.identidade.auth.dto.JwtAuthFilter;
import com.prx.cotacao.shared.tenant.TenantFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa o fail-fast de {@link SecurityConfig#allowedOrigins}: sem
 * `app.cors.allowed-origins` configurado, o contexto não deve subir — nada de
 * servir com CORS mal configurado silenciosamente (achado do security-reviewer,
 * ver comentário em SecurityConfig.java).
 *
 * Contexto mínimo (não é o app inteiro — sem Postgres, sem web server): registra
 * só SecurityConfig + stubs de suas quatro dependências de construtor
 * (JwtAuthFilter, TenantFilter, RestAuthenticationEntryPoint,
 * AdminAccessDeniedHandler) + o PropertySourcesPlaceholderConfigurer que o Spring
 * Boot real registra automaticamente e que é o componente responsável por
 * resolver (ou falhar ao resolver) o placeholder ${app.cors.allowed-origins}.
 */
class SecurityConfigCorsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JwtAuthFilter.class, () -> new JwtAuthFilter(null))
            .withBean(TenantFilter.class, () -> new TenantFilter(new ObjectMapper()))
            .withBean(RestAuthenticationEntryPoint.class, () -> new RestAuthenticationEntryPoint(new ObjectMapper()))
            .withBean(AdminAccessDeniedHandler.class, () -> new AdminAccessDeniedHandler(new ObjectMapper()))
            .withBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
            .withUserConfiguration(SecurityConfig.class);

    @Test
    void contexto_falha_ao_subir_sem_allowed_origins_configurado() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.cors.allowed-origins");
        });
    }

    @Test
    void contexto_sobe_normalmente_quando_allowed_origins_configurado() {
        contextRunner
                .withPropertyValues("app.cors.allowed-origins=http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecurityConfig.class);
                });
    }
}
