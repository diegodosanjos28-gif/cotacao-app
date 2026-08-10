package com.prx.cotacao.cotacao.mapacompra.service;

import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
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
 * Testes do snapshot congelado do Mapa de Compra (V18 —
 * cotacao.mapa_final_snapshot): a tela de uma cotação FINALIZADA passa a exibir a
 * distribuição exatamente como estava no momento da finalização, não mais um
 * recálculo ao vivo do algoritmo do cenário.
 *
 * Pré-requisito: Postgres local acessível (mesmo perfil "dev" dos demais testes de
 * integração deste módulo).
 */
@SpringBootTest
@ActiveProfiles("dev")
class MapaCompraSnapshotTest {

    @Autowired private CotacaoService cotacaoService;
    @Autowired private MapaCompraService mapaCompraService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Snapshot Test', 'TRIAL')",
                    tenantId);
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
                    "(SELECT id FROM cotacao WHERE tenant_id = ?))", tenantId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        Cotacao c = new Cotacao();
        c.setTitulo("Cotação Snapshot Test " + UUID.randomUUID());
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

    private UUID criarOferta(UUID itemId, UUID fornecedorId, String precoUnitario) {
        CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
        cpf.setCotacaoProdutoId(itemId);
        cpf.setFornecedorId(fornecedorId);
        BigDecimal preco = new BigDecimal(precoUnitario);
        cpf.setPrecoInformado(preco);
        cpf.setPrecoUnitarioCalculado(preco);
        cpf.setStatus(StatusItem.OK);
        cpf.setSemEstoque(false);
        return cpfRepository.save(cpf).getId();
    }

    private void assertMoneyEquals(String esperado, BigDecimal atual) {
        assertNotNull(atual, "Valor esperado " + esperado + " mas foi null");
        assertEquals(0, new BigDecimal(esperado).compareTo(atual),
                () -> "Esperado " + esperado + " mas foi " + atual);
    }

    @Test
    void finalizar_grava_snapshot_do_mapa_no_momento_da_finalizacao() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Único"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        comoTenant(() -> { criarOferta(item, f, "10.00"); return null; });

        comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));
        assertNotNull(recarregada.getMapaFinalSnapshot(), "Snapshot deveria ter sido gravado ao finalizar");
        assertTrue(recarregada.getMapaFinalSnapshot().contains("20.0"),
                "Snapshot deveria conter o total (2 * 10.00 = 20.00) em algum formato numérico");
    }

    @Test
    void mapa_de_cotacao_finalizada_retorna_snapshot_congelado_ignorando_mudanca_posterior_de_preco() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Único"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        UUID ofertaId = comoTenant(() -> criarOferta(item, f, "10.00"));

        comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        MapaCompraResponse antesDaMudanca = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));
        assertMoneyEquals("20.00", antesDaMudanca.totalGeral());

        // Muda o preço da oferta DEPOIS de finalizada — a tela da cotação finalizada
        // não deve refletir essa mudança, só o snapshot congelado no momento certo.
        comoTenant(() -> {
            CotacaoProdutoFornecedor cpf = cpfRepository.findById(ofertaId).orElseThrow();
            cpf.setPrecoInformado(new BigDecimal("999.00"));
            cpf.setPrecoUnitarioCalculado(new BigDecimal("999.00"));
            cpfRepository.save(cpf);
            return null;
        });

        MapaCompraResponse depoisDaMudanca = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));
        assertMoneyEquals("20.00", depoisDaMudanca.totalGeral());
    }

    @Test
    void mapa_de_cotacao_finalizada_ignora_cenario_pedido_e_usa_o_do_snapshot() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Único"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "1", 1));
        comoTenant(() -> { criarOferta(item, f, "5.00"); return null; });

        comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        // Pede um cenário diferente do que foi persistido — deve devolver o snapshot
        // (cenario MENOR_PRECO), não recalcular MELHOR_PRAZO.
        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MELHOR_PRAZO));
        assertEquals(CenarioSelecionado.MENOR_PRECO, resp.cenario());
    }

    @Test
    void mapa_de_cotacao_finalizada_sem_snapshot_cai_no_recalculo_ao_vivo() {
        // Simula um registro finalizado antes da V18 (ou qualquer caso de snapshot
        // nulo): marca FINALIZADA diretamente no banco, sem passar por
        // CotacaoService.finalizar(), então mapa_final_snapshot fica NULL.
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Único"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item 1", "3", 1));
        comoTenant(() -> { criarOferta(item, f, "4.00"); return null; });

        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("UPDATE cotacao SET status = 'FINALIZADA', cenario_selecionado = 'MENOR_PRECO' WHERE id = ?",
                    cotacaoId);
            return null;
        });
        TenantContext.clear();

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));
        assertMoneyEquals("12.00", resp.totalGeral()); // 3 * 4.00, recalculado ao vivo
    }
}
