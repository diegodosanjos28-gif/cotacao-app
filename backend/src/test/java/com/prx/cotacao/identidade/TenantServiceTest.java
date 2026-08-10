package com.prx.cotacao.identidade;

import com.prx.cotacao.identidade.dto.TenantRequest;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.service.TenantService;
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
 * {@link TenantService}: CNPJ único (quando informado) entre todos os tenants, e 404
 * para {@code buscar}/{@code atualizar} de tenant inexistente.
 *
 * `tenant` só é visível via RLS a requests com {@code is_admin_request()} ativo (ver
 * V10__rls_policies.sql, policy {@code tenant_admin_only}) — mesma condição das rotas
 * reais /admin/tenants (SecurityConfig exige ROLE_ADMIN_PRX, e TenantFilter seta
 * TenantContext.setAdmin(true) para esse papel). Por isso todo teste aqui roda dentro
 * do helper {@link #comoAdmin}, espelhando o que a stack HTTP faz de verdade.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555, mesma convenção
 * de {@link com.prx.cotacao.fornecedor.FornecedorServiceNomeUnicoTest}.
 */
@SpringBootTest
@ActiveProfiles("dev")
class TenantServiceTest {

    @Autowired private TenantService tenantService;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> tenantsCriados = new ArrayList<>();

    @AfterEach
    void limpar() {
        if (tenantsCriados.isEmpty()) return;
        comoAdmin(() -> {
            for (UUID id : tenantsCriados) {
                jdbc.update("DELETE FROM tenant WHERE id = ?", id);
            }
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

    private TenantRequest request(String nomeFantasia, String cnpj) {
        return new TenantRequest(nomeFantasia, "Razão " + nomeFantasia, cnpj, TenantStatus.TRIAL, null);
    }

    private Tenant criar(String nomeFantasia, String cnpj) {
        Tenant t = comoAdmin(() -> tenantService.criar(request(nomeFantasia, cnpj)));
        tenantsCriados.add(t.getId());
        return t;
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void criar_comCnpjJaCadastradoEmOutroTenant_lancaConflictException() {
        criar("Tenant Original", "11.222.333/0001-44");

        assertThrows(ConflictException.class,
                () -> criar("Tenant Duplicado", "11.222.333/0001-44"),
                "Segundo tenant com o mesmo CNPJ deve lançar 409");
    }

    @Test
    void criar_semCnpj_naoValidaUnicidade() {
        assertDoesNotThrow(() -> criar("Tenant Sem CNPJ 1", null));
        assertDoesNotThrow(() -> criar("Tenant Sem CNPJ 2", null),
                "CNPJ nulo/em branco não deve disputar unicidade entre tenants");
    }

    @Test
    void atualizar_comCnpjJaUsadoPorOutroTenant_lancaConflictException() {
        criar("Tenant Dono Do Cnpj", "22.333.444/0001-55");
        Tenant alvo = criar("Tenant Alvo Da Atualizacao", "99.888.777/0001-66");

        assertThrows(ConflictException.class,
                () -> comoAdmin(() -> tenantService.atualizar(alvo.getId(),
                        request("Tenant Alvo Renomeado", "22.333.444/0001-55"))),
                "Atualizar para um CNPJ já usado por outro tenant deve lançar 409");
    }

    @Test
    void atualizar_mantendoOProprioCnpj_naoLancaConflictException() {
        Tenant t = criar("Tenant Mesmo Cnpj", "33.444.555/0001-77");

        Tenant atualizado = assertDoesNotThrow(
                () -> comoAdmin(() -> tenantService.atualizar(t.getId(),
                        request("Tenant Mesmo Cnpj Renomeado", "33.444.555/0001-77"))),
                "Reenviar o mesmo CNPJ do próprio tenant não deve disparar conflito consigo mesmo");

        assertEquals("Tenant Mesmo Cnpj Renomeado", atualizado.getNomeFantasia());
    }

    @Test
    void buscar_tenantInexistente_lancaResourceNotFoundException() {
        UUID idInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> tenantService.buscar(idInexistente)));
    }

    @Test
    void atualizar_tenantInexistente_lancaResourceNotFoundException() {
        UUID idInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> tenantService.atualizar(idInexistente,
                        request("Não Importa", null))));
    }
}
