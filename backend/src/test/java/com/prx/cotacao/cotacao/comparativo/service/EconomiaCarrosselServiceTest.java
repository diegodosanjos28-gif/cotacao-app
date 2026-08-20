package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.CotacaoFinalizadaCursorPage;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EconomiaCarrosselService#paginar} — carrossel "Cotações Registradas"
 * (Dashboard), primeira paginação por cursor/keyset do projeto (ver
 * CotacaoProdutoFornecedorRepository.buscarPaginaCarrossel).
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555. Mesmo padrão
 * de setup de {@link EconomiaResumoServiceTest}.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class EconomiaCarrosselServiceTest {

    @Autowired private EconomiaCarrosselService economiaCarrosselService;
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
            a.setNomeFantasia("Tenant A - Carrossel Test");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Carrossel Test");
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

    // finalizadaEm explícito (diferente de EconomiaResumoServiceTest, que não filtra
    // por data) — o cursor do carrossel ordena e desempata por (finalizada_em, id).
    // Uma única oferta (fornecedorBarato) quando fornecedorCaro/precoCaro vêm null —
    // UNIQUE(cotacao_produto_id, fornecedor_id) proíbe duas ofertas do mesmo
    // fornecedor pro mesmo item, por isso a segunda oferta precisa de um fornecedor
    // distinto.
    private UUID criarCotacaoFinalizadaComItem(UUID tenantId, OffsetDateTime finalizadaEm,
                                                UUID fornecedorBarato, String precoBarato,
                                                UUID fornecedorCaro, String precoCaro) {
        return comoTenant(tenantId, () -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Carrossel Test");
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(finalizadaEm);
            UUID cotacaoId = cotacaoRepository.save(c).getId();

            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal("Item Carrossel Test");
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            UUID itemId = cotacaoProdutoRepository.save(cp).getId();

            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorBarato);
            cpf.setTextoOriginal("Oferta de teste");
            cpf.setPrecoInformado(new BigDecimal(precoBarato));
            cpf.setPrecoUnitarioCalculado(new BigDecimal(precoBarato));
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            cpfRepository.save(cpf);

            if (precoCaro != null) {
                CotacaoProdutoFornecedor cpf2 = new CotacaoProdutoFornecedor();
                cpf2.setCotacaoProdutoId(itemId);
                cpf2.setFornecedorId(fornecedorCaro);
                cpf2.setTextoOriginal("Oferta de teste 2");
                cpf2.setPrecoInformado(new BigDecimal(precoCaro));
                cpf2.setPrecoUnitarioCalculado(new BigDecimal(precoCaro));
                cpf2.setSemEstoque(false);
                cpf2.setStatus(StatusItem.OK);
                cpfRepository.save(cpf2);
            }

            return cotacaoId;
        });
    }

    private CotacaoFinalizadaCursorPage paginar(UUID tenantId, String cursor, int size) {
        return comoTenant(tenantId, () -> economiaCarrosselService.paginar(cursor, size, null, null));
    }

    private CotacaoFinalizadaCursorPage paginarComPeriodo(
            UUID tenantId, String cursor, int size, LocalDate inicio, LocalDate fim) {
        return comoTenant(tenantId, () -> economiaCarrosselService.paginar(cursor, size, inicio, fim));
    }

    // ── 1. Primeira página respeita o tamanho pedido e ordena por finalizada_em DESC ──

    @Test
    void primeira_pagina_ordena_por_finalizada_em_desc_e_indica_hasMore() {
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Carrossel");
        OffsetDateTime agora = OffsetDateTime.now();
        UUID c1 = criarCotacaoFinalizadaComItem(tenantAId, agora.minusDays(2), fornecedorId, "8.00", null, null);
        UUID c2 = criarCotacaoFinalizadaComItem(tenantAId, agora.minusDays(1), fornecedorId, "8.00", null, null);
        UUID c3 = criarCotacaoFinalizadaComItem(tenantAId, agora, fornecedorId, "8.00", null, null);

        CotacaoFinalizadaCursorPage pagina1 = paginar(tenantAId, null, 2);

        assertEquals(2, pagina1.items().size());
        assertEquals(c3, pagina1.items().get(0).id(), "Mais recente primeiro");
        assertEquals(c2, pagina1.items().get(1).id());
        assertTrue(pagina1.hasMore());
        assertNotNull(pagina1.nextCursor());

        CotacaoFinalizadaCursorPage pagina2 = paginar(tenantAId, pagina1.nextCursor(), 2);
        assertEquals(1, pagina2.items().size());
        assertEquals(c1, pagina2.items().get(0).id());
        assertFalse(pagina2.hasMore());
        assertNull(pagina2.nextCursor());
    }

    // ── 2. Agregados por cotação: itens/valor/economia/pct calculados no banco ──

    @Test
    void agregados_da_cotacao_batem_com_vencedor_por_item() {
        UUID fBarato = criarFornecedor(tenantAId, "Fornecedor Barato Carrossel");
        UUID fCaro = criarFornecedor(tenantAId, "Fornecedor Caro Carrossel");
        // menor=8, maior=10, qtd=10 -> recomendado=80, economia=20, pct=20%.
        UUID cotacaoId = criarCotacaoFinalizadaComItem(tenantAId, OffsetDateTime.now(), fBarato, "8.00", fCaro, "10.00");

        CotacaoFinalizadaCursorPage pagina = paginar(tenantAId, null, 10);

        assertEquals(1, pagina.items().size());
        var item = pagina.items().get(0);
        assertEquals(cotacaoId, item.id());
        assertEquals(1, item.itensCotados());
        assertEquals(0, new BigDecimal("80.00").compareTo(item.valorTotalComprado()));
        assertEquals(0, new BigDecimal("20.00").compareTo(item.economizado()));
        assertEquals(0, new BigDecimal("20.00").compareTo(item.economizadoPct()));
        assertEquals(1, item.fornecedoresCount());
    }

    // ── 3. Isolamento multi-tenant ───────────────────────────────────────────────

    @Test
    void carrossel_de_um_tenant_nunca_inclui_cotacoes_de_outro() {
        UUID fornecedorA = criarFornecedor(tenantAId, "Fornecedor Tenant A");
        UUID fornecedorB = criarFornecedor(tenantBId, "Fornecedor Tenant B");
        criarCotacaoFinalizadaComItem(tenantAId, OffsetDateTime.now(), fornecedorA, "8.00", null, null);
        criarCotacaoFinalizadaComItem(tenantBId, OffsetDateTime.now(), fornecedorB, "5.00", null, null);

        CotacaoFinalizadaCursorPage paginaA = paginar(tenantAId, null, 10);
        assertEquals(1, paginaA.items().size());

        CotacaoFinalizadaCursorPage paginaB = paginar(tenantBId, null, 10);
        assertEquals(1, paginaB.items().size());
    }

    // ── 4. Cursor malformado é rejeitado como erro de validação (não 500) ──────

    @Test
    void cursor_malformado_lanca_illegal_argument() {
        assertThrows(IllegalArgumentException.class, () -> paginar(tenantAId, "isso-nao-e-um-cursor-valido", 10));
    }

    // ── 5. totalElements reflete o total real, não o que já foi carregado ──────

    @Test
    void totalElements_reflete_o_total_real_mesmo_com_pagina_menor_que_o_total() {
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Carrossel");
        OffsetDateTime agora = OffsetDateTime.now();
        criarCotacaoFinalizadaComItem(tenantAId, agora.minusDays(2), fornecedorId, "8.00", null, null);
        criarCotacaoFinalizadaComItem(tenantAId, agora.minusDays(1), fornecedorId, "8.00", null, null);
        criarCotacaoFinalizadaComItem(tenantAId, agora, fornecedorId, "8.00", null, null);

        CotacaoFinalizadaCursorPage pagina1 = paginar(tenantAId, null, 2);

        assertEquals(2, pagina1.items().size(), "A página só traz 2 itens...");
        assertEquals(3, pagina1.totalElements(), "...mas totalElements já reflete as 3 cotações reais, não só a leva carregada");
    }

    // ── 6. Filtro de período (inicio/fim) ───────────────────────────────────────

    @Test
    void filtro_de_periodo_exclui_cotacoes_fora_do_intervalo_e_totalElements_reflete_o_filtro() {
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Carrossel");
        // Datas bem afastadas de "agora" pra não haver risco de o teste rodar perto
        // de uma virada de mês/ano e acidentalmente cair dentro/fora do intervalo.
        OffsetDateTime dentro = OffsetDateTime.parse("2026-08-15T12:00:00Z");
        OffsetDateTime foraAntes = OffsetDateTime.parse("2026-07-31T23:59:59Z");
        // 2026-09-01T00:00:00Z equivaleria a 2026-08-31T21:00:00-03:00 em
        // America/Sao_Paulo — ainda DENTRO do período (mesmo fuso usado pelo filtro,
        // ver VariacaoPrecoService.FUSO_COMPETENCIA). Usa alguns segundos depois da
        // meia-noite BRT do dia seguinte pra garantir que fica de fato fora.
        OffsetDateTime foraDepois = OffsetDateTime.parse("2026-09-01T03:00:01Z");
        UUID cotacaoDentro = criarCotacaoFinalizadaComItem(tenantAId, dentro, fornecedorId, "8.00", null, null);
        criarCotacaoFinalizadaComItem(tenantAId, foraAntes, fornecedorId, "8.00", null, null);
        criarCotacaoFinalizadaComItem(tenantAId, foraDepois, fornecedorId, "8.00", null, null);

        CotacaoFinalizadaCursorPage pagina = paginarComPeriodo(
                tenantAId, null, 10, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertEquals(1, pagina.items().size());
        assertEquals(cotacaoDentro, pagina.items().get(0).id());
        assertEquals(1, pagina.totalElements());
    }

    @Test
    void fim_do_periodo_e_inclusivo_para_o_usuario() {
        UUID fornecedorId = criarFornecedor(tenantAId, "Fornecedor Carrossel");
        // 20h do último dia do período, no fuso America/Sao_Paulo (UTC-3) — 23h UTC,
        // ainda dentro do dia 31 se o "fim" for tratado como o fim do dia inteiro
        // (não a meia-noite UTC do dia seguinte).
        OffsetDateTime ultimoDiaNoite = OffsetDateTime.parse("2026-08-31T23:00:00Z");
        UUID cotacaoId = criarCotacaoFinalizadaComItem(tenantAId, ultimoDiaNoite, fornecedorId, "8.00", null, null);

        CotacaoFinalizadaCursorPage pagina = paginarComPeriodo(
                tenantAId, null, 10, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertEquals(1, pagina.items().size(), "31/08 23h UTC ainda é 31/08 20h em America/Sao_Paulo — deve contar como dentro do período");
        assertEquals(cotacaoId, pagina.items().get(0).id());
    }
}
