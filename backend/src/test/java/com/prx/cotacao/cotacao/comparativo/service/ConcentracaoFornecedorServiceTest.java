package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.ConcentracaoFornecedoresResponse;
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
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConcentracaoFornecedorService#topConcentracao} — painel "Concentração de
 * Fornecedores" (Dashboard): Top N fornecedores por participação no valor comprado
 * dentro de uma competência (mês), com alerta de dependência (>= 40%).
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class ConcentracaoFornecedorServiceTest {

    @Autowired private ConcentracaoFornecedorService concentracaoFornecedorService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private final YearMonth mesRef = YearMonth.of(2026, 8);

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Concentracao Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();
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
                    "(SELECT id FROM cotacao WHERE tenant_id = ?))", tenantAId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantAId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantAId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantAId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantAId);
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

    private UUID criarFornecedor(String nome) {
        return comoTenant(tenantAId, () -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    // Cotação FINALIZADA dentro da competência de teste (mesRef), com 1 item vencido
    // só pelo fornecedor dado (única oferta, sem disputa) — quantidade sempre 1, então
    // valorComprado = preco.
    private void cotacaoVencidaPor(UUID fornecedorId, String preco) {
        comoTenant(tenantAId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Concentração Test");
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(mesRef.atDay(15).atStartOfDay(VariacaoPrecoService.FUSO_COMPETENCIA).toOffsetDateTime());
            UUID cotacaoId = cotacaoRepository.save(c).getId();

            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal("Item Concentração Test");
            cp.setQuantidade(new BigDecimal("1.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            UUID itemId = cotacaoProdutoRepository.save(cp).getId();

            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta de teste");
            cpf.setPrecoInformado(new BigDecimal(preco));
            cpf.setPrecoUnitarioCalculado(new BigDecimal(preco));
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            cpfRepository.save(cpf);
            return null;
        });
    }

    private ConcentracaoFornecedoresResponse topConcentracao(YearMonth mes) {
        return comoTenant(tenantAId, () -> concentracaoFornecedorService.topConcentracao(mes));
    }

    // ── 1. Share calculado corretamente, sem risco de dependência ──────────────
    // 3 fornecedores iguais (~33,3% cada) — nenhum atinge o limite de 40%. Um split
    // 50/50 entre só 2 fornecedores NÃO serviria de caso "sem risco": 50% já é >= 40%.

    @Test
    void tres_fornecedores_equilibrados_abaixo_do_limite_nao_disparam_alerta() {
        UUID f1 = criarFornecedor("Fornecedor Equilibrado 1");
        UUID f2 = criarFornecedor("Fornecedor Equilibrado 2");
        UUID f3 = criarFornecedor("Fornecedor Equilibrado 3");
        cotacaoVencidaPor(f1, "100.00");
        cotacaoVencidaPor(f2, "100.00");
        cotacaoVencidaPor(f3, "100.00");

        ConcentracaoFornecedoresResponse r = topConcentracao(mesRef);

        assertEquals(3, r.fornecedores().size());
        assertEquals(40, r.limiteDependenciaPct());
        assertFalse(r.algumEmRisco());
        r.fornecedores().forEach(item -> {
            assertEquals(0, new BigDecimal("33.33").compareTo(item.sharePct().setScale(2, java.math.RoundingMode.HALF_UP)));
            assertFalse(item.acimaDoLimite());
        });
    }

    // ── 2. Fornecedor concentrando >= 40% dispara o alerta de dependência ──────

    @Test
    void fornecedor_com_maioria_do_valor_dispara_alerta_de_dependencia() {
        UUID fDominante = criarFornecedor("Fornecedor Dominante");
        UUID fPequeno = criarFornecedor("Fornecedor Pequeno");
        cotacaoVencidaPor(fDominante, "80.00");
        cotacaoVencidaPor(fPequeno, "20.00");

        ConcentracaoFornecedoresResponse r = topConcentracao(mesRef);

        assertTrue(r.algumEmRisco());
        var lider = r.fornecedores().get(0);
        assertEquals(fDominante, lider.fornecedorId());
        assertEquals(0, new BigDecimal("80.00").compareTo(lider.sharePct()));
        assertTrue(lider.acimaDoLimite());
    }

    // ── 3. Nunca mistura competências ────────────────────────────────────────────

    @Test
    void fornecedor_de_outro_mes_nao_entra_na_competencia_pesquisada() {
        UUID fornecedorId = criarFornecedor("Fornecedor Mês Errado");
        cotacaoVencidaPor(fornecedorId, "100.00");

        ConcentracaoFornecedoresResponse r = topConcentracao(mesRef.plusMonths(1));

        assertEquals(0, r.fornecedores().size());
        assertFalse(r.algumEmRisco());
    }
}
