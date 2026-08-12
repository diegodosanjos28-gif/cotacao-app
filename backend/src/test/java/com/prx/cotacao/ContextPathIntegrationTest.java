package com.prx.cotacao;

import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova ponta a ponta de que {@code server.servlet.context-path: /api}
 * (application.yml) é de fato aplicado quando a aplicação sobe num Tomcat embutido
 * real — o que {@code @AutoConfigureMockMvc} NÃO consegue provar.
 *
 * MockMvc (usado em todos os outros *IntegrationTest deste projeto) nunca aplica
 * context-path: {@code request.getRequestURI()} dentro de uma requisição MockMvc
 * sempre devolve o path relativo ao servlet, com {@code getContextPath()} fixo em
 * {@code ""}, mesmo com {@code server.servlet.context-path} configurado. É por isso
 * que AuthIntegrationTest, SelecionarTenantIntegrationTest,
 * AdminTenantUsuarioFluxoIntegrationTest etc. continuam batendo em paths sem prefixo
 * (ex.: {@code post("/auth/login")}) e não precisam — nem devem — ganhar "/api" na
 * frente: mudar esses paths quebraria esses testes sem testar nada de novo, porque
 * MockMvc ignora context-path de qualquer forma.
 *
 * Esta classe usa {@code webEnvironment = RANDOM_PORT} (Tomcat embutido real,
 * socket HTTP de verdade) + {@link TestRestTemplate} batendo diretamente em
 * {@code http://localhost:<port>}, sem prefixo nenhum aplicado por baixo dos panos —
 * só assim o roteamento de context-path do Tomcat entra em jogo de verdade.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev, porta 5555), mesma
 * convenção dos demais testes de integração deste pacote.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ContextPathIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @LocalServerPort private int port;

    private static final String SENHA_PLANA = "SenhaTeste123!";
    private static final String EMAIL_ATIVO = "operador-context-path-test@prx.com";

    private UUID tenantId;
    private UUID usuarioId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        usuarioId = UUID.randomUUID();
        String hash = passwordEncoder.encode(SENHA_PLANA);

        // Mesmo padrão de AuthIntegrationTest: JDBC direto com TenantContext.setAdmin(true)
        // para não depender de RLS estar aberto no momento do seed.
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Context Path Test', 'TRIAL')",
                    tenantId);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, ?, ?, 'OPERADOR_CLIENTE', true)
                    """, usuarioId, tenantId, EMAIL_ATIVO, hash);
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM refresh_token WHERE usuario_id = ?", usuarioId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Monta uma URL absoluta apontando direto pro socket do Tomcat embutido
     * ({@code http://localhost:<port><path>}). Deliberadamente NÃO usa URIs
     * relativas: o {@link TestRestTemplate} autoconfigurado pelo
     * {@code @SpringBootTest} já injeta {@code server.servlet.context-path} na sua
     * root URI (por isso {@code restTemplate.getForEntity("/actuator/health", ...)}
     * já bateria em {@code /api/actuator/health} por baixo dos panos) — o que
     * mascararia exatamente o comportamento que este teste precisa provar
     * explicitamente path a path, prefixado ou não.
     */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void actuatorHealth_comPrefixoApi_retorna200() {
        ResponseEntity<String> resposta = restTemplate.getForEntity(url("/api/actuator/health"), String.class);

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertNotNull(resposta.getBody());
        assertTrue(resposta.getBody().contains("\"status\":\"UP\""),
                "Corpo da resposta deveria reportar status UP: " + resposta.getBody());
    }

    @Test
    void actuatorHealth_semPrefixoApi_retorna404() {
        // Prova que o context-path realmente mudou o roteamento: sem este teste, o
        // teste acima sozinho não provaria nada — um endpoint mapeado incondicionalmente
        // em /actuator/health (bug de configuração) responderia 200 em QUALQUER prefixo.
        ResponseEntity<String> resposta = restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resposta.getStatusCode());
    }

    @Test
    void authLogin_comPrefixoApi_retorna200ComAccessToken() {
        // Prova que TenantFilter + toda a cadeia de filtros do Spring Security (que
        // dependem de reconstruir o path sem o context-path, ver TenantFilter.doFilterInternal)
        // funcionam de verdade contra um Tomcat real, não só contra o MockMvc que
        // ignora context-path por completo.
        ResponseEntity<String> resposta = restTemplate.postForEntity(
                url("/api/auth/login"), new LoginRequest(EMAIL_ATIVO, SENHA_PLANA), String.class);

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertNotNull(resposta.getBody());
        assertTrue(resposta.getBody().contains("\"accessToken\""),
                "Corpo da resposta deveria conter accessToken: " + resposta.getBody());
        assertFalse(resposta.getBody().contains("\"accessToken\":\"\""),
                "accessToken não deveria vir vazio");
    }
}
