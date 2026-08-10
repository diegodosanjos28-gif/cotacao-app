package com.prx.cotacao.cotacao.mapacompra.service;

import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.DistribuicaoFornecedor;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.ItemDistribuicao;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.shared.tenant.TenantDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.service.CotacaoService;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

/**
 * Testes de CotacaoAjusteManualService: mover/remover/restaurar item no Mapa de
 * Compra, e a aplicação desses ajustes por cima do resultado dos 3 algoritmos em
 * MapaCompraService.gerar() (campos ajustadoManualmente/itensRemovidosManualmente/
 * temAjustesManuais do DTO).
 *
 * Pré-requisito: Postgres local acessível (mesmo perfil "dev" — ver
 * MapaCompraServiceTest/EmbalagemSnapshotIsolationTest pro mesmo padrão de setup).
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoAjusteManualServiceTest {

    @Autowired private CotacaoAjusteManualService ajusteService;
    @Autowired private MapaCompraService mapaCompraService;
    @Autowired private CotacaoService cotacaoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID outroTenantId;
    private final UUID usuarioFakeId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        outroTenantId = UUID.randomUUID();

        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Ajuste Manual Test', 'TRIAL')",
                    tenantId);
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Ajuste Manual Test B', 'TRIAL')",
                    outroTenantId);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, 'teste-ajuste-manual@prx.com', 'hash_fake', 'OPERADOR_CLIENTE', true)
                    """, usuarioFakeId, tenantId);
            return null;
        });
        TenantContext.clear();

        // CotacaoAjusteManualService.mover/remover/restaurar chamam currentUser.usuarioId()
        // (grava criado_por) — setup de auth fake, mesmo padrão de EmbalagemSnapshotIsolationTest.
        TenantDetails details = new TenantDetails(tenantId.toString(), "OPERADOR_CLIENTE");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                usuarioFakeId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERADOR_CLIENTE")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM cotacao_ajuste_manual WHERE tenant_id IN (?, ?)", tenantId, outroTenantId);
            jdbc.update("DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN " +
                    "(SELECT id FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id IN (?, ?)))", tenantId, outroTenantId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id IN (?, ?))", tenantId, outroTenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id IN (?, ?)", tenantId, outroTenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantId, outroTenantId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id IN (?, ?)", tenantId, outroTenantId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantId, outroTenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers (mesmo padrão de MapaCompraServiceTest) ─────────────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        Cotacao c = new Cotacao();
        c.setTitulo("Cotação Ajuste Manual Test " + UUID.randomUUID());
        c.setStatus(CotacaoStatus.EM_ANDAMENTO);
        c.setCanalOrigem(CanalOrigem.WEB);
        return cotacaoRepository.save(c).getId();
    }

    private UUID criarFornecedorAtivo(String nome) {
        Fornecedor f = new Fornecedor();
        f.setNome(nome);
        f.setStatus(FornecedorStatus.ATIVO);
        return fornecedorRepository.save(f).getId();
    }

    private UUID criarItem(UUID cotacaoId, String texto, String qtd, int ordem) {
        CotacaoProduto cp = new CotacaoProduto();
        cp.setCotacaoId(cotacaoId);
        cp.setTextoOriginal(texto);
        cp.setQuantidade(new BigDecimal(qtd));
        cp.setUnidade("un");
        cp.setOrdem(ordem);
        return cotacaoProdutoRepository.save(cp).getId();
    }

    private void criarOferta(UUID itemId, UUID fornecedorId, String precoUnitario) {
        CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
        cpf.setCotacaoProdutoId(itemId);
        cpf.setFornecedorId(fornecedorId);
        BigDecimal preco = new BigDecimal(precoUnitario);
        cpf.setPrecoInformado(preco);
        cpf.setPrecoUnitarioCalculado(preco);
        cpf.setStatus(StatusItem.OK);
        cpf.setSemEstoque(false);
        cpfRepository.save(cpf);
    }

    private DistribuicaoFornecedor distDe(MapaCompraResponse resp, UUID fornecedorId) {
        return resp.distribuicoes().stream()
                .filter(d -> d.fornecedorId().equals(fornecedorId))
                .findFirst().orElse(null);
    }

    private ItemDistribuicao itemDe(DistribuicaoFornecedor dist, UUID itemId) {
        if (dist == null) return null;
        return dist.itens().stream().filter(i -> i.cotacaoProdutoId().equals(itemId)).findFirst().orElse(null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // mover
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mover_aplica_override_mesmo_nao_sendo_o_mais_barato() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> {
            criarOferta(item, fA, "10.00"); // mais barato — MENOR_PRECO escolheria A
            criarOferta(item, fB, "15.00");
            return null;
        });

        comoTenant(() -> { ajusteService.mover(cotacaoId, item, fB); return null; });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertNull(itemDe(distDe(resp, fA), item), "Item não deve mais estar com o fornecedor A");
        ItemDistribuicao itemEmB = itemDe(distDe(resp, fB), item);
        assertNotNull(itemEmB, "Item deve estar com o fornecedor B por causa do ajuste manual");
        assertTrue(itemEmB.ajustadoManualmente());
        assertTrue(resp.temAjustesManuais());
    }

    @Test
    void mover_sobrevive_a_troca_de_cenario() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> {
            criarOferta(item, fA, "10.00");
            criarOferta(item, fB, "15.00");
            return null;
        });

        comoTenant(() -> { ajusteService.mover(cotacaoId, item, fB); return null; });

        for (CenarioSelecionado cenario : CenarioSelecionado.values()) {
            MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, cenario));
            assertNotNull(itemDe(distDe(resp, fB), item),
                    "Ajuste manual deve valer em " + cenario + ", não só no cenário em que foi feito");
        }
    }

    @Test
    void mover_para_fornecedor_sem_oferta_valida_lanca_illegal_argument() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B")); // nunca cotou o item
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> { criarOferta(item, fA, "10.00"); return null; });

        assertThrows(IllegalArgumentException.class,
                () -> comoTenant(() -> { ajusteService.mover(cotacaoId, item, fB); return null; }));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // remover / restaurar
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void remover_exclui_da_distribuicao_e_aparece_em_itensRemovidosManualmente_nao_em_produtosSemFornecedor() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> { criarOferta(item, fA, "10.00"); return null; });

        comoTenant(() -> { ajusteService.remover(cotacaoId, item); return null; });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertNull(itemDe(distDe(resp, fA), item));
        assertTrue(resp.produtosSemFornecedor().isEmpty(),
                "Item removido manualmente NÃO é 'sem oferta' — tem oferta válida, só foi excluído por escolha");
        assertEquals(1, resp.itensRemovidosManualmente().size());
        assertEquals(item, resp.itensRemovidosManualmente().get(0).cotacaoProdutoId());
        assertTrue(resp.temAjustesManuais());
    }

    @Test
    void remover_item_sem_nenhuma_oferta_valida_lanca_illegal_argument() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1)); // sem nenhuma oferta

        assertThrows(IllegalArgumentException.class,
                () -> comoTenant(() -> { ajusteService.remover(cotacaoId, item); return null; }));
    }

    @Test
    void restaurar_item_volta_a_seguir_o_algoritmo_do_cenario() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> {
            criarOferta(item, fA, "10.00");
            criarOferta(item, fB, "15.00");
            return null;
        });
        comoTenant(() -> { ajusteService.mover(cotacaoId, item, fB); return null; });

        comoTenant(() -> { ajusteService.restaurar(cotacaoId, item); return null; });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));
        assertNotNull(itemDe(distDe(resp, fA), item), "Depois de restaurar, item volta pro mais barato (Fornecedor A)");
        assertFalse(resp.temAjustesManuais());
    }

    @Test
    void restaurarTudo_limpa_todos_os_ajustes_da_cotacao() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "1", 2));
        comoTenant(() -> {
            criarOferta(item1, fA, "10.00");
            criarOferta(item1, fB, "15.00");
            criarOferta(item2, fA, "5.00");
            return null;
        });
        comoTenant(() -> { ajusteService.mover(cotacaoId, item1, fB); return null; });
        comoTenant(() -> { ajusteService.remover(cotacaoId, item2); return null; });

        comoTenant(() -> { ajusteService.restaurarTudo(cotacaoId); return null; });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));
        assertFalse(resp.temAjustesManuais());
        assertNotNull(itemDe(distDe(resp, fA), item1), "Item 1 volta pro mais barato");
        assertNotNull(itemDe(distDe(resp, fA), item2), "Item 2 volta a aparecer na distribuição");
        assertTrue(resp.itensRemovidosManualmente().isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cotação finalizada / isolamento
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mutacao_bloqueada_em_cotacao_finalizada() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> {
            criarOferta(item, fA, "10.00");
            criarOferta(item, fB, "15.00");
            return null;
        });
        comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertThrows(ConflictException.class,
                () -> comoTenant(() -> { ajusteService.mover(cotacaoId, item, fB); return null; }));
        assertThrows(ConflictException.class,
                () -> comoTenant(() -> { ajusteService.remover(cotacaoId, item); return null; }));
        assertThrows(ConflictException.class,
                () -> comoTenant(() -> { ajusteService.restaurarTudo(cotacaoId); return null; }));
    }

    private <T> T comoOutroTenant(Supplier<T> fn) {
        TenantContext.set(outroTenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    @Test
    void cotacao_de_outro_tenant_nao_e_encontrada() {
        UUID cotacaoOutroTenant = comoOutroTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Tenant B");
            c.setStatus(CotacaoStatus.EM_ANDAMENTO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });

        // Autenticado como tenant A, tenta mexer numa cotação que só existe no tenant B.
        assertThrows(ResourceNotFoundException.class,
                () -> comoTenant(() -> { ajusteService.restaurarTudo(cotacaoOutroTenant); return null; }));
    }
}
