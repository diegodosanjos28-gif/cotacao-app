package com.prx.cotacao.catalogo;

import com.prx.cotacao.catalogo.dto.ProdutoRequest;
import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.catalogo.service.ProdutoService;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProdutoService} — sem teste dedicado até este PR de paginação. Cobre
 * {@code buscar(q, pageable)} (paginação/filtro do endpoint de autocomplete) e o método
 * novo {@code buscarPorIds} (resolução de nome bounded por uma lista de IDs conhecidos,
 * usado por {@code GridProdutosSection.recarregar()} no frontend). O isolamento de
 * tenant do repositório em si (findAll/buscarPorNome) já é coberto em
 * {@link com.prx.cotacao.MultiTenantIsolationTest} — aqui o foco é a camada de serviço e
 * o método novo, incluindo um teste de RLS dedicado pra buscarPorIds por ser código
 * novo e de risco (findAllById sem filtro explícito de tenant no service, dependendo só
 * do Hibernate filter).
 */
@SpringBootTest
@ActiveProfiles("dev")
class ProdutoServiceTest {

    @Autowired private ProdutoService produtoService;
    @Autowired private ProdutoRepository produtoRepository;
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
            a.setNomeFantasia("Tenant A - ProdutoService Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - ProdutoService Test");
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
            jdbc.update("DELETE FROM produto WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

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

    // ── buscar(q, pageable) ──────────────────────────────────────────────────

    @Test
    void buscar_sem_q_pagina_o_catalogo_inteiro_do_tenant() {
        criarProduto(tenantAId, "Arroz Branco 5kg");
        criarProduto(tenantAId, "Feijão Preto 1kg");
        criarProduto(tenantAId, "Café Torrado 500g");

        Page<Produto> pagina = comoTenant(tenantAId, () -> produtoService.buscar(null, PageRequest.of(0, 2)));

        assertEquals(3, pagina.getTotalElements());
        assertEquals(2, pagina.getContent().size());
        assertEquals(2, pagina.getTotalPages());
    }

    @Test
    void buscar_com_q_filtra_por_substring_case_insensitive() {
        criarProduto(tenantAId, "Arroz Branco 5kg");
        criarProduto(tenantAId, "Feijão Preto 1kg");

        Page<Produto> pagina = comoTenant(tenantAId, () -> produtoService.buscar("ARROZ", PageRequest.of(0, 20)));

        assertEquals(1, pagina.getTotalElements());
        assertEquals("Arroz Branco 5kg", pagina.getContent().get(0).getNome());
    }

    @Test
    void buscar_com_q_em_branco_e_tratado_como_sem_filtro() {
        criarProduto(tenantAId, "Arroz Branco 5kg");
        criarProduto(tenantAId, "Feijão Preto 1kg");

        Page<Produto> pagina = comoTenant(tenantAId, () -> produtoService.buscar("   ", PageRequest.of(0, 20)));

        assertEquals(2, pagina.getTotalElements());
    }

    @Test
    void buscar_nao_retorna_produtos_de_outro_tenant() {
        criarProduto(tenantAId, "Produto Tenant A");
        criarProduto(tenantBId, "Produto Tenant B");

        Page<Produto> paginaA = comoTenant(tenantAId, () -> produtoService.buscar(null, PageRequest.of(0, 20)));

        assertEquals(1, paginaA.getTotalElements());
        assertEquals("Produto Tenant A", paginaA.getContent().get(0).getNome());
    }

    // ── buscarPorIds ─────────────────────────────────────────────────────────

    @Test
    void buscarPorIds_resolve_apenas_os_ids_pedidos_ignorando_paginacao() {
        UUID id1 = criarProduto(tenantAId, "Arroz Branco 5kg");
        UUID id2 = criarProduto(tenantAId, "Feijão Preto 1kg");
        criarProduto(tenantAId, "Café Torrado 500g"); // não pedido — não deve vir na resposta

        List<Produto> resultado = comoTenant(tenantAId, () -> produtoService.buscarPorIds(List.of(id1, id2)));

        assertEquals(2, resultado.size());
        assertTrue(resultado.stream().anyMatch(p -> p.getId().equals(id1)));
        assertTrue(resultado.stream().anyMatch(p -> p.getId().equals(id2)));
    }

    @Test
    void buscarPorIds_com_lista_vazia_retorna_lista_vazia() {
        List<Produto> resultado = comoTenant(tenantAId, () -> produtoService.buscarPorIds(List.of()));

        assertTrue(resultado.isEmpty());
    }

    @Test
    void buscarPorIds_nao_resolve_produto_de_outro_tenant_mesmo_pedindo_o_id_explicitamente() {
        UUID idDoTenantA = criarProduto(tenantAId, "Produto Tenant A");

        // Tenant B pede explicitamente o ID de um produto do tenant A — RLS (Hibernate
        // filter em findAllById) deve simplesmente não devolver nada, não vazar o nome.
        List<Produto> resultadoParaTenantB = comoTenant(tenantBId, () -> produtoService.buscarPorIds(List.of(idDoTenantA)));

        assertTrue(resultadoParaTenantB.isEmpty(),
                "buscarPorIds não pode resolver um produtoId de outro tenant, mesmo pedido explicitamente");
    }

    // ── atualizar ────────────────────────────────────────────────────────────

    @Test
    void atualizar_so_sobrescreve_campos_informados_no_request() {
        UUID id = criarProduto(tenantAId, "Arroz Branco 5kg");
        comoTenant(tenantAId, () -> {
            Produto p = produtoRepository.findById(id).orElseThrow();
            p.setMarca("Marca Original");
            p.setUnidadePadrao("un");
            return produtoRepository.save(p);
        });

        // Request só informa nome — os demais campos (null no request) não devem apagar
        // marca/unidadePadrao já salvos.
        Produto atualizado = comoTenant(tenantAId, () ->
                produtoService.atualizar(id, new ProdutoRequest("Arroz Branco 5kg Editado", null, null, null, null)));

        assertEquals("Arroz Branco 5kg Editado", atualizado.getNome());
        assertEquals("Marca Original", atualizado.getMarca());
        assertEquals("un", atualizado.getUnidadePadrao());
    }
}
