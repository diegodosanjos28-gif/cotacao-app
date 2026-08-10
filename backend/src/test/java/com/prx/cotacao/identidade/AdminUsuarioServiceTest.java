package com.prx.cotacao.identidade;

import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.service.AdminUsuarioService;
import com.prx.cotacao.identidade.service.UsuarioAdminService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AdminUsuarioService}: CRUD de usuário ADMIN_PRX (rotas
 * /admin/administradores), espelho de {@link UsuarioAdminServiceTest} mas para o
 * papel ADMIN_PRX em vez de OPERADOR_CLIENTE.
 *
 * Cobre duas regras que não existem no service irmão:
 * 1. Guarda do último administrador ativo do sistema — regra GLOBAL (usuário
 *    ADMIN_PRX não tem tenant_id, então {@code countByPapelAndAtivo} conta TODOS os
 *    ADMIN_PRX ativos do banco, não só os criados neste teste). Por isso
 *    {@link #atualizar_desativarUnicoAdminAtivoDoSistema_lancaConflictException()}
 *    precisa isolar o estado global de verdade (desativa temporariamente qualquer
 *    outro ADMIN_PRX ativo pré-existente — ex.: seed de dev admin@prx.com — e
 *    restaura no @AfterEach) em vez de assumir que o banco está vazio.
 * 2. Separação de escopo entre {@link AdminUsuarioService} (ADMIN_PRX, tenant_id
 *    sempre NULL) e {@link UsuarioAdminService} (OPERADOR_CLIENTE, dentro de UM
 *    tenant) — cada service não deve enxergar/editar usuário do escopo do outro,
 *    nem por adivinhação de UUID.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AdminUsuarioServiceTest {

    @Autowired private AdminUsuarioService adminUsuarioService;
    @Autowired private UsuarioAdminService usuarioAdminService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> adminIdsCriados = new ArrayList<>();
    private final List<UUID> operadorIdsCriados = new ArrayList<>();
    private final List<UUID> tenantIdsCriados = new ArrayList<>();
    private final List<UUID> adminsPreExistentesParaRestaurar = new ArrayList<>();

    @AfterEach
    void limpar() {
        comoAdmin(() -> {
            for (UUID id : adminIdsCriados) jdbc.update("DELETE FROM usuario WHERE id = ?", id);
            for (UUID id : operadorIdsCriados) jdbc.update("DELETE FROM usuario WHERE id = ?", id);
            for (UUID id : tenantIdsCriados) jdbc.update("DELETE FROM tenant WHERE id = ?", id);
            // Restaura o estado ativo de qualquer ADMIN_PRX pré-existente que este
            // teste tenha desativado temporariamente para isolar a regra global.
            for (UUID id : adminsPreExistentesParaRestaurar) {
                jdbc.update("UPDATE usuario SET ativo = true WHERE id = ?", id);
            }
            return null;
        });
        adminIdsCriados.clear();
        operadorIdsCriados.clear();
        tenantIdsCriados.clear();
        adminsPreExistentesParaRestaurar.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoAdmin(Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UsuarioAdminRequest requestAdmin(String email, String senha) {
        return new UsuarioAdminRequest(email, senha, true);
    }

    private UUID criarTenantDeTeste(String nomeFantasia) {
        return comoAdmin(() -> {
            Tenant t = new Tenant();
            t.setNomeFantasia(nomeFantasia);
            t.setStatus(TenantStatus.TRIAL);
            return tenantRepository.save(t).getId();
        });
    }

    // ── Testes: guarda do último admin ativo ────────────────────────────────

    @Test
    void atualizar_desativarUnicoAdminAtivoDoSistema_lancaConflictException() {
        // Isola a regra global: desativa temporariamente qualquer outro ADMIN_PRX
        // ativo já existente no banco, senão o teste dependeria de o banco estar
        // vazio (não está — há seed de dev).
        comoAdmin(() -> {
            List<UUID> outrosAdminsAtivos = jdbc.queryForList(
                    "SELECT id FROM usuario WHERE papel = 'ADMIN_PRX' AND ativo = true", UUID.class);
            adminsPreExistentesParaRestaurar.addAll(outrosAdminsAtivos);
            for (UUID id : outrosAdminsAtivos) {
                jdbc.update("UPDATE usuario SET ativo = false WHERE id = ?", id);
            }
            return null;
        });

        Usuario unico = comoAdmin(() ->
                adminUsuarioService.criar(requestAdmin("unico-admin-ativo@prx-test.com", "SenhaForte123")).usuario());
        adminIdsCriados.add(unico.getId());

        assertThrows(ConflictException.class,
                () -> comoAdmin(() -> adminUsuarioService.atualizar(unico.getId(),
                        new UsuarioAdminRequest(unico.getEmail(), null, false))),
                "Deve recusar desativar o único ADMIN_PRX ativo do sistema");
    }

    @Test
    void atualizar_desativarUmAdminComOutrosAtivosRestando_funcionaNormalmente() {
        Usuario a = comoAdmin(() ->
                adminUsuarioService.criar(requestAdmin("admin-multiplo-a@prx-test.com", "SenhaForte123")).usuario());
        Usuario b = comoAdmin(() ->
                adminUsuarioService.criar(requestAdmin("admin-multiplo-b@prx-test.com", "SenhaForte123")).usuario());
        adminIdsCriados.add(a.getId());
        adminIdsCriados.add(b.getId());

        Usuario atualizado = comoAdmin(() -> adminUsuarioService.atualizar(a.getId(),
                new UsuarioAdminRequest(a.getEmail(), null, false)));

        assertFalse(atualizado.isAtivo(), "Com outros admins ativos restando, a desativação deve ser permitida");
    }

    // ── Testes: separação AdminUsuarioService / UsuarioAdminService ─────────

    @Test
    void adminCriadoPeloAdminUsuarioService_naoAparecoNaListagemDeOperadoresDoTenant() {
        UUID tenantId = criarTenantDeTeste("Tenant - Separacao Admin Nao Aparece Em Operadores");
        tenantIdsCriados.add(tenantId);

        Usuario admin = comoAdmin(() ->
                adminUsuarioService.criar(requestAdmin("admin-separacao-a@prx-test.com", "SenhaForte123")).usuario());
        adminIdsCriados.add(admin.getId());

        List<Usuario> operadoresDoTenant = comoAdmin(() -> usuarioAdminService.listar(tenantId));

        assertTrue(operadoresDoTenant.stream().noneMatch(u -> u.getId().equals(admin.getId())),
                "Usuário ADMIN_PRX (tenant_id NULL) não deve aparecer na listagem de OPERADOR_CLIENTE de nenhum tenant");
    }

    @Test
    void operadorCriadoPeloUsuarioAdminService_naoAparecoNaListagemDeAdministradores() {
        UUID tenantId = criarTenantDeTeste("Tenant - Separacao Operador Nao Aparece Em Admins");
        tenantIdsCriados.add(tenantId);

        Usuario operador = comoAdmin(() -> usuarioAdminService.criar(tenantId,
                new UsuarioAdminRequest("operador-separacao-b@prx-test.com", "SenhaForte123", true)).usuario());
        operadorIdsCriados.add(operador.getId());

        List<Usuario> admins = comoAdmin(adminUsuarioService::listar);

        assertTrue(admins.stream().noneMatch(u -> u.getId().equals(operador.getId())),
                "Usuário OPERADOR_CLIENTE não deve aparecer na listagem de administradores ADMIN_PRX");
    }

    @Test
    void atualizar_comIdDeOperadorCliente_lancaResourceNotFoundExceptionSemVazarExistencia() {
        UUID tenantId = criarTenantDeTeste("Tenant - AdminUsuarioService Nao Edita Operador");
        tenantIdsCriados.add(tenantId);

        Usuario operador = comoAdmin(() -> usuarioAdminService.criar(tenantId,
                new UsuarioAdminRequest("operador-nao-editavel-por-admin-service@prx-test.com", "SenhaForte123", true)).usuario());
        operadorIdsCriados.add(operador.getId());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> adminUsuarioService.atualizar(operador.getId(),
                        requestAdmin(operador.getEmail(), null))),
                "Usuário existe, mas é OPERADOR_CLIENTE, não ADMIN_PRX — AdminUsuarioService não pode editá-lo por adivinhação de UUID");
        assertTrue(ex.getMessage().contains(operador.getId().toString()));
    }

    @Test
    void resetarSenha_comIdDeOperadorCliente_lancaResourceNotFoundException() {
        UUID tenantId = criarTenantDeTeste("Tenant - AdminUsuarioService Nao Reseta Senha De Operador");
        tenantIdsCriados.add(tenantId);

        Usuario operador = comoAdmin(() -> usuarioAdminService.criar(tenantId,
                new UsuarioAdminRequest("operador-reset-bloqueado@prx-test.com", "SenhaForte123", true)).usuario());
        operadorIdsCriados.add(operador.getId());

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> adminUsuarioService.resetarSenha(operador.getId())),
                "Reset de senha de um OPERADOR_CLIENTE via AdminUsuarioService (rota de ADMIN_PRX) deve dar 404, não sucesso");
    }
}
