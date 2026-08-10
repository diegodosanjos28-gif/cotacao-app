package com.prx.cotacao.identidade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.identidade.auth.service.JwtService;
import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.identidade.auth.dto.TokenResponse;
import com.prx.cotacao.identidade.dto.TenantRequest;
import com.prx.cotacao.identidade.dto.TenantResponse;
import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.dto.UsuarioAdminResponse;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.tenant.TenantContext;
import io.jsonwebtoken.Claims;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo ponta a ponta da Fase 1 do Admin PRX, via HTTP real (MockMvc + cadeia completa
 * de filtros do Spring Security — mesmo padrão de {@link com.prx.cotacao.identidade.auth.AuthIntegrationTest}):
 *
 * ADMIN_PRX loga → cria um tenant novo → cria um usuário OPERADOR_CLIENTE nesse tenant
 * (sem informar senha, forçando a geração automática) → o usuário recém-criado loga de
 * verdade com a senha em texto puro devolvida na resposta de criação → o JWT emitido
 * carrega o tenant_id correto → o usuário só enxerga dados do próprio tenant (isolamento
 * confirmado batendo em GET /fornecedores com um fornecedor plantado num tenant
 * "fantasma" diferente, que não pode vazar na listagem).
 *
 * Cobre também a validação de {@code @Size(min=8)} em {@link UsuarioAdminRequest#senha}
 * adicionada pelo security-reviewer: senha manual curta é rejeitada com 400.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminTenantUsuarioFluxoIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private static final String EMAIL_ADMIN = "admin-fluxo-e2e-test@prx.com";
    private static final String SENHA_ADMIN = "SenhaAdminFluxoTeste123!";

    private UUID adminId;
    private final List<UUID> tenantsCriados = new ArrayList<>();

    @BeforeEach
    void seedAdmin() {
        adminId = UUID.randomUUID();
        String hash = passwordEncoder.encode(SENHA_ADMIN);
        comoAdmin(() -> {
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, NULL, ?, ?, 'ADMIN_PRX', true)
                    """, adminId, EMAIL_ADMIN, hash);
            return null;
        });
    }

    @AfterEach
    void limpar() {
        comoAdmin(() -> {
            for (UUID tenantId : tenantsCriados) {
                jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
                jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
                jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            }
            jdbc.update("DELETE FROM usuario WHERE id = ?", adminId);
            return null;
        });
        tenantsCriados.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoAdmin(Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private String loginEObterAccessToken(String email, String senha) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, senha))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    private String bearer(String token) { return "Bearer " + token; }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void fluxoCompleto_adminCriaTenantEUsuario_novoUsuarioLogaEVeApenasSeuProprioTenant() throws Exception {
        String adminToken = loginEObterAccessToken(EMAIL_ADMIN, SENHA_ADMIN);

        // 1. Admin cria um tenant novo.
        MvcResult tenantResult = mockMvc.perform(post("/admin/tenants")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TenantRequest(
                                "Tenant Fluxo E2E", "Razão Social Fluxo E2E", null, TenantStatus.TRIAL, "basico"))))
                .andExpect(status().isCreated())
                .andReturn();
        TenantResponse tenantCriado = objectMapper.readValue(
                tenantResult.getResponse().getContentAsString(), TenantResponse.class);
        tenantsCriados.add(tenantCriado.id());

        // 2. Admin cria um usuário OPERADOR_CLIENTE nesse tenant, sem informar senha —
        // força a geração automática.
        MvcResult usuarioResult = mockMvc.perform(post("/admin/tenants/" + tenantCriado.id() + "/usuarios")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UsuarioAdminRequest("novo-operador-fluxo-e2e@prx.com", null, true))))
                .andExpect(status().isCreated())
                .andReturn();
        UsuarioAdminResponse usuarioCriado = objectMapper.readValue(
                usuarioResult.getResponse().getContentAsString(), UsuarioAdminResponse.class);

        assertEquals(Papel.OPERADOR_CLIENTE, usuarioCriado.papel(),
                "Papel deve ser sempre OPERADOR_CLIENTE — nunca vem do request, é hardcoded no service");
        assertNotNull(usuarioCriado.senhaGerada(), "Senha deve ter sido gerada automaticamente");

        // 3. Login como o usuário recém-criado, com a senha em texto puro devolvida na criação.
        MvcResult loginNovoUsuario = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("novo-operador-fluxo-e2e@prx.com", usuarioCriado.senhaGerada()))))
                .andExpect(status().isOk())
                .andReturn();
        TokenResponse tokensNovoUsuario = objectMapper.readValue(
                loginNovoUsuario.getResponse().getContentAsString(), TokenResponse.class);

        // 4. O JWT emitido carrega o tenant_id do tenant criado no passo 1 e papel OPERADOR_CLIENTE.
        Claims claims = jwtService.parseToken(tokensNovoUsuario.accessToken());
        assertEquals(tenantCriado.id(), jwtService.extractTenantId(claims));
        assertEquals(Papel.OPERADOR_CLIENTE, jwtService.extractPapel(claims));

        // 5. Planta um fornecedor num tenant "fantasma" diferente — se o isolamento
        // falhar, ele vazaria na listagem do passo 6.
        UUID tenantFantasmaId = UUID.randomUUID();
        comoAdmin(() -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Fantasma Isolamento', 'TRIAL')",
                    tenantFantasmaId);
            jdbc.update("INSERT INTO fornecedor (id, tenant_id, nome) VALUES (?, ?, 'Fornecedor Fantasma Isolamento')",
                    UUID.randomUUID(), tenantFantasmaId);
            return null;
        });
        tenantsCriados.add(tenantFantasmaId);

        // 6. O novo usuário só enxerga o próprio tenant — que está vazio (nenhum
        // fornecedor foi criado nele) — nunca o fornecedor plantado no tenant fantasma.
        mockMvc.perform(get("/fornecedores").header("Authorization", bearer(tokensNovoUsuario.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // Regressão: GET /admin/tenants sem nenhum query param (`q` chega null no
    // controller) quebrava com "function lower(bytea) does not exist" — o Hibernate
    // não conseguia inferir o tipo do parâmetro `:q` dentro de LOWER(CONCAT(...))
    // quando bindado como null. TenantService.listar agora sempre passa "" pro
    // repository nesse caso (TenantRepository.buscar comparado com `:q = ''`).
    @Test
    void listarTenants_semQueryParams_naoQuebra() throws Exception {
        String adminToken = loginEObterAccessToken(EMAIL_ADMIN, SENHA_ADMIN);

        mockMvc.perform(get("/admin/tenants").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void criarUsuario_comSenhaManualCurta_retorna400() throws Exception {
        String adminToken = loginEObterAccessToken(EMAIL_ADMIN, SENHA_ADMIN);

        MvcResult tenantResult = mockMvc.perform(post("/admin/tenants")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TenantRequest(
                                "Tenant Validacao Senha Curta", null, null, TenantStatus.TRIAL, null))))
                .andExpect(status().isCreated())
                .andReturn();
        TenantResponse tenantCriado = objectMapper.readValue(
                tenantResult.getResponse().getContentAsString(), TenantResponse.class);
        tenantsCriados.add(tenantCriado.id());

        // "Ab12345" tem 7 caracteres — abaixo do mínimo de 8 exigido por @Size.
        mockMvc.perform(post("/admin/tenants/" + tenantCriado.id() + "/usuarios")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UsuarioAdminRequest("senha-curta-validacao@prx.com", "Ab12345", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("senha")));
    }
}
