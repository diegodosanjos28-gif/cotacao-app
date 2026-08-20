package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComparativoService#comparativoLote} — versão em lote de
 * {@link ComparativoService#comparativo}, criada pra resolver o achado do usuário
 * 08-20: o Dashboard disparava 1 requisição HTTP (e 1 query por cotação) por linha
 * visível em duas grids ao mesmo tempo, estourando o rate limit por IP. O resultado
 * de comparativoLote(ids) pra cada id individual deve ser idêntico ao que
 * comparativo(id) retornaria chamado separadamente.
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555. Mesmo
 * padrão de setup de {@link com.prx.cotacao.historico.HistoricoPrecoServiceTest}.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class ComparativoLoteServiceTest {

    @Autowired private ComparativoService comparativoService;
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
            a.setNomeFantasia("Tenant A - Comparativo Lote Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Comparativo Lote Test");
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

    private UUID criarFornecedor(UUID tenantId, String nome) {
        return comoTenant(tenantId, () -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    private UUID criarCotacao(UUID tenantId, String titulo) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo(titulo);
            c.setStatus(CotacaoStatus.EM_ANDAMENTO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID adicionarItem(UUID tenantId, UUID cotacaoId, String textoOriginal, int ordem) {
        return comoTenant(tenantId, () -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal(textoOriginal);
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
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

    private Map<UUID, List<ComparativoItemResponse>> lote(UUID tenantId, List<UUID> cotacaoIds) {
        return comoTenant(tenantId, () -> comparativoService.comparativoLote(cotacaoIds));
    }

    private List<ComparativoItemResponse> individual(UUID tenantId, UUID cotacaoId) {
        return comoTenant(tenantId, () -> comparativoService.comparativo(cotacaoId));
    }

    // ── 1. Lista vazia de IDs — não bate no banco, devolve mapa vazio ───────────

    @Test
    void lista_vazia_devolve_mapa_vazio() {
        assertTrue(lote(tenantAId, List.of()).isEmpty());
    }

    // ── 2. Uma cotação sem nenhum item — entrada presente no mapa com lista vazia ──

    @Test
    void cotacao_sem_itens_aparece_no_mapa_com_lista_vazia() {
        UUID cotacaoId = criarCotacao(tenantAId, "Cotação Sem Itens");

        Map<UUID, List<ComparativoItemResponse>> resultado = lote(tenantAId, List.of(cotacaoId));

        assertTrue(resultado.containsKey(cotacaoId));
        assertTrue(resultado.get(cotacaoId).isEmpty());
    }

    // ── 3. Resultado do lote é idêntico ao de chamar comparativo() individualmente ──

    @Test
    void resultado_do_lote_bate_com_chamadas_individuais_para_cada_cotacao() {
        UUID fBarato = criarFornecedor(tenantAId, "Fornecedor Barato");
        UUID fCaro = criarFornecedor(tenantAId, "Fornecedor Caro");

        UUID cotacao1 = criarCotacao(tenantAId, "Cotação 1");
        UUID item1 = adicionarItem(tenantAId, cotacao1, "Arroz 5kg", 1);
        criarOferta(tenantAId, item1, fBarato, "8.00");
        criarOferta(tenantAId, item1, fCaro, "10.00");

        UUID cotacao2 = criarCotacao(tenantAId, "Cotação 2");
        UUID item2a = adicionarItem(tenantAId, cotacao2, "Feijão 1kg", 1);
        UUID item2b = adicionarItem(tenantAId, cotacao2, "Óleo 900ml", 2);
        criarOferta(tenantAId, item2a, fBarato, "5.00");
        criarOferta(tenantAId, item2b, fCaro, "7.00");

        Map<UUID, List<ComparativoItemResponse>> lote = lote(tenantAId, List.of(cotacao1, cotacao2));
        List<ComparativoItemResponse> individual1 = individual(tenantAId, cotacao1);
        List<ComparativoItemResponse> individual2 = individual(tenantAId, cotacao2);

        assertEquals(individual1, lote.get(cotacao1));
        assertEquals(individual2, lote.get(cotacao2));
        // Cotação 2 preserva a ordem dos itens (item2a antes de item2b, por `ordem`)
        // mesmo com os dois lotes intercalados na query única com IN (:cotacaoIds).
        assertEquals(2, lote.get(cotacao2).size());
        assertEquals(item2a, lote.get(cotacao2).get(0).cotacaoProdutoId());
        assertEquals(item2b, lote.get(cotacao2).get(1).cotacaoProdutoId());
    }

    // ── 4. Isolamento multi-tenant — IDs de outro tenant nunca aparecem/vazam ────

    @Test
    void lote_nunca_inclui_dados_de_cotacao_de_outro_tenant() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor Tenant A");
        UUID cotacaoA = criarCotacao(tenantAId, "Cotação Tenant A");
        UUID itemA = adicionarItem(tenantAId, cotacaoA, "Item Tenant A", 1);
        criarOferta(tenantAId, itemA, fornecedorA, "12.00");

        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor Tenant B");
        UUID cotacaoB = criarCotacao(tenantBId, "Cotação Tenant B");
        UUID itemB = adicionarItem(tenantBId, cotacaoB, "Item Tenant B", 1);
        criarOferta(tenantBId, itemB, fornecedorB, "99.00");

        // Tenant A pede o lote incluindo (de propósito) o ID da cotação do tenant B —
        // RLS deve simplesmente não devolver nada pra esse ID, não vazar o conteúdo.
        Map<UUID, List<ComparativoItemResponse>> resultado = lote(tenantAId, List.of(cotacaoA, cotacaoB));

        assertEquals(1, resultado.get(cotacaoA).size());
        assertEquals("Fornecedor Tenant A", resultado.get(cotacaoA).get(0).precosPorFornecedor().get(0).nomeFornecedor());
        assertTrue(resultado.get(cotacaoB).isEmpty(),
                "Cotação de outro tenant não deve trazer nenhum item, mesmo pedida explicitamente no lote");
    }
}
