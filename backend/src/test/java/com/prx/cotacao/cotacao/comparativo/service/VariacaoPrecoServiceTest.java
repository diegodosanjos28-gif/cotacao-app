package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.comparativo.dto.VariacaoPrecoResponse;
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
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VariacaoPrecoService#topSpread} — painel "Variação de Preço por Produto"
 * (Dashboard): Top N produtos por spread de preço dentro de uma competência (mês).
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class VariacaoPrecoServiceTest {

    @Autowired private VariacaoPrecoService variacaoPrecoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private ProdutoRepository produtoRepository;
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
            a.setNomeFantasia("Tenant A - Variacao Preco Test");
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
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantAId);
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

    private UUID criarProduto(String nome) {
        return comoTenant(tenantAId, () -> {
            Produto p = new Produto();
            p.setNome(nome);
            return produtoRepository.save(p).getId();
        });
    }

    // Cotação FINALIZADA dentro da competência de teste (mesRef) com 1 item vinculado
    // a produtoId, ofertado pelos fornecedores/preços dados (1 oferta por fornecedor).
    private void cotacaoComOfertas(UUID produtoId, java.util.Map<UUID, String> precoPorFornecedor) {
        comoTenant(tenantAId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Variação Preço Test");
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(mesRef.atDay(15).atStartOfDay(VariacaoPrecoService.FUSO_COMPETENCIA).toOffsetDateTime());
            UUID cotacaoId = cotacaoRepository.save(c).getId();

            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("Item Variação Preço Test");
            cp.setQuantidade(new BigDecimal("1.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            UUID itemId = cotacaoProdutoRepository.save(cp).getId();

            precoPorFornecedor.forEach((fornecedorId, preco) -> {
                CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
                cpf.setCotacaoProdutoId(itemId);
                cpf.setFornecedorId(fornecedorId);
                cpf.setTextoOriginal("Oferta de teste");
                cpf.setPrecoInformado(new BigDecimal(preco));
                cpf.setPrecoUnitarioCalculado(new BigDecimal(preco));
                cpf.setSemEstoque(false);
                cpf.setStatus(StatusItem.OK);
                cpfRepository.save(cpf);
            });
            return null;
        });
    }

    private VariacaoPrecoResponse topSpread(YearMonth mes) {
        return comoTenant(tenantAId, () -> variacaoPrecoService.topSpread(mes));
    }

    // ── 1. Produto com 2 fornecedores calcula spread corretamente ──────────────

    @Test
    void produto_com_dois_fornecedores_calcula_spread_percentual() {
        UUID fBarato = criarFornecedor("Fornecedor Barato");
        UUID fCaro = criarFornecedor("Fornecedor Caro");
        UUID produtoId = criarProduto("Detergente 500ml");
        cotacaoComOfertas(produtoId, java.util.Map.of(fBarato, "2.00", fCaro, "3.00"));

        VariacaoPrecoResponse r = topSpread(mesRef);

        assertEquals(1, r.produtos().size());
        assertEquals(1, r.totalProdutosComparados());
        var item = r.produtos().get(0);
        assertEquals(produtoId, item.produtoId());
        assertEquals("Detergente 500ml", item.nomeProduto());
        assertEquals(0, new BigDecimal("2.00").compareTo(item.menorPreco()));
        assertEquals(0, new BigDecimal("3.00").compareTo(item.maiorPreco()));
        // spread = (3-2)/2*100 = 50%.
        assertEquals(0, new BigDecimal("50.00").compareTo(item.spreadPct()));
        assertEquals("Fornecedor Barato", item.fornecedorMenorNome());
        assertEquals("Fornecedor Caro", item.fornecedorMaiorNome());
    }

    // ── 2. Regra de ouro: produto com só 1 fornecedor não entra no painel ──────

    @Test
    void produto_com_apenas_um_fornecedor_e_excluido() {
        UUID fornecedorId = criarFornecedor("Fornecedor Único");
        UUID produtoId = criarProduto("Produto Sem Competição");
        cotacaoComOfertas(produtoId, java.util.Map.of(fornecedorId, "5.00"));

        VariacaoPrecoResponse r = topSpread(mesRef);

        assertEquals(0, r.produtos().size());
        assertEquals(0, r.totalProdutosComparados());
    }

    // ── 3. Regra de ouro: nunca mistura competências (mês diferente é ignorado) ──

    @Test
    void oferta_de_outro_mes_nao_entra_na_competencia_pesquisada() {
        UUID fBarato = criarFornecedor("Fornecedor Barato");
        UUID fCaro = criarFornecedor("Fornecedor Caro");
        UUID produtoId = criarProduto("Produto Mês Errado");
        cotacaoComOfertas(produtoId, java.util.Map.of(fBarato, "2.00", fCaro, "3.00"));

        VariacaoPrecoResponse rMesSeguinte = topSpread(mesRef.plusMonths(1));

        assertEquals(0, rMesSeguinte.produtos().size(),
                "Oferta finalizada em agosto não pode aparecer numa consulta de setembro");
    }

    // ── 4. Top N ordena por spread desc ─────────────────────────────────────────

    @Test
    void produtos_sao_ordenados_por_spread_desc() {
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");
        UUID produtoSpreadBaixo = criarProduto("Produto Spread Baixo");
        UUID produtoSpreadAlto = criarProduto("Produto Spread Alto");
        cotacaoComOfertas(produtoSpreadBaixo, java.util.Map.of(f1, "10.00", f2, "11.00")); // 10%
        cotacaoComOfertas(produtoSpreadAlto, java.util.Map.of(f1, "10.00", f2, "20.00")); // 100%

        VariacaoPrecoResponse r = topSpread(mesRef);

        assertEquals(2, r.produtos().size());
        assertEquals(produtoSpreadAlto, r.produtos().get(0).produtoId());
        assertEquals(produtoSpreadBaixo, r.produtos().get(1).produtoId());
    }
}
