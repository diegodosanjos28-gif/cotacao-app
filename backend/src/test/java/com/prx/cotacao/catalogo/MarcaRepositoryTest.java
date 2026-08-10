package com.prx.cotacao.catalogo;

import com.prx.cotacao.catalogo.entity.Marca;
import com.prx.cotacao.catalogo.repository.MarcaRepository;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolamento multi-tenant do catálogo de marcas (V16): {@code tenant_id IS NULL} é uma
 * marca default GLOBAL, visível a todo tenant; {@code tenant_id != null} é uma marca
 * cadastrada pelo próprio tenant, visível só a ele (nunca a outro tenant comum). Policy
 * de RLS (ver V16__create_marca.sql): leitura = global OU do próprio tenant; escrita =
 * só o próprio tenant, nunca uma linha global.
 *
 * <p>Padrão de setup espelha {@link com.prx.cotacao.MultiTenantIsolationTest} — sem
 * {@code @Transactional} na classe (cada bloco usa {@link TransactionTemplate} para que
 * {@code TenantAwareTransactionManager} habilite o tenant certo por transação).</p>
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class MarcaRepositoryTest {

    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MarcaRepository marcaRepository;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void criarTenants() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Teste Marca");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Teste Marca");
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
            jdbc.update("DELETE FROM marca WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers (mesmo padrão de MultiTenantIsolationTest) ──────────────────────

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

    private Marca novaMarca(UUID tenantId, String nome) {
        Marca m = new Marca();
        m.setTenantId(tenantId);
        m.setNome(nome);
        // Marca não tem @PrePersist/valor default para criado_em (NOT NULL na coluna,
        // DEFAULT NOW() só se aplica a INSERT bruto que omite a coluna) — precisa ser
        // setado explicitamente pelo caller antes de save(), senão o insert falha com
        // violação de NOT NULL mascarando o comportamento de RLS que estes testes
        // realmente querem exercitar.
        m.setCriadoEm(OffsetDateTime.now());
        return m;
    }

    // ── 1. Tenant sem marcas próprias enxerga as ~72 marcas globais ─────────────

    @Test
    void tenant_sem_marcas_proprias_enxerga_marcas_globais_do_seed() {
        List<String> nomes = comoTenantA(() -> marcaRepository.findNomesOrdenadosPorEspecificidade());

        assertTrue(nomes.size() >= 70, "Deveria enxergar o seed de ~72 marcas globais, viu " + nomes.size());
        assertTrue(nomes.contains("heinz"), "Seed global deveria incluir 'heinz'");
    }

    // ── 2. Tenant com marca própria enxerga a própria + as globais ──────────────

    @Test
    void tenant_com_marca_propria_enxerga_propria_mais_globais() {
        long totalGlobal = comoTenantA(() -> marcaRepository.findNomesOrdenadosPorEspecificidade()).size();

        comoTenantA(() -> marcaRepository.save(novaMarca(tenantAId, "marca-exclusiva-tenant-a")));

        List<String> nomesDepois = comoTenantA(() -> marcaRepository.findNomesOrdenadosPorEspecificidade());

        assertEquals(totalGlobal + 1, nomesDepois.size());
        assertTrue(nomesDepois.contains("marca-exclusiva-tenant-a"));
        assertTrue(nomesDepois.contains("heinz"), "Continua enxergando as marcas globais também");
    }

    // ── 3. Tenant não enxerga marca cadastrada por outro tenant ─────────────────

    @Test
    void tenant_nao_enxerga_marca_no_global_cadastrada_por_outro_tenant() {
        comoTenantA(() -> marcaRepository.save(novaMarca(tenantAId, "marca-privada-tenant-a")));

        List<String> nomesVistosPorB = comoTenantB(() -> marcaRepository.findNomesOrdenadosPorEspecificidade());

        assertFalse(nomesVistosPorB.contains("marca-privada-tenant-a"),
                "Tenant B não pode enxergar marca não-global cadastrada pelo tenant A");
    }

    // ── 4. Tenant comum não pode inserir marca global (tenant_id = NULL) ────────

    @Test
    void tenant_comum_nao_consegue_inserir_marca_global_viola_policy_de_rls() {
        assertThrows(DataAccessException.class, () ->
                comoTenantA(() -> marcaRepository.save(novaMarca(null, "tentativa-marca-global"))));
    }

    // ── 5. Duas marcas com mesmo nome em tenants DIFERENTES não colidem ─────────

    @Test
    void duas_marcas_com_mesmo_nome_em_tenants_diferentes_nao_colidem() {
        comoTenantA(() -> marcaRepository.save(novaMarca(tenantAId, "marca-compartilhada")));
        comoTenantB(() -> marcaRepository.save(novaMarca(tenantBId, "marca-compartilhada")));

        List<String> nomesPorA = comoTenantA(() -> marcaRepository.findNomesOrdenadosPorEspecificidade());
        List<String> nomesPorB = comoTenantB(() -> marcaRepository.findNomesOrdenadosPorEspecificidade());

        assertTrue(nomesPorA.contains("marca-compartilhada"));
        assertTrue(nomesPorB.contains("marca-compartilhada"));
        assertEquals(1, nomesPorA.stream().filter("marca-compartilhada"::equals).count(),
                "Índice único é por tenant — cada tenant só vê a própria linha, sem duplicar");
    }
}
