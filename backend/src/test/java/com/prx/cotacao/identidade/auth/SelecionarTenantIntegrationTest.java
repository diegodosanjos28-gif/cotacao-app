package com.prx.cotacao.identidade.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.fornecedor.dto.FornecedorRequest;
import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.identidade.auth.dto.SelecionarTenantRequest;
import com.prx.cotacao.identidade.auth.dto.TokenResponse;
import com.prx.cotacao.identidade.auth.service.JwtService;
import com.prx.cotacao.shared.tenant.TenantContext;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /auth/selecionar-tenant — fluxo de "navegação" do ADMIN_PRX por um tenant
 * escolhido. Exercita a cadeia real de filtros via MockMvc (mesmo padrão de
 * {@link com.prx.cotacao.identidade.AdminTenantUsuarioFluxoIntegrationTest} e
 * {@link com.prx.cotacao.identidade.auth.AuthIntegrationTest}): a lógica que
 * realmente importa aqui vive no {@code TenantFilter} (bypass de
 * {@code /admin/**} e {@code /auth/selecionar-tenant}, RLS real de verdade em rota
 * de negócio quando há tenant escolhido, 403 quando não há) e em
 * {@code AuthService.refresh} (propagação do tenant impersonado via
 * {@code refresh_token.tenant_id_impersonado}) — nenhuma das duas é pega chamando
 * {@code AuthService} diretamente via @Autowired, só via HTTP real.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SelecionarTenantIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private static final String EMAIL_ADMIN = "admin-selecionar-tenant-test@prx.com";
    private static final String SENHA_ADMIN = "SenhaAdminSelecionarTenant123!";

    private UUID adminId;
    private UUID tenantAId;
    private UUID tenantBId;
    private UUID fornecedorBId;

    @BeforeEach
    void seed() {
        adminId = UUID.randomUUID();
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        fornecedorBId = UUID.randomUUID();
        String hash = passwordEncoder.encode(SENHA_ADMIN);

        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, NULL, ?, ?, 'ADMIN_PRX', true)
                    """, adminId, EMAIL_ADMIN, hash);
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant A - Selecionar Tenant Test', 'TRIAL')",
                    tenantAId);
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant B - Selecionar Tenant Test', 'TRIAL')",
                    tenantBId);
            // Plantado diretamente no tenant B — se o isolamento da impersonação
            // falhar, ele vaza na listagem/edição feita "como" tenant A abaixo.
            jdbc.update("INSERT INTO fornecedor (id, tenant_id, nome) VALUES (?, ?, 'Fornecedor Oculto Tenant B')",
                    fornecedorBId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM refresh_token WHERE usuario_id = ?", adminId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM usuario WHERE id = ?", adminId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoAdmin(java.util.function.Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private String loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_ADMIN, SENHA_ADMIN))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    private MvcResult selecionarTenantResult(String tokenDoAdmin, UUID tenantId) throws Exception {
        return mockMvc.perform(post("/auth/selecionar-tenant")
                        .header("Authorization", "Bearer " + tokenDoAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SelecionarTenantRequest(tenantId))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private TokenResponse selecionarTenant(String tokenDoAdmin, UUID tenantId) throws Exception {
        MvcResult result = selecionarTenantResult(tokenDoAdmin, tenantId);
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    private String fornecedorRequestJson(String nome) throws Exception {
        return objectMapper.writeValueAsString(new FornecedorRequest(nome, null, null, null, null, null));
    }

    // refreshToken nunca vem mais no corpo (TokenResponse.refreshToken tem @JsonIgnore)
    // — só no Set-Cookie httpOnly da resposta.
    private String refreshCookieValue(MvcResult result) {
        Cookie refreshCookie = result.getResponse().getCookie("refresh_token");
        assertNotNull(refreshCookie, "esperava Set-Cookie refresh_token na resposta");
        return refreshCookie.getValue();
    }

    // ── Testes: isolamento de impersonação ──────────────────────────────────

    @Test
    void adminImpersonandoTenantA_naoVeFornecedorDoTenantBNaListagem() throws Exception {
        String adminToken = loginAdmin();
        TokenResponse tokensTenantA = selecionarTenant(adminToken, tenantAId);

        mockMvc.perform(get("/fornecedores").header("Authorization", "Bearer " + tokensTenantA.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adminImpersonandoTenantA_naoConsegueAtualizarFornecedorDoTenantBPeloId() throws Exception {
        String adminToken = loginAdmin();
        TokenResponse tokensTenantA = selecionarTenant(adminToken, tenantAId);

        mockMvc.perform(put("/fornecedores/" + fornecedorBId)
                        .header("Authorization", "Bearer " + tokensTenantA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fornecedorRequestJson("Tentativa De Escrita Cross Tenant")))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminImpersonandoTenantA_escritaNasceDeVerdadeNoTenantEscolhido() throws Exception {
        String adminToken = loginAdmin();
        TokenResponse tokensTenantA = selecionarTenant(adminToken, tenantAId);

        mockMvc.perform(post("/fornecedores")
                        .header("Authorization", "Bearer " + tokensTenantA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fornecedorRequestJson("Fornecedor Criado Via Admin Impersonado")))
                .andExpect(status().isCreated());

        // Confirma no banco que a linha nasceu com tenant_id = tenantA — RLS de
        // verdade, não confiança de frontend. Precisa de TenantContext.setAdmin(true)
        // aqui: fora da requisição MockMvc, a sessão JDBC do teste não tem is_admin
        // nem tenant setado, então a policy de RLS filtraria a SELECT para 0 linhas
        // mesmo com o dado realmente lá.
        Integer countNoTenantA = comoAdmin(() -> jdbc.queryForObject(
                "SELECT count(*) FROM fornecedor WHERE tenant_id = ? AND nome = ?",
                Integer.class, tenantAId, "Fornecedor Criado Via Admin Impersonado"));
        assertEquals(1, countNoTenantA);
    }

    // ── Teste: 403 sem tenant selecionado ────────────────────────────────────

    @Test
    void adminSemTenantSelecionado_recebe403AoAcessarEndpointDeNegocio() throws Exception {
        String adminToken = loginAdmin(); // token recém-logado carrega tenant_id NULL

        mockMvc.perform(get("/fornecedores").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Selecione um tenant antes de acessar este recurso."));
    }

    // ── Teste: propagação via refresh ────────────────────────────────────────

    @Test
    void refresh_propagaTenantImpersonadoParaONovoAccessToken() throws Exception {
        String adminToken = loginAdmin();
        MvcResult selecionarResult = selecionarTenantResult(adminToken, tenantAId);
        String refreshTokenTenantA = refreshCookieValue(selecionarResult);

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshTokenTenantA))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andReturn();
        TokenResponse novosTokens = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), TokenResponse.class);

        UUID tenantIdNoNovoToken = jwtService.extractTenantId(jwtService.parseToken(novosTokens.accessToken()));
        assertEquals(tenantAId, tenantIdNoNovoToken,
                "tenant_id impersonado (tenant_id_impersonado do refresh token anterior) deve sobreviver ao refresh, não voltar a NULL");

        // Confirma funcionalmente, não só na claim: o novo access token ainda só
        // enxerga o tenant A (RLS real aplicada com o tenant propagado).
        mockMvc.perform(get("/fornecedores").header("Authorization", "Bearer " + novosTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Testes: POST /auth/selecionar-tenant ────────────────────────────────

    @Test
    void selecionarTenant_comTenantIdInexistente_retorna404() throws Exception {
        String adminToken = loginAdmin();
        UUID tenantInexistente = UUID.randomUUID();

        mockMvc.perform(post("/auth/selecionar-tenant")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SelecionarTenantRequest(tenantInexistente))))
                .andExpect(status().isNotFound());
    }

    @Test
    void selecionarTenant_comTenantIdNulo_saiDoModoNavegacaoEVoltaABloquearRotaDeNegocio() throws Exception {
        String adminToken = loginAdmin();
        selecionarTenant(adminToken, tenantAId); // entra em modo navegação primeiro

        TokenResponse tokensVoltaPainel = selecionarTenant(adminToken, null);

        mockMvc.perform(get("/fornecedores").header("Authorization", "Bearer " + tokensVoltaPainel.accessToken()))
                .andExpect(status().isForbidden());
    }
}
