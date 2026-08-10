package com.prx.cotacao.fornecedor;

import com.prx.cotacao.fornecedor.dto.FornecedorRequest;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.service.FornecedorService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressão do Fix 1 (fornecedor duplicado): {@link FornecedorService#criar} e
 * {@link FornecedorService#atualizar} devem rejeitar nome duplicado
 * (case-insensitive) entre fornecedores não-inativos do MESMO tenant, sem afetar
 * outros tenants, e sem bloquear reuso de nome após inativação (soft-delete).
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), ver TestcontainersConfig
 * e application-dev.yml — porta 5433.
 */
@SpringBootTest
@ActiveProfiles("dev")
class FornecedorServiceNomeUnicoTest {

    @Autowired private FornecedorService fornecedorService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void criarTenants() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Fornecedor Nome Unico");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Fornecedor Nome Unico");
            b.setStatus(TenantStatus.TRIAL);
            tenantBId = tenantRepository.save(b).getId();
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoTenantA(Supplier<T> fn) {
        TenantContext.set(tenantAId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private <T> T comoTenantB(Supplier<T> fn) {
        TenantContext.set(tenantBId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private FornecedorRequest request(String nome) {
        return new FornecedorRequest(nome, null, null, null, null, null);
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void criar_segundo_fornecedor_com_mesmo_nome_case_insensitive_lanca_conflict() {
        comoTenantA(() -> fornecedorService.criar(request("Distribuidora A")));

        assertThrows(ConflictException.class,
                () -> comoTenantA(() -> fornecedorService.criar(request("distribuidora a"))),
                "Segundo fornecedor com nome igual (case-insensitive) deve lançar 409");
    }

    @Test
    void criar_fornecedor_com_mesmo_nome_em_tenants_diferentes_nao_colide() {
        Fornecedor fa = comoTenantA(() -> fornecedorService.criar(request("Distribuidora Compartilhada")));
        Fornecedor fb = assertDoesNotThrow(
                () -> comoTenantB(() -> fornecedorService.criar(request("Distribuidora Compartilhada"))),
                "Mesmo nome em tenants diferentes não deve colidir");

        assertNotEquals(fa.getId(), fb.getId());
        assertEquals(tenantAId, fa.getTenantId());
        assertEquals(tenantBId, fb.getTenantId());
    }

    @Test
    void inativar_fornecedor_libera_nome_para_reuso() {
        Fornecedor original = comoTenantA(() -> fornecedorService.criar(request("Distribuidora Reuso")));

        comoTenantA(() -> { fornecedorService.inativar(original.getId()); return null; });

        Fornecedor novo = assertDoesNotThrow(
                () -> comoTenantA(() -> fornecedorService.criar(request("Distribuidora Reuso"))),
                "Após inativar o antigo, o nome deve poder ser reutilizado");

        assertNotEquals(original.getId(), novo.getId());
        assertEquals(FornecedorStatus.ATIVO, novo.getStatus());
    }

    @Test
    void atualizar_nao_colide_consigo_mesmo() {
        Fornecedor f = comoTenantA(() -> fornecedorService.criar(request("Distribuidora Original")));

        Fornecedor atualizado = assertDoesNotThrow(
                () -> comoTenantA(() -> fornecedorService.atualizar(f.getId(),
                        new FornecedorRequest("Distribuidora Original", "5 dias", null, null, null, null))),
                "Editar outros campos sem mudar o nome não deve lançar ConflictException");

        assertEquals("5 dias", atualizado.getPrazoEntregaPadrao());
    }

    @Test
    void atualizar_pode_colidir_com_outro_fornecedor_existente() {
        comoTenantA(() -> fornecedorService.criar(request("Distribuidora Um")));
        Fornecedor dois = comoTenantA(() -> fornecedorService.criar(request("Distribuidora Dois")));

        assertThrows(ConflictException.class,
                () -> comoTenantA(() -> fornecedorService.atualizar(dois.getId(), request("distribuidora um"))),
                "Renomear para um nome já usado por outro fornecedor deve lançar 409");
    }
}
