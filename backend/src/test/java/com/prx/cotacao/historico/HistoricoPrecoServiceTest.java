package com.prx.cotacao.historico;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.historico.dto.HistoricoPrecoPageResponse;
import com.prx.cotacao.historico.dto.HistoricoPrecoProdutoResponse;
import com.prx.cotacao.historico.service.HistoricoPrecoService;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HistoricoPrecoService#historico} agrega, por produto do catálogo do tenant,
 * os pontos de referência de preço (menor oferta válida de cada cotação FINALIZADA em
 * que o produto apareceu), mais recente primeiro. Produto nunca cotado aparece com
 * {@code pontos: []} — a tela lista o catálogo inteiro, não só o que já tem histórico.
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555. Padrão de
 * setup espelha {@link com.prx.cotacao.MultiTenantIsolationTest} (dois tenants criados
 * a cada teste, mesmo quando só um é usado) e {@link com.prx.cotacao.cotacao.application.ComparativoServiceTest}
 * (fixtures de {@code cotacao_produto_fornecedor} persistidas direto pelo repositório,
 * sem passar pelo parser).</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class HistoricoPrecoServiceTest {

    @Autowired private HistoricoPrecoService historicoPrecoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
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
            a.setNomeFantasia("Tenant A - Histórico Preço Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Histórico Preço Test");
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

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private UUID criarCotacao(UUID tenantId, String titulo, CotacaoStatus status, OffsetDateTime finalizadaEm) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo(titulo);
            c.setStatus(status);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(finalizadaEm);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID adicionarItem(UUID tenantId, UUID cotacaoId, UUID produtoId, String textoOriginal,
                                String quantidade, String unidade, int ordem) {
        return comoTenant(tenantId, () -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal(textoOriginal);
            cp.setQuantidade(new BigDecimal(quantidade));
            cp.setUnidade(unidade);
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void criarOferta(UUID tenantId, UUID itemId, UUID fornecedorId, String preco,
                              boolean semEstoque, StatusItem status) {
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

    // Simula uma oferta status OK, com estoque, mas sem precoUnitarioCalculado —
    // cenário possível quando a Conferência ainda não resolveu o preço unitário.
    private void criarOfertaComPrecoNulo(UUID tenantId, UUID itemId, UUID fornecedorId) {
        comoTenant(tenantId, () -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta sem preço unitário calculado");
            cpf.setPrecoInformado(new BigDecimal("1.00"));
            cpf.setPrecoUnitarioCalculado(null);
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            return cpfRepository.save(cpf);
        });
    }

    // Página grande o suficiente pra caber todo o catálogo de teste de uma vez — os
    // testes deste arquivo não exercitam paginação em si (ver HistoricoPrecoPageTest
    // pra isso), só o comportamento de agregação por produto.
    private List<HistoricoPrecoProdutoResponse> historico(UUID tenantId) {
        HistoricoPrecoPageResponse resposta =
                comoTenant(tenantId, () -> historicoPrecoService.historico(PageRequest.of(0, 100), null));
        return resposta.pagina().getContent();
    }

    private HistoricoPrecoProdutoResponse entradaDoProduto(List<HistoricoPrecoProdutoResponse> resultado, UUID produtoId) {
        return resultado.stream()
                .filter(r -> r.produtoId().equals(produtoId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Produto não encontrado no histórico: " + produtoId));
    }

    // ── 1. Produto nunca cotado aparece com pontos vazios ───────────────────────

    @Test
    void produto_sem_cotacao_finalizada_aparece_com_pontos_vazios_e_referencia_nula() {
        UUID produtoId = criarProduto(tenantAId, "Feijão Preto 1kg");

        List<HistoricoPrecoProdutoResponse> resultado = historico(tenantAId);

        HistoricoPrecoProdutoResponse entrada = entradaDoProduto(resultado, produtoId);
        assertTrue(entrada.pontos().isEmpty());
        assertNull(entrada.quantidadeReferencia());
        assertNull(entrada.unidadeReferencia());
    }

    // ── 2. Exatamente 1 cotação finalizada → 1 ponto ────────────────────────────

    @Test
    void produto_com_uma_cotacao_finalizada_tem_exatamente_um_ponto() {
        UUID produtoId = criarProduto(tenantAId, "Arroz Especial 1kg");
        UUID fornecedorId = criarFornecedor(tenantAId, "Distribuidora Única");
        UUID cotacaoId = criarCotacao(tenantAId, "Cotação Única", CotacaoStatus.FINALIZADA, OffsetDateTime.now());
        UUID itemId = adicionarItem(tenantAId, cotacaoId, produtoId, "Arroz Especial 1kg", "10.000", "un", 1);
        criarOferta(tenantAId, itemId, fornecedorId, "8.00", false, StatusItem.OK);

        List<HistoricoPrecoProdutoResponse> resultado = historico(tenantAId);

        HistoricoPrecoProdutoResponse entrada = entradaDoProduto(resultado, produtoId);
        assertEquals(1, entrada.pontos().size());
        assertEquals(0, new BigDecimal("8.00").compareTo(entrada.pontos().get(0).preco()));
        assertEquals(fornecedorId, entrada.pontos().get(0).fornecedorId());
        assertEquals(cotacaoId, entrada.pontos().get(0).cotacaoId());
    }

    // ── 3. 3+ cotações finalizadas → pontos mais-recente-primeiro; referência vem do mais recente ─

    @Test
    void tres_cotacoes_finalizadas_geram_pontos_ordenados_mais_recente_primeiro() {
        UUID produtoId = criarProduto(tenantAId, "Óleo de Soja 900ml");
        UUID fornecedorId = criarFornecedor(tenantAId, "Distribuidora Alfa");
        OffsetDateTime agora = OffsetDateTime.now();

        UUID cotacao1 = criarCotacao(tenantAId, "Cotação mais antiga", CotacaoStatus.FINALIZADA, agora.minusDays(2));
        UUID item1 = adicionarItem(tenantAId, cotacao1, produtoId, "Óleo de Soja 900ml", "5.000", "cx", 1);
        criarOferta(tenantAId, item1, fornecedorId, "6.00", false, StatusItem.OK);

        UUID cotacao2 = criarCotacao(tenantAId, "Cotação intermediária", CotacaoStatus.FINALIZADA, agora.minusDays(1));
        UUID item2 = adicionarItem(tenantAId, cotacao2, produtoId, "Óleo de Soja 900ml", "10.000", "un", 2);
        criarOferta(tenantAId, item2, fornecedorId, "7.00", false, StatusItem.OK);

        UUID cotacao3 = criarCotacao(tenantAId, "Cotação mais recente", CotacaoStatus.FINALIZADA, agora);
        UUID item3 = adicionarItem(tenantAId, cotacao3, produtoId, "Óleo de Soja 900ml", "20.000", "pct", 3);
        criarOferta(tenantAId, item3, fornecedorId, "9.00", false, StatusItem.OK);

        List<HistoricoPrecoProdutoResponse> resultado = historico(tenantAId);

        HistoricoPrecoProdutoResponse entrada = entradaDoProduto(resultado, produtoId);
        assertEquals(3, entrada.pontos().size());
        assertEquals(cotacao3, entrada.pontos().get(0).cotacaoId(), "Mais recente deve vir primeiro");
        assertEquals(cotacao2, entrada.pontos().get(1).cotacaoId());
        assertEquals(cotacao1, entrada.pontos().get(2).cotacaoId(), "Mais antiga deve vir por último");

        // quantidadeReferencia/unidadeReferencia vêm do cotacao_produto do ponto mais recente (cotacao3).
        assertEquals(0, new BigDecimal("20.000").compareTo(entrada.quantidadeReferencia()));
        assertEquals("pct", entrada.unidadeReferencia());
    }

    // ── 4. Dentro de 1 cotação: escolhe a oferta válida mais barata; tie-break por fornecedorId ──

    @Test
    void seleciona_oferta_mais_barata_excluindo_sem_estoque_status_invalido_e_preco_nulo() {
        UUID produtoId = criarProduto(tenantAId, "Açúcar Cristal 1kg");
        UUID fEmpate1 = criarFornecedor(tenantAId, "Distribuidora Empate 1");
        UUID fEmpate2 = criarFornecedor(tenantAId, "Distribuidora Empate 2");
        UUID fMaisBaratoSemEstoque = criarFornecedor(tenantAId, "Distribuidora Sem Estoque");
        UUID fNaoIdentificado = criarFornecedor(tenantAId, "Distribuidora Não Identificado");
        UUID fPrecoNulo = criarFornecedor(tenantAId, "Distribuidora Preço Nulo");

        UUID cotacaoId = criarCotacao(tenantAId, "Cotação Multi-Fornecedor", CotacaoStatus.FINALIZADA, OffsetDateTime.now());
        UUID itemId = adicionarItem(tenantAId, cotacaoId, produtoId, "Açúcar Cristal 1kg", "10.000", "un", 1);

        // Mais barato (1.00) está sem estoque — deve ser excluído.
        criarOferta(tenantAId, itemId, fMaisBaratoSemEstoque, "1.00", true, StatusItem.OK);
        // Segundo mais barato (3.00) está NAO_IDENTIFICADO — deve ser excluído.
        criarOferta(tenantAId, itemId, fNaoIdentificado, "3.00", false, StatusItem.NAO_IDENTIFICADO);
        // Preço unitário nulo — deve ser excluído mesmo com status OK e com estoque.
        criarOfertaComPrecoNulo(tenantAId, itemId, fPrecoNulo);
        // Empate no menor preço válido (8.00) entre dois fornecedores.
        criarOferta(tenantAId, itemId, fEmpate1, "8.00", false, StatusItem.OK);
        criarOferta(tenantAId, itemId, fEmpate2, "8.00", false, StatusItem.OK);

        UUID vencedorEsperado = fEmpate1.toString().compareTo(fEmpate2.toString()) < 0 ? fEmpate1 : fEmpate2;

        List<HistoricoPrecoProdutoResponse> resultado = historico(tenantAId);

        HistoricoPrecoProdutoResponse entrada = entradaDoProduto(resultado, produtoId);
        assertEquals(1, entrada.pontos().size(),
                "Só 1 ponto de referência por (produto, cotação) — apesar de 5 ofertas");
        assertEquals(0, new BigDecimal("8.00").compareTo(entrada.pontos().get(0).preco()));
        assertEquals(vencedorEsperado, entrada.pontos().get(0).fornecedorId(),
                "No empate de preço, o desempate é pelo menor fornecedorId.toString()");
    }

    // ── 5. Cotação não-finalizada é excluída inteiramente, mesmo com ofertas válidas ──

    @Test
    void cotacao_em_andamento_nao_gera_ponto_de_referencia() {
        UUID produtoId = criarProduto(tenantAId, "Café Torrado 500g");
        UUID fornecedorId = criarFornecedor(tenantAId, "Distribuidora Café");
        UUID cotacaoId = criarCotacao(tenantAId, "Cotação em andamento", CotacaoStatus.EM_ANDAMENTO, null);
        UUID itemId = adicionarItem(tenantAId, cotacaoId, produtoId, "Café Torrado 500g", "10.000", "un", 1);
        criarOferta(tenantAId, itemId, fornecedorId, "15.00", false, StatusItem.OK);

        List<HistoricoPrecoProdutoResponse> resultado = historico(tenantAId);

        HistoricoPrecoProdutoResponse entrada = entradaDoProduto(resultado, produtoId);
        assertTrue(entrada.pontos().isEmpty(),
                "Cotação EM_ANDAMENTO não deve gerar ponto de referência, mesmo com oferta válida");
        assertNull(entrada.quantidadeReferencia());
        assertNull(entrada.unidadeReferencia());
    }

    // ── 6. Isolamento multi-tenant — o achado mais caro do histórico de RLS do projeto ──

    @Test
    void historico_de_um_tenant_nunca_inclui_produtos_ou_pontos_de_outro_tenant() {
        UUID produtoA = criarProduto(tenantAId, "Produto Exclusivo Tenant A");
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor Tenant A");
        UUID cotacaoA = criarCotacao(tenantAId, "Cotação Tenant A", CotacaoStatus.FINALIZADA, OffsetDateTime.now());
        UUID itemA = adicionarItem(tenantAId, cotacaoA, produtoA, "Produto Exclusivo Tenant A", "10.000", "un", 1);
        criarOferta(tenantAId, itemA, fornecedorA, "12.00", false, StatusItem.OK);

        UUID produtoB = criarProduto(tenantBId, "Produto Exclusivo Tenant B");
        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor Tenant B");
        UUID cotacaoB = criarCotacao(tenantBId, "Cotação Tenant B", CotacaoStatus.FINALIZADA, OffsetDateTime.now());
        UUID itemB = adicionarItem(tenantBId, cotacaoB, produtoB, "Produto Exclusivo Tenant B", "10.000", "un", 1);
        criarOferta(tenantBId, itemB, fornecedorB, "99.00", false, StatusItem.OK);

        List<HistoricoPrecoProdutoResponse> resultadoA = historico(tenantAId);
        assertTrue(resultadoA.stream().anyMatch(r -> r.produtoId().equals(produtoA)),
                "Tenant A deve ver o próprio produto no histórico");
        assertTrue(resultadoA.stream().noneMatch(r -> r.produtoId().equals(produtoB)),
                "Tenant A não pode ver o produto do tenant B no histórico");
        HistoricoPrecoProdutoResponse entradaA = entradaDoProduto(resultadoA, produtoA);
        assertTrue(entradaA.pontos().stream().noneMatch(p -> p.cotacaoId().equals(cotacaoB)),
                "Nenhum ponto de referência do tenant B deve vazar para o resultado do tenant A");
        assertTrue(entradaA.pontos().stream().noneMatch(p -> p.fornecedorId().equals(fornecedorB)),
                "Nenhuma oferta de fornecedor do tenant B deve vazar para o resultado do tenant A");

        List<HistoricoPrecoProdutoResponse> resultadoB = historico(tenantBId);
        assertTrue(resultadoB.stream().anyMatch(r -> r.produtoId().equals(produtoB)),
                "Tenant B deve ver o próprio produto no histórico");
        assertTrue(resultadoB.stream().noneMatch(r -> r.produtoId().equals(produtoA)),
                "Tenant B não pode ver o produto do tenant A no histórico");
    }
}
