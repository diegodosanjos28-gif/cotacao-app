package com.prx.cotacao.identidade;

import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.service.UsuarioAdminService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UsuarioAdminService}: email único entre TODOS os tenants (usuario.email é
 * UNIQUE global, ver V2__create_usuarios.sql), 404 quando o tenant do path não existe,
 * geração automática de senha quando o admin não informa uma, e {@code buscarNoTenant}
 * (exercitado indiretamente via {@code atualizar}/{@code resetarSenha}) não vazando
 * existência de usuário de outro tenant.
 *
 * Roda dentro de {@link #comoAdmin} — as rotas reais (/admin/tenants/{id}/usuarios) só
 * chegam a este service com TenantContext.setAdmin(true) (papel ADMIN_PRX, ver
 * TenantFilter), então é isso que reproduz a policy de RLS de fato em vigor em runtime
 * para `usuario` e `tenant`.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class UsuarioAdminServiceTest {

    @Autowired private UsuarioAdminService usuarioAdminService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void criarTenants() {
        comoAdmin(() -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Usuario Admin Service");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Usuario Admin Service");
            b.setStatus(TenantStatus.TRIAL);
            tenantBId = tenantRepository.save(b).getId();
            return null;
        });
    }

    @AfterEach
    void limpar() {
        comoAdmin(() -> {
            jdbc.update("DELETE FROM usuario WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoAdmin(Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UsuarioAdminRequest request(String email, String senha) {
        return new UsuarioAdminRequest(email, senha, true);
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void criar_comEmailJaCadastrado_lancaConflictException() {
        comoAdmin(() -> usuarioAdminService.criar(tenantAId, request("duplicado@prx-test.com", "SenhaForte123")));

        assertThrows(ConflictException.class,
                () -> comoAdmin(() -> usuarioAdminService.criar(tenantBId,
                        request("duplicado@prx-test.com", "OutraSenhaForte123"))),
                "Email já usado por outro usuário (mesmo em outro tenant) deve lançar 409 — email é UNIQUE global");
    }

    @Test
    void criar_comTenantInexistente_lancaResourceNotFoundException() {
        UUID tenantInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> usuarioAdminService.criar(tenantInexistente,
                        request("qualquer@prx-test.com", "SenhaForte123"))));
    }

    @Test
    void criar_semSenhaInformada_geraSenhaAutomaticaEHashSalvoBateComEla() {
        UsuarioAdminService.ResultadoCriacao resultado = comoAdmin(() ->
                usuarioAdminService.criar(tenantAId, request("sem-senha@prx-test.com", null)));

        assertNotNull(resultado.senhaGerada(), "Senha deve ter sido gerada automaticamente");
        assertTrue(resultado.senhaGerada().length() >= 8);
        assertTrue(passwordEncoder.matches(resultado.senhaGerada(), resultado.usuario().getSenhaHash()),
                "Hash persistido deve corresponder à senha em texto puro devolvida na resposta");
    }

    @Test
    void criar_comSenhaInformada_naoRetornaSenhaGeradaEHashBateComASenhaEnviada() {
        UsuarioAdminService.ResultadoCriacao resultado = comoAdmin(() ->
                usuarioAdminService.criar(tenantAId, request("com-senha@prx-test.com", "SenhaManualForte1")));

        assertNull(resultado.senhaGerada(),
                "Quando o admin informa a senha, a resposta não deve ecoar nenhuma senha em texto puro");
        assertTrue(passwordEncoder.matches("SenhaManualForte1", resultado.usuario().getSenhaHash()));
    }

    @Test
    void atualizar_usuarioQuePertenceAOutroTenant_lancaResourceNotFoundExceptionSemVazarExistencia() {
        Usuario deB = comoAdmin(() ->
                usuarioAdminService.criar(tenantBId, request("do-tenant-b@prx-test.com", "SenhaForte123")).usuario());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> usuarioAdminService.atualizar(tenantAId, deB.getId(),
                        request("do-tenant-b@prx-test.com", null))),
                "Usuário existe, mas pertence ao tenant B — pedir via tenant A no path deve dar 404, não 403/200");
        assertTrue(ex.getMessage().contains(deB.getId().toString()));
    }

    @Test
    void resetarSenha_usuarioQuePertenceAOutroTenant_lancaResourceNotFoundException() {
        Usuario deB = comoAdmin(() ->
                usuarioAdminService.criar(tenantBId, request("reset-tenant-b@prx-test.com", "SenhaForte123")).usuario());

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> usuarioAdminService.resetarSenha(tenantAId, deB.getId())),
                "Reset de senha de usuário de outro tenant (via path errado) deve dar 404");
    }

    @Test
    void listar_comTenantInexistente_lancaResourceNotFoundException() {
        UUID tenantInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> usuarioAdminService.listar(tenantInexistente)));
    }
}
