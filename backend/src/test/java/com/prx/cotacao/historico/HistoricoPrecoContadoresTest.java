package com.prx.cotacao.historico;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.historico.dto.HistoricoPrecoContadores;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CotacaoProdutoFornecedorRepository#contarKpisHistorico()} — a query nativa por
 * trás dos 3 cards da tela Histórico de Preços (produtos com histórico / acima da
 * referência / oportunidade). Peça de maior risco do PR de paginação (CTE + DISTINCT ON
 * + ROW_NUMBER, escopada ao tenant inteiro independente da página exibida) — sem teste
 * dedicado até aqui. Replica a mesma classificação de
 * frontend/src/lib/historicoPrecos.ts: {@code temHistorico} = pontos.length >= 2,
 * "acima" = variação >= 0 (empate conta como acima), "oportunidade" = variação < 0.
 *
 * <p>Setup espelha {@link HistoricoPrecoServiceTest} (mesmos helpers, Postgres local
 * porta 5555 via perfil dev).</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class HistoricoPrecoContadoresTest {

    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
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
            a.setNomeFantasia("Tenant A - Contadores Histórico Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Contadores Histórico Test");
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
            jdbc.update("DELETE FROM produto WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers (mesmo padrão de HistoricoPrecoServiceTest) ─────────────────────

    private <T> T comoTenant(UUID tenantId, Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarProduto(UUID tenantId, String nome) {
        return comoTenant(tenantId, () -> {
            Produto p = new Produto();
            p.setNome(nome);
            return produtoRepository.save(p).getId();
        });
    }

    private UUID criarFornecedor(UUID tenantId, String nome) {
        return comoTenant(tenantId, () -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    private UUID criarCotacao(UUID tenantId, String titulo, OffsetDateTime finalizadaEm) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo(titulo);
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(finalizadaEm);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID adicionarItem(UUID tenantId, UUID cotacaoId, UUID produtoId) {
        return comoTenant(tenantId, () -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("Item de teste");
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void criarOferta(UUID tenantId, UUID itemId, UUID fornecedorId, String preco) {
        comoTenant(tenantId, () -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta de teste");
            cpf.setPrecoInformado(new BigDecimal(preco));
            cpf.setPrecoUnitarioCalculado(new BigDecimal(preco));
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            return cpfRepository.save(cpf);
        });
    }

    // Cria 1 cotação FINALIZADA com 1 item + 1 oferta válida pro produto, na data dada.
    private void criarPontoDeReferencia(UUID tenantId, UUID produtoId, UUID fornecedorId, String titulo,
                                         OffsetDateTime finalizadaEm, String preco) {
        UUID cotacaoId = criarCotacao(tenantId, titulo, finalizadaEm);
        UUID itemId = adicionarItem(tenantId, cotacaoId, produtoId);
        criarOferta(tenantId, itemId, fornecedorId, preco);
    }

    private HistoricoPrecoContadores contadores(UUID tenantId) {
        return comoTenant(tenantId, () -> cpfRepository.contarKpisHistorico());
    }

    // ── 1. Produto sem nenhuma cotação finalizada não conta em nenhum contador ──

    @Test
    void produto_sem_cotacao_finalizada_nao_conta_em_nenhum_contador() {
        criarProduto(tenantAId, "Produto Nunca Cotado");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(0, c.getComHistorico());
        assertEquals(0, c.getAcima());
        assertEquals(0, c.getOportunidade());
    }

    // ── 2. Exatamente 1 ponto não conta em comHistorico (precisa de 2+ pra comparar) ──

    @Test
    void produto_com_exatamente_um_ponto_nao_conta_em_com_historico() {
        UUID produtoId = criarProduto(tenantAId, "Produto Um Ponto Só");
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Único");
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Única",
                OffsetDateTime.now(), "10.00");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(0, c.getComHistorico());
        assertEquals(0, c.getAcima());
        assertEquals(0, c.getOportunidade());
    }

    // ── 3. 2 pontos, mais recente mais caro → acima ─────────────────────────────

    @Test
    void dois_pontos_mais_recente_mais_caro_conta_em_acima() {
        UUID produtoId = criarProduto(tenantAId, "Produto Subiu De Preço");
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Alfa");
        OffsetDateTime agora = OffsetDateTime.now();
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Antiga", agora.minusDays(1), "8.00");
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Recente", agora, "10.00");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(1, c.getComHistorico());
        assertEquals(1, c.getAcima());
        assertEquals(0, c.getOportunidade());
    }

    // ── 4. 2 pontos, mais recente mais barato → oportunidade ────────────────────

    @Test
    void dois_pontos_mais_recente_mais_barato_conta_em_oportunidade() {
        UUID produtoId = criarProduto(tenantAId, "Produto Baixou De Preço");
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Beta");
        OffsetDateTime agora = OffsetDateTime.now();
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Antiga", agora.minusDays(1), "10.00");
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Recente", agora, "8.00");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(1, c.getComHistorico());
        assertEquals(0, c.getAcima());
        assertEquals(1, c.getOportunidade());
    }

    // ── 5. Empate entre os 2 pontos mais recentes conta como "acima" (mesma regra do
    //       frontend: variação == 0 é acima, não oportunidade) ──────────────────

    @Test
    void empate_de_preco_entre_os_dois_pontos_mais_recentes_conta_em_acima() {
        UUID produtoId = criarProduto(tenantAId, "Produto Preço Estável");
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Gama");
        OffsetDateTime agora = OffsetDateTime.now();
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Antiga", agora.minusDays(1), "10.00");
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Recente", agora, "10.00");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(1, c.getComHistorico());
        assertEquals(1, c.getAcima());
        assertEquals(0, c.getOportunidade());
    }

    // ── 6. Com 3+ pontos, só os 2 mais recentes entram na classificação — o mais
    //       antigo é ignorado (ROW_NUMBER/rn<=2 da query) ───────────────────────

    @Test
    void com_tres_pontos_so_os_dois_mais_recentes_importam_para_a_classificacao() {
        UUID produtoId = criarProduto(tenantAId, "Produto Três Cotações");
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Delta");
        OffsetDateTime agora = OffsetDateTime.now();
        // Mais antiga, preço bem baixo — se entrasse na comparação enviesaria pra
        // "acima" (5 -> 10 subiu, 5 -> 20 subiu ainda mais); mas o comportamento
        // esperado é olhar só os 2 mais recentes (20 -> 10 caiu = oportunidade).
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Mais Antiga", agora.minusDays(2), "5.00");
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Intermediária", agora.minusDays(1), "20.00");
        criarPontoDeReferencia(tenantAId, produtoId, fornecedorId, "Cotação Mais Recente", agora, "10.00");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(1, c.getComHistorico());
        assertEquals(0, c.getAcima());
        assertEquals(1, c.getOportunidade());
    }

    // ── 7. Agregação correta entre múltiplos produtos do mesmo tenant ───────────

    @Test
    void agrega_corretamente_varios_produtos_do_mesmo_tenant() {
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Compartilhado");
        OffsetDateTime agora = OffsetDateTime.now();

        criarProduto(tenantAId, "Produto Sem Histórico");

        UUID produtoAcima = criarProduto(tenantAId, "Produto Que Subiu");
        criarPontoDeReferencia(tenantAId, produtoAcima, fornecedorId, "Antiga 1", agora.minusDays(1), "8.00");
        criarPontoDeReferencia(tenantAId, produtoAcima, fornecedorId, "Recente 1", agora, "10.00");

        UUID produtoOportunidade = criarProduto(tenantAId, "Produto Que Caiu");
        criarPontoDeReferencia(tenantAId, produtoOportunidade, fornecedorId, "Antiga 2", agora.minusDays(1), "10.00");
        criarPontoDeReferencia(tenantAId, produtoOportunidade, fornecedorId, "Recente 2", agora, "8.00");

        HistoricoPrecoContadores c = contadores(tenantAId);

        assertEquals(2, c.getComHistorico());
        assertEquals(1, c.getAcima());
        assertEquals(1, c.getOportunidade());
    }

    // ── 8. Isolamento multi-tenant — os contadores não vazam entre tenants ──────

    @Test
    void contadores_sao_isolados_por_tenant() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor Tenant A");
        UUID produtoA = criarProduto(tenantAId, "Produto Tenant A");
        OffsetDateTime agora = OffsetDateTime.now();
        // Tenant A: oportunidade (caiu de preço).
        criarPontoDeReferencia(tenantAId, produtoA, fornecedorA, "A Antiga", agora.minusDays(1), "10.00");
        criarPontoDeReferencia(tenantAId, produtoA, fornecedorA, "A Recente", agora, "8.00");

        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor Tenant B");
        UUID produtoB = criarProduto(tenantBId, "Produto Tenant B");
        // Tenant B: acima (subiu de preço) — se vazasse pro tenant A, contaminaria
        // acima/oportunidade de forma perceptível nos dois lados.
        criarPontoDeReferencia(tenantBId, produtoB, fornecedorB, "B Antiga", agora.minusDays(1), "8.00");
        criarPontoDeReferencia(tenantBId, produtoB, fornecedorB, "B Recente", agora, "10.00");

        HistoricoPrecoContadores contadoresA = contadores(tenantAId);
        assertEquals(1, contadoresA.getComHistorico());
        assertEquals(0, contadoresA.getAcima());
        assertEquals(1, contadoresA.getOportunidade());

        HistoricoPrecoContadores contadoresB = contadores(tenantBId);
        assertEquals(1, contadoresB.getComHistorico());
        assertEquals(1, contadoresB.getAcima());
        assertEquals(0, contadoresB.getOportunidade());
    }
}
