package com.prx.cotacao;

import com.prx.cotacao.cotacao.core.dto.CotacaoAnteriorCursorProjecao;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoProjecao;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gap de teste apontado pelo security-reviewer na Fase A do refactor da Entrada de
 * Dados: {@link CotacaoProdutoFornecedorRepository#buscarPaginaAnteriores} /
 * {@link CotacaoProdutoFornecedorRepository#contarAnteriores} e
 * {@link CotacaoFornecedorRepository#buscarHistoricoFornecedores} são queries
 * NATIVAS — bypassam o Hibernate {@code @Filter} de tenant. A única proteção de
 * isolamento multi-tenant nelas é a RLS do Postgres (avaliada na conexão via
 * {@code app.current_tenant_id}, setado por {@code TenantAwareDataSource}).
 *
 * <p>Mesmo padrão comoTenantA/comoTenantB de {@link MultiTenantIsolationTest}, classe
 * irmã dedicada a essas duas queries nativas específicas (o arquivo original já é
 * grande e cobre um conjunto de repositórios diferente).</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class NativeQueryMultiTenantIsolationTest {

    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void criarTenants() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Native Query Isolation");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Native Query Isolation");
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
            jdbc.update("DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN " +
                    "(SELECT id FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id IN (?, ?)))", tenantAId, tenantBId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id IN (?, ?))", tenantAId, tenantBId);
            jdbc.update("DELETE FROM cotacao_fornecedor WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoTenant(UUID tenantId, Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarFornecedor(UUID tenantId, String nome) {
        return comoTenant(tenantId, () -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    /** Cotação FINALIZADA completa: 1 item vivo + 1 oferta OK do fornecedor dado. */
    private UUID criarCotacaoFinalizadaComItemEOferta(UUID tenantId, UUID fornecedorId, OffsetDateTime finalizadaEm) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Native Query Isolation Test");
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(finalizadaEm);
            UUID cotacaoId = cotacaoRepository.save(c).getId();

            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal("Item Native Query Isolation Test");
            cp.setQuantidade(new BigDecimal("5.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            UUID itemId = cotacaoProdutoRepository.save(cp).getId();

            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta de teste");
            cpf.setPrecoInformado(new BigDecimal("3.00"));
            cpf.setPrecoUnitarioCalculado(new BigDecimal("3.00"));
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            cpfRepository.save(cpf);

            return cotacaoId;
        });
    }

    private UUID criarCotacaoRascunho(UUID tenantId) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Rascunho não deve contar em contarAnteriores");
            c.setStatus(CotacaoStatus.RASCUNHO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private void adicionarCotacaoFornecedorConfirmado(UUID tenantId, UUID cotacaoId, UUID fornecedorId) {
        comoTenant(tenantId, () -> {
            CotacaoFornecedor cf = new CotacaoFornecedor();
            cf.setCotacaoId(cotacaoId);
            cf.setFornecedorId(fornecedorId);
            cf.setOrdem(1);
            cf.setStatus(CotacaoFornecedorStatus.CONFIRMADO);
            return cfRepository.save(cf);
        });
    }

    // ── buscarPaginaAnteriores / contarAnteriores (CotacaoProdutoFornecedorRepository) ──

    @Test
    void buscarPaginaAnteriores_bypassaHibernateFilter_masRLSNaoDeixaVazarCotacaoDeOutroTenant() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor A - anteriores");
        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor B - anteriores");
        OffsetDateTime agora = OffsetDateTime.now();
        UUID cotacaoA = criarCotacaoFinalizadaComItemEOferta(tenantAId, fornecedorA, agora);
        UUID cotacaoB = criarCotacaoFinalizadaComItemEOferta(tenantBId, fornecedorB, agora);

        List<CotacaoAnteriorCursorProjecao> vistasPorA = comoTenant(tenantAId,
                () -> cpfRepository.buscarPaginaAnteriores(null, null, 50));

        List<UUID> idsVistosPorA = vistasPorA.stream().map(CotacaoAnteriorCursorProjecao::getId).toList();
        assertTrue(idsVistosPorA.contains(cotacaoA), "Tenant A deve ver a própria cotação finalizada");
        assertFalse(idsVistosPorA.contains(cotacaoB),
                "RLS deve impedir que a query nativa vaze a cotação FINALIZADA do tenant B para o tenant A");

        List<CotacaoAnteriorCursorProjecao> vistasPorB = comoTenant(tenantBId,
                () -> cpfRepository.buscarPaginaAnteriores(null, null, 50));
        List<UUID> idsVistosPorB = vistasPorB.stream().map(CotacaoAnteriorCursorProjecao::getId).toList();
        assertTrue(idsVistosPorB.contains(cotacaoB), "Tenant B deve ver a própria cotação finalizada");
        assertFalse(idsVistosPorB.contains(cotacaoA),
                "RLS deve impedir que a query nativa vaze a cotação FINALIZADA do tenant A para o tenant B");
    }

    @Test
    void contarAnteriores_bypassaHibernateFilter_masContaApenasFinalizadaDoTenantAutenticado() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor A - contar");
        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor B - contar");
        criarCotacaoFinalizadaComItemEOferta(tenantAId, fornecedorA, OffsetDateTime.now());
        criarCotacaoRascunho(tenantAId); // RASCUNHO não deve contar
        criarCotacaoFinalizadaComItemEOferta(tenantBId, fornecedorB, OffsetDateTime.now());
        criarCotacaoFinalizadaComItemEOferta(tenantBId, fornecedorB, OffsetDateTime.now().minusDays(1));

        long totalA = comoTenant(tenantAId, cpfRepository::contarAnteriores);
        long totalB = comoTenant(tenantBId, cpfRepository::contarAnteriores);

        assertEquals(1, totalA, "contarAnteriores não deve incluir RASCUNHO nem cotações finalizadas de outro tenant");
        assertEquals(2, totalB, "contarAnteriores do tenant B não deve ser inflado por dados do tenant A");
    }

    // ── buscarHistoricoFornecedores (CotacaoFornecedorRepository) ──────────────

    @Test
    void buscarHistoricoFornecedores_bypassaHibernateFilter_masRLSNaoDeixaVazarFornecedorDeOutroTenant() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Distribuidora Isolamento A");
        UUID fornecedorB = criarFornecedor(tenantBId, "Distribuidora Isolamento B");

        List<FornecedorHistoricoProjecao> vistoPorA = comoTenant(tenantAId, cfRepository::buscarHistoricoFornecedores);
        List<UUID> idsVistosPorA = vistoPorA.stream().map(FornecedorHistoricoProjecao::getFornecedorId).toList();
        assertTrue(idsVistosPorA.contains(fornecedorA), "Tenant A deve ver o próprio fornecedor");
        assertFalse(idsVistosPorA.contains(fornecedorB),
                "RLS (fornecedor.tenant_id direto) deve impedir que a query nativa vaze fornecedor do tenant B");

        List<FornecedorHistoricoProjecao> vistoPorB = comoTenant(tenantBId, cfRepository::buscarHistoricoFornecedores);
        List<UUID> idsVistosPorB = vistoPorB.stream().map(FornecedorHistoricoProjecao::getFornecedorId).toList();
        assertTrue(idsVistosPorB.contains(fornecedorB), "Tenant B deve ver o próprio fornecedor");
        assertFalse(idsVistosPorB.contains(fornecedorA),
                "RLS (fornecedor.tenant_id direto) deve impedir que a query nativa vaze fornecedor do tenant A");
    }

    @Test
    void buscarHistoricoFornecedores_naoMisturaCotacoesParticipadasEntreTenantsMesmoComFornecedorHomonimo() {
        // Mesmo nome nos dois tenants, de propósito: garante que a contagem de
        // cotacoesParticipadas/coberturaMediaPct agregada via join
        // (cotacao_fornecedor -> cotacao -> cotacao_produto -> cotacao_produto_fornecedor)
        // não vaza por engano, e não é só o nome do fornecedor que está isolado.
        String nomeComum = "Distribuidora Homônima";
        UUID fornecedorA = criarFornecedor(tenantAId, nomeComum);
        UUID fornecedorB = criarFornecedor(tenantBId, nomeComum);

        UUID cotacaoA = criarCotacaoFinalizadaComItemEOferta(tenantAId, fornecedorA, OffsetDateTime.now());
        adicionarCotacaoFornecedorConfirmado(tenantAId, cotacaoA, fornecedorA);

        // Tenant B participa de DUAS cotações finalizadas — se a query nativa
        // vazasse por join, o tenant A poderia enxergar cotacoesParticipadas=3.
        UUID cotacaoB1 = criarCotacaoFinalizadaComItemEOferta(tenantBId, fornecedorB, OffsetDateTime.now());
        adicionarCotacaoFornecedorConfirmado(tenantBId, cotacaoB1, fornecedorB);
        UUID cotacaoB2 = criarCotacaoFinalizadaComItemEOferta(tenantBId, fornecedorB, OffsetDateTime.now().minusDays(1));
        adicionarCotacaoFornecedorConfirmado(tenantBId, cotacaoB2, fornecedorB);

        var porFornecedorIdA = comoTenant(tenantAId, cfRepository::buscarHistoricoFornecedores).stream()
                .collect(Collectors.toMap(FornecedorHistoricoProjecao::getFornecedorId, p -> p));
        var porFornecedorIdB = comoTenant(tenantBId, cfRepository::buscarHistoricoFornecedores).stream()
                .collect(Collectors.toMap(FornecedorHistoricoProjecao::getFornecedorId, p -> p));

        assertEquals(1, porFornecedorIdA.get(fornecedorA).getCotacoesParticipadas(),
                "cotacoesParticipadas do fornecedor do tenant A não pode incluir participações do tenant B");
        assertEquals(2, porFornecedorIdB.get(fornecedorB).getCotacoesParticipadas(),
                "cotacoesParticipadas do fornecedor do tenant B deve refletir só as próprias participações");
        assertFalse(porFornecedorIdA.containsKey(fornecedorB));
        assertFalse(porFornecedorIdB.containsKey(fornecedorA));
    }
}
