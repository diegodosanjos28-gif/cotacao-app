package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.cotacao.core.dto.CotacaoAnteriorCursorPage;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CotacaoAnterioresService#paginar} — carrossel "Cotações anteriores" da
 * landing da Entrada de Dados, mesma mecânica de cursor/keyset de
 * {@code EconomiaCarrosselService} (ver
 * {@link com.prx.cotacao.cotacao.comparativo.service.EconomiaCarrosselServiceTest}
 * como referência de padrão usada aqui).
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoAnterioresServiceTest {

    @Autowired private CotacaoAnterioresService cotacaoAnterioresService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - CotacaoAnterioresService Test");
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

    private <T> T comoTenantA(Supplier<T> fn) {
        TenantContext.set(tenantAId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarFornecedor(String nome) {
        return comoTenantA(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    private UUID criarCotacaoSimples(CotacaoStatus status, OffsetDateTime finalizadaEm) {
        return comoTenantA(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Anteriores Test " + status);
            c.setStatus(status);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(finalizadaEm);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID criarCotacaoFinalizadaComItemEOferta(OffsetDateTime finalizadaEm, UUID fornecedorId) {
        return comoTenantA(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Anteriores Test FINALIZADA");
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(finalizadaEm);
            UUID cotacaoId = cotacaoRepository.save(c).getId();

            CotacaoProduto cp1 = new CotacaoProduto();
            cp1.setCotacaoId(cotacaoId);
            cp1.setTextoOriginal("Item 1");
            cp1.setQuantidade(new BigDecimal("1.000"));
            cp1.setUnidade("un");
            cp1.setOrdem(1);
            UUID item1 = cotacaoProdutoRepository.save(cp1).getId();

            CotacaoProduto cp2 = new CotacaoProduto();
            cp2.setCotacaoId(cotacaoId);
            cp2.setTextoOriginal("Item 2");
            cp2.setQuantidade(new BigDecimal("1.000"));
            cp2.setUnidade("un");
            cp2.setOrdem(2);
            cotacaoProdutoRepository.save(cp2); // sem oferta — não conta em itensCotados

            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(item1);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta");
            cpf.setPrecoInformado(new BigDecimal("4.00"));
            cpf.setPrecoUnitarioCalculado(new BigDecimal("4.00"));
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            cpfRepository.save(cpf);

            return cotacaoId;
        });
    }

    private CotacaoAnteriorCursorPage paginar(String cursor, int size) {
        return comoTenantA(() -> cotacaoAnterioresService.paginar(cursor, size));
    }

    // ── 1. Paginação básica: hasMore / nextCursor / totalElements ───────────

    @Test
    void paginar_ordena_por_finalizada_em_desc_e_pagina_corretamente_com_hasMore_e_nextCursor() {
        UUID fornecedorId = criarFornecedor("Fornecedor Anteriores");
        OffsetDateTime agora = OffsetDateTime.now();
        UUID c1 = criarCotacaoFinalizadaComItemEOferta(agora.minusDays(2), fornecedorId);
        UUID c2 = criarCotacaoFinalizadaComItemEOferta(agora.minusDays(1), fornecedorId);
        UUID c3 = criarCotacaoFinalizadaComItemEOferta(agora, fornecedorId);

        CotacaoAnteriorCursorPage pagina1 = paginar(null, 2);

        assertEquals(2, pagina1.items().size());
        assertEquals(c3, pagina1.items().get(0).id(), "Mais recente primeiro");
        assertEquals(c2, pagina1.items().get(1).id());
        assertTrue(pagina1.hasMore());
        assertNotNull(pagina1.nextCursor());
        assertEquals(3, pagina1.totalElements());

        CotacaoAnteriorCursorPage pagina2 = paginar(pagina1.nextCursor(), 2);
        assertEquals(1, pagina2.items().size());
        assertEquals(c1, pagina2.items().get(0).id());
        assertFalse(pagina2.hasMore());
        assertNull(pagina2.nextCursor());
        assertEquals(3, pagina2.totalElements());
    }

    // ── 2. Agregados por cotação: itens da lista base / cotados / fornecedores ──

    @Test
    void paginar_agregados_da_cotacao_batem_com_itens_e_fornecedores_reais() {
        UUID fornecedorId = criarFornecedor("Fornecedor Anteriores Agregado");
        UUID cotacaoId = criarCotacaoFinalizadaComItemEOferta(OffsetDateTime.now(), fornecedorId);

        CotacaoAnteriorCursorPage pagina = paginar(null, 10);

        assertEquals(1, pagina.items().size());
        var item = pagina.items().get(0);
        assertEquals(cotacaoId, item.id());
        assertEquals(2, item.itensListaBase(), "2 itens na lista base (com e sem oferta)");
        assertEquals(1, item.itensCotados(), "só 1 item tem oferta OK");
        assertEquals(1, item.fornecedoresCount());
    }

    // ── 3. Cursor malformado ──────────────────────────────────────────────────

    @Test
    void paginar_cursor_malformado_lanca_illegal_argument() {
        assertThrows(IllegalArgumentException.class, () -> paginar("isso-nao-e-um-cursor-valido", 10));
    }

    @Test
    void paginar_cursor_com_uuid_invalido_lanca_illegal_argument() {
        assertThrows(IllegalArgumentException.class,
                () -> paginar("2026-08-20T10:00:00Z_nao-e-um-uuid", 10));
    }

    // ── 4. Só FINALIZADA aparece ──────────────────────────────────────────────

    @Test
    void paginar_so_inclui_cotacoes_finalizada_outros_status_nunca_aparecem() {
        criarCotacaoSimples(CotacaoStatus.RASCUNHO, null);
        criarCotacaoSimples(CotacaoStatus.EM_ANDAMENTO, null);
        criarCotacaoSimples(CotacaoStatus.CANCELADA, OffsetDateTime.now());
        UUID fornecedorId = criarFornecedor("Fornecedor Anteriores Status");
        UUID finalizadaId = criarCotacaoFinalizadaComItemEOferta(OffsetDateTime.now(), fornecedorId);

        CotacaoAnteriorCursorPage pagina = paginar(null, 10);

        assertEquals(1, pagina.items().size());
        assertEquals(finalizadaId, pagina.items().get(0).id());
        assertEquals(1, pagina.totalElements());
    }

    // ── 5. Teto de size aplicado ──────────────────────────────────────────────

    @Test
    void paginar_teto_de_size_e_aplicado_size_absurdo_nao_estoura_e_fica_limitado_a_100() {
        UUID fornecedorId = criarFornecedor("Fornecedor Anteriores Teto");
        OffsetDateTime base = OffsetDateTime.now();
        for (int i = 0; i < 105; i++) {
            criarCotacaoFinalizadaComItemEOferta(base.minusMinutes(i), fornecedorId);
        }

        CotacaoAnteriorCursorPage pagina = paginar(null, 99999);

        assertEquals(100, pagina.items().size(), "size deve ser limitado a 100 mesmo pedindo 99999");
        assertTrue(pagina.hasMore());
        assertEquals(105, pagina.totalElements());
    }

    @Test
    void paginar_size_menor_que_1_e_tratado_como_1() {
        UUID fornecedorId = criarFornecedor("Fornecedor Anteriores Size Zero");
        criarCotacaoFinalizadaComItemEOferta(OffsetDateTime.now(), fornecedorId);
        criarCotacaoFinalizadaComItemEOferta(OffsetDateTime.now().minusDays(1), fornecedorId);

        CotacaoAnteriorCursorPage pagina = paginar(null, 0);

        assertEquals(1, pagina.items().size(), "size=0 deve virar 1 (Math.max(size, 1))");
        assertTrue(pagina.hasMore());
    }
}
