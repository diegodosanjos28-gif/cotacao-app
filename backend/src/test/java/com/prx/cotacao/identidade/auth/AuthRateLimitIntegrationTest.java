package com.prx.cotacao.identidade.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração ponta-a-ponta do lockout primário (e-mail+IP) de
 * LoginRateLimiter, via MockMvc (mesma cadeia real de filtros usada em
 * AuthIntegrationTest).
 *
 * Usa @TestPropertySource com thresholds baixos para não depender de dezenas de
 * tentativas — isso força o Spring a criar um ApplicationContext SEPARADO do usado por
 * AuthIntegrationTest (propriedades diferentes = chave de cache de contexto diferente),
 * então o bean singleton LoginRateLimiter aqui é uma instância própria, sem interferir
 * nos testes de AuthIntegrationTest nem ser afetado por eles.
 *
 * ip-max-attempts (camada de flood por IP) e email-max-failures (camada secundária,
 * só por e-mail) são propositalmente altos aqui para isolar o comportamento sob teste
 * (lockout primário e-mail+IP) das outras duas camadas — sem isso, várias tentativas
 * seguidas do MockMvc (que sempre usa o mesmo IP de loopback) poderiam disparar o
 * RateLimitException errado antes mesmo de chegar no lockout que este teste cobre.
 *
 * Só um teste nesta classe: o bean LoginRateLimiter é singleton e mantém estado entre
 * métodos de teste dentro do mesmo contexto Spring (não há @DirtiesContext aqui), então
 * múltiplos testes usando o mesmo e-mail contaminariam a contagem de falhas uns dos
 * outros. Mais simples manter só o cenário pedido nesta classe do que orquestrar reset
 * de estado entre testes.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev dos demais testes de
 * integração deste pacote).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.rate-limit.auth.ip-max-attempts=1000",
        "app.rate-limit.auth.ip-window-seconds=60",
        "app.rate-limit.login.email-ip-max-failures=2",
        "app.rate-limit.login.email-ip-window-seconds=900",
        "app.rate-limit.login.email-max-failures=1000",
        "app.rate-limit.login.email-window-seconds=900"
})
class AuthRateLimitIntegrationTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String SENHA_PLANA = "SenhaTeste123!";
    private static final String EMAIL_ATIVO = "operador-ratelimit-test@prx.com";

    private UUID tenantId;
    private UUID usuarioAtivoId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        usuarioAtivoId = UUID.randomUUID();
        String hash = passwordEncoder.encode(SENHA_PLANA);

        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Rate Limit Test', 'TRIAL')",
                    tenantId);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, ?, ?, 'OPERADOR_CLIENTE', true)
                    """, usuarioAtivoId, tenantId, EMAIL_ATIVO, hash);
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM refresh_token WHERE usuario_id = ?", usuarioAtivoId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    @Test
    void apos2TentativasComSenhaErrada_proximaTentativaComSenhaCorretaRetorna429() throws Exception {
        // email-ip-max-failures=2: as 2 primeiras tentativas com senha errada só falham
        // com 401 normal (ainda não atingiram o limite de lockout).
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_ATIVO, "senha-errada"))))
                    .andExpect(status().isUnauthorized());
        }

        // 3ª tentativa, agora com a senha CORRETA, deve ser bloqueada com 429 — prova
        // que reserveLoginAttempt() em AuthService.login() roda ANTES do lookup de
        // usuário/comparação de senha, não depois.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_ATIVO, SENHA_PLANA))))
                .andExpect(status().isTooManyRequests());
    }
}
