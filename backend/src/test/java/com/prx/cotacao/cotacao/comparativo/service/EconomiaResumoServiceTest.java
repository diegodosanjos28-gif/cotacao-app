package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoResponse;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EconomiaResumoService#resumo} — KPIs de "Economia de Cotações" (Dashboard),
 * agregados no banco (CotacaoProdutoFornecedorRepository.resumoEconomiaFinalizadas,
 * native query) sobre TODAS as cotações FINALIZADA do tenant, replicando exatamente
 * frontend/src/lib/comparativo.ts (totalEconomia/percentualEconomia/
 * fornecedorMaisCompetitivo). Achado do usuário 08-20: antes desse endpoint, os KPIs
 * eram calculados no frontend sobre, no máximo, as 20 cotações mais recentes de
 * QUALQUER status (não "todas as finalizadas") — uma janela pequena e mal filtrada.
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555. Mesmo padrão
 * de setup de {@link ComparativoServiceTest}.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class EconomiaResumoServiceTest {

    @Autowired private EconomiaResumoService economiaResumoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Economia Resumo Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Economia Resumo Test");
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

    private EconomiaResumoResponse resumo(UUID tenantId) {
        return comoTenant(tenantId, () -> economiaResumoService.resumo());
    }

    private UUID criarFornecedor(UUID tenantId, String nome) {
        return criarFornecedor(tenantId, nome, FornecedorStatus.ATIVO);
    }

    private UUID criarFornecedor(UUID tenantId, String nome, FornecedorStatus status) {
        return comoTenant(tenantId, () -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            f.setStatus(status);
            return fornecedorRepository.save(f).getId();
        });
    }

    private UUID criarCotacao(UUID tenantId, CotacaoStatus status) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Economia Resumo Test");
            c.setStatus(status);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID adicionarItem(UUID tenantId, UUID cotacaoId, String quantidade, int ordem) {
        return comoTenant(tenantId, () -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal("Item Economia Resumo Test");
            cp.setQuantidade(new BigDecimal(quantidade));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void criarOferta(UUID tenantId, UUID itemId, UUID fornecedorId, String preco, boolean semEstoque, StatusItem status) {
        comoTenant(tenantId, () -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta de teste");
            cpf.setPrecoInformado(new BigDecimal(preco));
            cpf.setPrecoUnitarioCalculado(status == StatusItem.OK ? new BigDecimal(preco) : null);
            cpf.setSemEstoque(semEstoque);
            cpf.setStatus(status);
            return cpfRepository.save(cpf);
        });
    }

    // ── 1. Sem nenhuma cotação finalizada ───────────────────────────────────────

    @Test
    void sem_cotacao_finalizada_retorna_tudo_zerado() {
        criarCotacao(tenantAId, CotacaoStatus.EM_ANDAMENTO);

        EconomiaResumoResponse r = resumo(tenantAId);

        assertEquals(0, r.cotacoesProcessadas());
        assertEquals(0, new BigDecimal("0").compareTo(r.economiaAcumulada()));
        assertEquals(0, new BigDecimal("0").compareTo(r.mediaEconomiaPct()));
        assertNull(r.fornecedorMaisCompetitivoNome());
        assertNull(r.fornecedorMaisCompetitivoContagem());
    }

    // ── 2. Uma finalizada, um item, dois fornecedores — economia = (maior-menor)*qtd ──

    @Test
    void uma_finalizada_um_item_calcula_economia_e_percentual_corretos() {
        UUID fBarato = criarFornecedor(tenantAId, "Fornecedor Barato");
        UUID fCaro = criarFornecedor(tenantAId, "Fornecedor Caro");
        UUID cotacaoId = criarCotacao(tenantAId, CotacaoStatus.FINALIZADA);
        UUID itemId = adicionarItem(tenantAId, cotacaoId, "10.000", 1);
        criarOferta(tenantAId, itemId, fBarato, "8.00", false, StatusItem.OK);
        criarOferta(tenantAId, itemId, fCaro, "10.00", false, StatusItem.OK);

        EconomiaResumoResponse r = resumo(tenantAId);

        // economia = (10.00 - 8.00) * 10 = 20.00; recomendado = 8.00 * 10 = 80.00;
        // percentual = 20 / (80 + 20) * 100 = 20%.
        assertEquals(1, r.cotacoesProcessadas());
        assertEquals(0, new BigDecimal("20.00").compareTo(r.economiaAcumulada()));
        assertEquals(0, new BigDecimal("20.00").compareTo(r.mediaEconomiaPct()));
        assertEquals("Fornecedor Barato", r.fornecedorMaisCompetitivoNome());
        assertEquals(1, r.fornecedorMaisCompetitivoContagem());
    }

    // ── 3. Cotação não-finalizada não conta em nada, mesmo com ofertas válidas ──

    @Test
    void cotacao_em_andamento_nao_conta_em_nenhum_kpi() {
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Qualquer");
        UUID cotacaoId = criarCotacao(tenantAId, CotacaoStatus.EM_ANDAMENTO);
        UUID itemId = adicionarItem(tenantAId, cotacaoId, "5.000", 1);
        criarOferta(tenantAId, itemId, fornecedorId, "15.00", false, StatusItem.OK);

        EconomiaResumoResponse r = resumo(tenantAId);

        assertEquals(0, r.cotacoesProcessadas());
        assertEquals(0, new BigDecimal("0").compareTo(r.economiaAcumulada()));
    }

    // ── 4. Item sem nenhuma oferta válida contribui 0, cotação ainda é contada ──

    @Test
    void item_sem_oferta_valida_contribui_zero_mas_cotacao_ainda_conta_no_total() {
        UUID fSemEstoque = criarFornecedor(tenantAId, "Fornecedor Sem Estoque");
        UUID cotacaoId = criarCotacao(tenantAId, CotacaoStatus.FINALIZADA);
        UUID itemId = adicionarItem(tenantAId, cotacaoId, "10.000", 1);
        // Único preço informado está sem estoque — não é oferta válida.
        criarOferta(tenantAId, itemId, fSemEstoque, "8.00", true, StatusItem.OK);

        EconomiaResumoResponse r = resumo(tenantAId);

        assertEquals(1, r.cotacoesProcessadas(), "Cotação finalizada conta mesmo sem nenhum item com oferta válida");
        assertEquals(0, new BigDecimal("0").compareTo(r.economiaAcumulada()));
        assertEquals(0, new BigDecimal("0").compareTo(r.mediaEconomiaPct()),
                "Cotação sem oferta válida contribui 0 pra média, não é excluída do denominador");
        assertNull(r.fornecedorMaisCompetitivoNome());
    }

    // ── 5. Múltiplas finalizadas — soma acumulada, média correta sobre TODAS ────

    @Test
    void multiplas_finalizadas_soma_economia_e_tira_media_sobre_todas() {
        UUID fBarato = criarFornecedor(tenantAId, "Fornecedor Barato");
        UUID fCaro = criarFornecedor(tenantAId, "Fornecedor Caro");

        // Cotação 1: economia 20% (igual ao teste 2).
        UUID cotacao1 = criarCotacao(tenantAId, CotacaoStatus.FINALIZADA);
        UUID item1 = adicionarItem(tenantAId, cotacao1, "10.000", 1);
        criarOferta(tenantAId, item1, fBarato, "8.00", false, StatusItem.OK);
        criarOferta(tenantAId, item1, fCaro, "10.00", false, StatusItem.OK);

        // Cotação 2: só o fornecedor barato participou (sem economia — 1 oferta só,
        // menor == maior, economia = 0, mas recomendado = 8*10 = 80 > 0, percentual 0%).
        UUID cotacao2 = criarCotacao(tenantAId, CotacaoStatus.FINALIZADA);
        UUID item2 = adicionarItem(tenantAId, cotacao2, "10.000", 1);
        criarOferta(tenantAId, item2, fBarato, "8.00", false, StatusItem.OK);

        EconomiaResumoResponse r = resumo(tenantAId);

        assertEquals(2, r.cotacoesProcessadas());
        assertEquals(0, new BigDecimal("20.00").compareTo(r.economiaAcumulada()),
                "Só a cotação 1 gera economia (20.00); cotação 2 contribui 0");
        assertEquals(0, new BigDecimal("10.00").compareTo(r.mediaEconomiaPct()),
                "Média entre 20% (cotação 1) e 0% (cotação 2) = 10%, sobre as 2 finalizadas");
        // fBarato venceu o item da cotação 1 E é a única oferta da cotação 2 (também
        // conta como "vencedor" desse item, único candidato válido).
        assertEquals("Fornecedor Barato", r.fornecedorMaisCompetitivoNome());
        assertEquals(2, r.fornecedorMaisCompetitivoContagem());
    }

    // ── 6. Fornecedor INATIVO ainda pode vencer, mas o nome cai pro UUID (mesma regra de ComparativoService) ──

    @Test
    void fornecedor_inativo_vencedor_tem_nome_como_uuid_string() {
        UUID fInativo = criarFornecedor(tenantAId, "Fornecedor Será Inativado", FornecedorStatus.INATIVO);
        UUID cotacaoId = criarCotacao(tenantAId, CotacaoStatus.FINALIZADA);
        UUID itemId = adicionarItem(tenantAId, cotacaoId, "10.000", 1);
        criarOferta(tenantAId, itemId, fInativo, "8.00", false, StatusItem.OK);

        EconomiaResumoResponse r = resumo(tenantAId);

        assertEquals(fInativo.toString(), r.fornecedorMaisCompetitivoNome());
        assertEquals(1, r.fornecedorMaisCompetitivoContagem());
    }

    // ── 7. Isolamento multi-tenant ───────────────────────────────────────────────

    @Test
    void kpis_de_um_tenant_nunca_incluem_dados_de_outro_tenant() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor Tenant A");
        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor Tenant B");

        UUID cotacaoA = criarCotacao(tenantAId, CotacaoStatus.FINALIZADA);
        UUID itemA = adicionarItem(tenantAId, cotacaoA, "10.000", 1);
        criarOferta(tenantAId, itemA, fornecedorA, "5.00", false, StatusItem.OK);

        UUID cotacaoB = criarCotacao(tenantBId, CotacaoStatus.FINALIZADA);
        UUID itemB1 = adicionarItem(tenantBId, cotacaoB, "10.000", 1);
        UUID itemB2 = adicionarItem(tenantBId, cotacaoB, "10.000", 2);
        criarOferta(tenantBId, itemB1, fornecedorB, "50.00", false, StatusItem.OK);
        criarOferta(tenantBId, itemB2, fornecedorB, "99.00", false, StatusItem.OK);

        EconomiaResumoResponse a = resumo(tenantAId);
        assertEquals(1, a.cotacoesProcessadas(), "Tenant A só deve contar sua própria cotação finalizada");
        assertEquals("Fornecedor Tenant A", a.fornecedorMaisCompetitivoNome());

        EconomiaResumoResponse b = resumo(tenantBId);
        assertEquals(1, b.cotacoesProcessadas(), "Tenant B só deve contar sua própria cotação finalizada");
        assertEquals("Fornecedor Tenant B", b.fornecedorMaisCompetitivoNome());
        assertEquals(2, b.fornecedorMaisCompetitivoContagem());
    }
}
