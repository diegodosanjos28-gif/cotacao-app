package com.prx.cotacao.identidade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.identidade.auth.dto.TokenResponse;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig reserva {@code /admin/**} inteiro com {@code hasRole("ADMIN_PRX")}
 * (ver {@link com.prx.cotacao.shared.security.SecurityConfig#filterChain}) — nenhum
 * controller do pacote {@code identidade} tem {@code @PreAuthorize} próprio, então a
 * proteção inteira das rotas administrativas depende só dessa linha. Este teste cobre,
 * numa única bateria parametrizada, as 5 rotas do plano aprovado: um usuário autenticado
 * com papel OPERADOR_CLIENTE deve receber 403 — com corpo {@code ProblemDetail} via
 * {@link com.prx.cotacao.shared.security.AdminAccessDeniedHandler}, não um 403 sem corpo — em todas elas.
 *
 * Os IDs de path usados são placeholders que não existem no banco: a autorização por
 * {@code hasRole} acontece no filtro de segurança, antes do Dispatcher resolver a rota
 * pro controller/service, então a existência do tenant/usuário no path é irrelevante
 * para este teste.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminAcessoNegadoIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String EMAIL_OPERADOR = "operador-403-admin-test@prx.com";
    private static final String SENHA_OPERADOR = "SenhaOperador403Teste123!";
    private static final String ID_PLACEHOLDER = UUID.randomUUID().toString();
    private static final String OUTRO_ID_PLACEHOLDER = UUID.randomUUID().toString();

    private UUID tenantId;
    private UUID operadorId;
    private String operadorToken;

    @BeforeEach
    void seed() throws Exception {
        tenantId = UUID.randomUUID();
        operadorId = UUID.randomUUID();
        String hash = passwordEncoder.encode(SENHA_OPERADOR);

        comoAdmin(() -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant 403 Admin Test', 'TRIAL')",
                    tenantId);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, ?, ?, 'OPERADOR_CLIENTE', true)
                    """, operadorId, tenantId, EMAIL_OPERADOR, hash);
            return null;
        });

        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_OPERADOR, SENHA_OPERADOR))))
                .andExpect(status().isOk())
                .andReturn();
        operadorToken = objectMapper.readValue(login.getResponse().getContentAsString(), TokenResponse.class)
                .accessToken();
    }

    @AfterEach
    void limpar() {
        comoAdmin(() -> {
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoAdmin(Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    static Stream<Arguments> rotasAdmin() {
        return Stream.of(
                Arguments.of("GET", "/admin/tenants"),
                Arguments.of("POST", "/admin/tenants"),
                Arguments.of("GET", "/admin/tenants/" + ID_PLACEHOLDER + "/usuarios"),
                Arguments.of("POST", "/admin/tenants/" + ID_PLACEHOLDER + "/usuarios"),
                Arguments.of("POST", "/admin/tenants/" + ID_PLACEHOLDER + "/usuarios/" + OUTRO_ID_PLACEHOLDER + "/reset-senha")
        );
    }

    // ── Teste ────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1} retorna 403 para OPERADOR_CLIENTE")
    @MethodSource("rotasAdmin")
    void rotaAdmin_comPapelOperadorCliente_retorna403ComCorpoProblemDetail(String metodo, String path) throws Exception {
        MockHttpServletRequestBuilder builder = switch (metodo) {
            case "GET" -> get(path);
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
            default -> throw new IllegalArgumentException("Método HTTP não coberto neste teste: " + metodo);
        };

        mockMvc.perform(builder.header("Authorization", "Bearer " + operadorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Você não tem permissão para acessar este recurso."));
    }
}
