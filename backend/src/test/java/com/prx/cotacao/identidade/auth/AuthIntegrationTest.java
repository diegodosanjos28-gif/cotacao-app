package com.prx.cotacao.identidade.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.identidade.auth.dto.LoginRequest;
import com.prx.cotacao.identidade.auth.dto.TokenResponse;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de regressão do fluxo de autenticação (POST /auth/login, POST /auth/refresh).
 *
 * Contexto: depois que RLS passou a ser de fato aplicado (app conectando como
 * cotacao_app, não mais como superuser), /auth/login e /auth/refresh quebraram por
 * completo — o lookup de Usuario por email/id acontece ANTES de qualquer tenant ser
 * conhecido (é o próprio propósito desses dois endpoints), e a policy de RLS em
 * `usuario` bloqueia toda linha sem is_admin_request() ativo. O fix foi TenantFilter
 * setar TenantContext.setAdmin(true) para qualquer request sob /auth/**, ANTES do
 * controller/service rodarem.
 *
 * Usa MockMvc (não TestRestTemplate) para exercitar a cadeia real de filtros do
 * Spring Security — incluindo JwtAuthFilter e TenantFilter, registrados via
 * SecurityConfig.filterChain() — sem depender de um socket HTTP real. O
 * @AutoConfigureMockMvc detecta spring-security-test no classpath e aplica o
 * springSecurityFilterChain de verdade nas requisições simuladas, então isso NÃO é
 * o mesmo que chamar AuthService diretamente via @Autowired (que pularia o filtro
 * inteiro e não pegaria a classe de bug que motivou este arquivo). TestRestTemplate
 * com um servidor real também funcionaria, mas o request factory default do JDK
 * (SimpleClientHttpRequestFactory/HttpURLConnection) tropeça num bug conhecido
 * ("cannot retry due to server authentication, in streaming mode") ao fazer POST com
 * corpo e receber 401 de volta — daí a escolha por MockMvc aqui.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), mesma convenção dos demais
 * testes de integração deste pacote (MultiTenantIsolationTest, EmbalagemSnapshotIsolationTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String SENHA_PLANA = "SenhaTeste123!";
    private static final String EMAIL_ATIVO = "operador-auth-test@prx.com";
    private static final String EMAIL_INATIVO = "inativo-auth-test@prx.com";
    private static final String EMAIL_INEXISTENTE = "nao-existe-auth-test@prx.com";

    private UUID tenantId;
    private UUID usuarioAtivoId;
    private UUID usuarioInativoId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        usuarioAtivoId = UUID.randomUUID();
        usuarioInativoId = UUID.randomUUID();
        String hash = passwordEncoder.encode(SENHA_PLANA);

        // JDBC direto com TenantContext.setAdmin(true), mesmo padrão usado no
        // @BeforeEach de EmbalagemSnapshotIsolationTest — evita buffering do JPA e
        // não depende de RLS estar aberto para o teste conseguir semear os dados.
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Auth Integration Test', 'TRIAL')",
                    tenantId);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, ?, ?, 'OPERADOR_CLIENTE', true)
                    """, usuarioAtivoId, tenantId, EMAIL_ATIVO, hash);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, ?, ?, 'OPERADOR_CLIENTE', false)
                    """, usuarioInativoId, tenantId, EMAIL_INATIVO, hash);
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            // refresh_token tem ON DELETE CASCADE em usuario_id, mas apagamos explícito
            // para deixar a intenção clara e não depender só da cascade.
            jdbc.update("DELETE FROM refresh_token WHERE usuario_id IN (?, ?)", usuarioAtivoId, usuarioInativoId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MvcResult login(String email, String senha) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, senha))))
                .andReturn();
    }

    private MvcResult refresh(String refreshTokenCookieValue) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshTokenCookieValue))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andReturn();
    }

    private TokenResponse parseTokens(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    // refreshToken nunca vem mais no corpo (TokenResponse.refreshToken tem @JsonIgnore)
    // — só no Set-Cookie httpOnly da resposta.
    private String refreshCookieValue(MvcResult result) {
        Cookie refreshCookie = result.getResponse().getCookie("refresh_token");
        assertNotNull(refreshCookie, "esperava Set-Cookie refresh_token na resposta");
        return refreshCookie.getValue();
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void login_comCredenciaisCorretas_retorna200ComTokens() throws Exception {
        // Este é o teste que teria pego a regressão: exercita a cadeia real de filtros
        // (TenantFilter incluído via springSecurityFilterChain), não chama AuthService
        // diretamente.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_ATIVO, SENHA_PLANA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().path("refresh_token", "/api/auth"))
                .andExpect(cookie().sameSite("refresh_token", "Strict"));
    }

    @Test
    void login_comSenhaErrada_retorna401ComMensagemGenerica() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_ATIVO, "senha-completamente-errada"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Credenciais inválidas"));
    }

    @Test
    void login_comEmailInexistente_retorna401ComMesmaMensagemGenerica() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_INEXISTENTE, SENHA_PLANA))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Credenciais inválidas"));
        // Mensagem idêntica à de senha errada — não pode vazar se o email existe.
    }

    @Test
    void login_comUsuarioInativo_retorna401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL_INATIVO, SENHA_PLANA))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Credenciais inválidas"));
    }

    @Test
    void refresh_comTokenValido_retornaNovosTokensQueFuncionamEmEndpointProtegido() throws Exception {
        MvcResult loginResult = login(EMAIL_ATIVO, SENHA_PLANA);
        String refreshTokenOriginal = refreshCookieValue(loginResult);

        MvcResult refreshResult = refresh(refreshTokenOriginal);
        assertEquals(200, refreshResult.getResponse().getStatus());

        TokenResponse novosTokens = parseTokens(refreshResult);
        String refreshTokenNovo = refreshCookieValue(refreshResult);
        assertNotNull(novosTokens.accessToken());
        assertFalse(novosTokens.accessToken().isBlank());
        // refreshToken sempre difere (carrega um jti aleatório); accessToken pode
        // colidir com o original se login+refresh acontecerem no mesmo segundo (iat
        // idêntico + mesmas claims => mesmo JWT assinado), então não é uma asserção
        // útil aqui — o que importa é que o token funcione de verdade abaixo.
        assertNotEquals(refreshTokenOriginal, refreshTokenNovo);

        // Fecha o loop no problema de lookup-por-ID-sem-tenant em refresh(): o novo
        // access token precisa funcionar de verdade num endpoint protegido, não só
        // ser gerado com sucesso.
        mockMvc.perform(get("/cotacoes").header("Authorization", "Bearer " + novosTokens.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_reutilizandoTokenJaUsado_retorna401EIndicaRevogacao() throws Exception {
        String refreshTokenOriginal = refreshCookieValue(login(EMAIL_ATIVO, SENHA_PLANA));

        MvcResult primeiroUso = refresh(refreshTokenOriginal);
        assertEquals(200, primeiroUso.getResponse().getStatus());

        // Reuso do MESMO refresh token original (já marcado usado=true na 1ª chamada)
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshTokenOriginal))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Token já utilizado — todos os tokens foram revogados"));
    }

    @Test
    void refresh_semCookie_retorna401() throws Exception {
        mockMvc.perform(post("/auth/refresh").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_semHeaderCsrf_retorna403() throws Exception {
        String refreshToken = refreshCookieValue(login(EMAIL_ATIVO, SENHA_PLANA));

        mockMvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isForbidden());
    }

    // Achado do security-reviewer: comparar path via request.getRequestURI() bruto
    // (não decodificado) contra um Set<String> literal deixava passar
    // "/auth/%72efresh" — o Dispatcher decodifica e roteia pra refresh() normalmente,
    // mas a string bruta não batia no Set, então o filtro CSRF nem via a rota como
    // protegida. CsrfHeaderFilter passou a usar AntPathRequestMatcher (mesma
    // normalização usada pelo Dispatcher) especificamente para fechar isso.
    @Test
    void refresh_comPathPercentEncoded_aindaExigeHeaderCsrf() throws Exception {
        String refreshToken = refreshCookieValue(login(EMAIL_ATIVO, SENHA_PLANA));

        // post(String) do MockMvc trata "%72" como texto literal e re-escapa o "%"
        // (viraria "%2572", double-encoding — outro caso que o firewall já barra
        // sozinho, não o que este teste quer exercitar). post(URI) com um URI já
        // pronto evita esse re-encode e manda o "/auth/%72efresh" de verdade.
        mockMvc.perform(post(new java.net.URI("http://localhost/auth/%72efresh"))
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void logout_limpaCookieELogoutRefreshFalhaDepois() throws Exception {
        String refreshToken = refreshCookieValue(login(EMAIL_ATIVO, SENHA_PLANA));

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie("refresh_token", refreshToken))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("refresh_token", 0));

        // O cookie de logout limpa o token do cliente, mas o token original em si já
        // foi revogado no banco por essa mesma chamada — reutilizá-lo direto (como um
        // atacante que capturou o valor antes do logout faria) tem que falhar.
        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_semCookie_retorna204() throws Exception {
        mockMvc.perform(post("/auth/logout").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNoContent());
    }

    @Test
    void bypassDeAdminEmAuthSlashStarNaoVazaParaEndpointsAutenticados() throws Exception {
        // Confirma que TenantContext.setAdmin(true) — setado pelo TenantFilter só para
        // /auth/** — não vaza para uma request desautenticada em outra rota protegida.
        // TenantContext é ThreadLocal e limpo no finally do filtro a cada request, mas
        // esse é exatamente o tipo de comportamento que uma refatoração descuidada do
        // filtro poderia quebrar silenciosamente. Status é sempre 401 (não 401 ou 403)
        // desde a fix do RestAuthenticationEntryPoint — ver RestAuthenticationEntryPointTest.
        mockMvc.perform(get("/cotacoes"))
                .andExpect(status().isUnauthorized());
    }
}
