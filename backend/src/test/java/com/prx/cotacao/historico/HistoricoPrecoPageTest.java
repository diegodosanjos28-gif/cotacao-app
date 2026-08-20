package com.prx.cotacao.historico;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.historico.dto.HistoricoPrecoPageResponse;
import com.prx.cotacao.historico.dto.HistoricoPrecoProdutoResponse;
import com.prx.cotacao.historico.service.HistoricoPrecoService;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HistoricoPrecoService#historico(org.springframework.data.domain.Pageable, String)}
 * — a paginação em si (por produto, sempre por nome, ignorando qualquer sort vindo do
 * cliente) e o filtro por {@code q}. A agregação de pontos de referência por produto já
 * é coberta em {@link HistoricoPrecoServiceTest} (página única, grande o bastante pra
 * caber tudo) — aqui o foco é totalElements/totalPages/ordem/filtro.
 */
@SpringBootTest
@ActiveProfiles("dev")
class HistoricoPrecoPageTest {

    @Autowired private HistoricoPrecoService historicoPrecoService;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;

    @BeforeEach
    void criarTenant() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Paginação Histórico Test");
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
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantAId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantAId);
            return null;
        });
        TenantContext.clear();
    }

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantAId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private void criarProduto(String nome) {
        comoTenant(() -> {
            Produto p = new Produto();
            p.setNome(nome);
            return produtoRepository.save(p).getId();
        });
    }

    private HistoricoPrecoPageResponse historico(int page, int size, String q) {
        return comoTenant(() -> historicoPrecoService.historico(PageRequest.of(page, size), q));
    }

    // ── 1. totalElements/totalPages corretos com mais produtos que o tamanho da página ──

    @Test
    void pagina_com_mais_produtos_que_o_tamanho_da_pagina_calcula_totalElements_e_totalPages_corretamente() {
        criarProduto("Arroz Branco 5kg");
        criarProduto("Batata Inglesa 1kg");
        criarProduto("Café Torrado 500g");
        criarProduto("Detergente Neutro 500ml");
        criarProduto("Feijão Preto 1kg");

        HistoricoPrecoPageResponse resposta = historico(0, 2, null);

        assertEquals(5, resposta.pagina().getTotalElements());
        assertEquals(3, resposta.pagina().getTotalPages()); // ceil(5/2)
        assertEquals(2, resposta.pagina().getContent().size());
    }

    // ── 2. Ordem sempre por nome ascendente, mesmo ignorando sort do cliente, e produto
    //       sem histórico aparece na página certa por ordem alfabética ─────────────

    @Test
    void produtos_vem_ordenados_por_nome_ascendente_e_produto_sem_historico_aparece_na_pagina_certa() {
        // Insere fora de ordem alfabética de propósito.
        criarProduto("Zucchini 1kg");
        criarProduto("Arroz Branco 5kg");
        criarProduto("Molho de Tomate 340g"); // nenhum tem cotação — todos "sem histórico"

        HistoricoPrecoPageResponse pagina0 = historico(0, 2, null);
        List<HistoricoPrecoProdutoResponse> conteudoPagina0 = pagina0.pagina().getContent();
        assertEquals(2, conteudoPagina0.size());
        assertEquals("Arroz Branco 5kg", conteudoPagina0.get(0).nomeProduto());
        assertEquals("Molho de Tomate 340g", conteudoPagina0.get(1).nomeProduto());
        // Produto nunca cotado ainda aparece (pontos vazios), só na ordem alfabética certa.
        assertTrue(conteudoPagina0.get(0).pontos().isEmpty());
        assertTrue(conteudoPagina0.get(1).pontos().isEmpty());

        HistoricoPrecoPageResponse pagina1 = historico(1, 2, null);
        List<HistoricoPrecoProdutoResponse> conteudoPagina1 = pagina1.pagina().getContent();
        assertEquals(1, conteudoPagina1.size());
        assertEquals("Zucchini 1kg", conteudoPagina1.get(0).nomeProduto());
    }

    // ── 3. Busca por q filtra corretamente (case-insensitive, substring) ────────

    @Test
    void busca_por_q_filtra_produtos_por_substring_case_insensitive() {
        criarProduto("Arroz Branco 5kg");
        criarProduto("Arroz Integral 1kg");
        criarProduto("Feijão Preto 1kg");

        HistoricoPrecoPageResponse resposta = historico(0, 20, "arroz");

        List<HistoricoPrecoProdutoResponse> conteudo = resposta.pagina().getContent();
        assertEquals(2, resposta.pagina().getTotalElements());
        assertTrue(conteudo.stream().anyMatch(p -> p.nomeProduto().equals("Arroz Branco 5kg")));
        assertTrue(conteudo.stream().anyMatch(p -> p.nomeProduto().equals("Arroz Integral 1kg")));
        assertTrue(conteudo.stream().noneMatch(p -> p.nomeProduto().equals("Feijão Preto 1kg")));
    }

    // ── 4. q em branco/nulo não filtra — devolve o catálogo inteiro (paginado) ──

    @Test
    void busca_com_q_em_branco_nao_filtra_devolve_catalogo_inteiro() {
        criarProduto("Arroz Branco 5kg");
        criarProduto("Feijão Preto 1kg");

        HistoricoPrecoPageResponse comQNulo = historico(0, 20, null);
        HistoricoPrecoPageResponse comQEmBranco = historico(0, 20, "   ");

        assertEquals(2, comQNulo.pagina().getTotalElements());
        assertEquals(2, comQEmBranco.pagina().getTotalElements());
    }

    // ── 5. Pageable com sort explícito vindo do cliente é ignorado — sempre nome asc ──

    @Test
    void sort_pedido_pelo_cliente_e_ignorado_ordenacao_e_sempre_por_nome() {
        criarProduto("Zucchini 1kg");
        criarProduto("Arroz Branco 5kg");

        // Pede sort descendente por nome — o serviço deve sobrescrever com nome asc.
        HistoricoPrecoPageResponse resposta = comoTenant(() ->
                historicoPrecoService.historico(PageRequest.of(0, 20, Sort.by("nome").descending()), null));

        List<HistoricoPrecoProdutoResponse> conteudo = resposta.pagina().getContent();
        assertEquals("Arroz Branco 5kg", conteudo.get(0).nomeProduto());
        assertEquals("Zucchini 1kg", conteudo.get(1).nomeProduto());
    }
}
