package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.dto.ImportarTextoItemResponse;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.error.ConflictException;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * {@link CotacaoListaService#importarTexto} — modal "Colar do WhatsApp" (Prompt 12).
 * Diferente de {@link CotacaoListaService#processarLista} (coberto por
 * {@link CotacaoListaServiceUpsertTest}), este método é SEMPRE append-only: nunca faz
 * upsert por produtoId/textoOriginal — uma linha cujo produto resolvido já está vivo na
 * cotação é reportada como {@code duplicado=true} e simplesmente não é inserida (id
 * nulo), sem abortar o resto da importação.
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555. Mesmo padrão
 * de {@link CotacaoListaServiceUpsertTest}: Postgres real, tenant criado manualmente por
 * teste, sem mocks.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoListaServiceImportarTextoTest {

    @Autowired private CotacaoListaService cotacaoListaService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant Lista ImportarTexto Test");
            t.setStatus(TenantStatus.TRIAL);
            tenantId = tenantRepository.save(t).getId();
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return criarCotacao(CotacaoStatus.RASCUNHO);
    }

    private UUID criarCotacao(CotacaoStatus status) {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação ImportarTexto Test");
            c.setStatus(status);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private List<CotacaoProduto> itensVivos(UUID cotacaoId) {
        return comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
    }

    private UUID criarProduto(String nome) {
        return comoTenant(() -> {
            Produto p = new Produto();
            p.setNome(nome);
            return produtoRepository.save(p).getId();
        });
    }

    private UUID criarItemVivoComProduto(UUID cotacaoId, UUID produtoId, String textoOriginal, int ordem) {
        return comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal(textoOriginal);
            cp.setQuantidade(new BigDecimal("1.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void softDelete(UUID itemId) {
        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(itemId).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });
    }

    private List<ImportarTextoItemResponse> importar(UUID cotacaoId, String texto) {
        return comoTenant(() -> cotacaoListaService.importarTexto(cotacaoId, texto));
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void importar_a_mesma_linha_duas_vezes_cria_duas_linhas_distintas() {
        // Texto que não começa com dígito -> LinhaParseada.parseOk()==false -> nunca
        // resolve produtoId (matched=false), então a checagem de "já vivo" (que exige
        // matched==true) nunca dedup essa linha — cada importação cria uma linha nova,
        // ao contrário de processarLista (upsert-por-match), confirmando o
        // comportamento append-only de importarTexto.
        UUID cotacaoId = criarCotacao();
        String linha = "Produto Sem Padrao De Quantidade Nenhuma";

        List<ImportarTextoItemResponse> primeiro = importar(cotacaoId, linha);
        assertEquals(1, primeiro.size());
        assertFalse(primeiro.get(0).matched(), "Linha sem padrão qtd+unidade não deve casar com produto nenhum");
        assertNotNull(primeiro.get(0).id());

        List<ImportarTextoItemResponse> segundo = importar(cotacaoId, linha);
        assertEquals(1, segundo.size());
        assertFalse(segundo.get(0).duplicado(), "Sem produtoId resolvido, não há como marcar duplicado");
        assertNotNull(segundo.get(0).id());
        assertNotEquals(primeiro.get(0).id(), segundo.get(0).id());

        List<CotacaoProduto> itens = itensVivos(cotacaoId);
        assertEquals(2, itens.size(), "Reenviar a mesma linha não-matcheável duas vezes deve criar 2 linhas distintas");
    }

    @Test
    void importar_produtoId_ja_vivo_na_cotacao_marca_duplicado_e_nao_insere() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = criarProduto("Sazon Legumes 60g");
        UUID itemExistenteId = criarItemVivoComProduto(cotacaoId, produtoId, "10un sazon legumes 60g", 1);

        List<ImportarTextoItemResponse> resultado = importar(cotacaoId, "12un sazon legumes 60g");

        assertEquals(1, resultado.size());
        ImportarTextoItemResponse item = resultado.get(0);
        assertTrue(item.matched(), "Linha deve casar com o produto do catálogo");
        assertEquals(produtoId, item.produtoIdEncontrado());
        assertTrue(item.duplicado(), "Produto já vivo na cotação deve ser reportado como duplicado");
        assertNull(item.id(), "Linha duplicada não deve ter sido inserida (id nulo)");

        List<CotacaoProduto> itens = itensVivos(cotacaoId);
        assertEquals(1, itens.size(), "Não deve ter sido criada uma segunda linha para o mesmo produtoId");
        assertEquals(itemExistenteId, itens.get(0).getId());
    }

    @Test
    void importar_nao_reaproveita_item_soft_deletado_com_mesmo_texto_ou_produto() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = criarProduto("Sazon Legumes 60g Soft Delete");
        UUID itemAntigoId = criarItemVivoComProduto(cotacaoId, produtoId, "10un sazon legumes 60g soft delete", 1);
        softDelete(itemAntigoId);

        List<ImportarTextoItemResponse> resultado = importar(cotacaoId, "10un sazon legumes 60g soft delete");

        assertEquals(1, resultado.size());
        ImportarTextoItemResponse item = resultado.get(0);
        assertTrue(item.matched());
        assertEquals(produtoId, item.produtoIdEncontrado());
        assertFalse(item.duplicado(), "Produto cujo único vínculo vivo foi soft-deleted não deve contar como 'já vivo'");
        assertNotNull(item.id(), "Uma linha NOVA deve ter sido inserida, não uma atualização da soft-deleted");
        assertNotEquals(itemAntigoId, item.id());

        List<CotacaoProduto> itens = itensVivos(cotacaoId);
        assertEquals(1, itens.size(), "Só a linha nova deve aparecer como viva");
        assertEquals(item.id(), itens.get(0).getId());

        CotacaoProduto antigo = comoTenant(() -> cotacaoProdutoRepository.findById(itemAntigoId).orElseThrow());
        assertNotNull(antigo.getRemovidoEm(), "Linha antiga soft-deletada não deve ter sido tocada");
        assertEquals("10un sazon legumes 60g soft delete", antigo.getTextoOriginal());
        assertEquals(produtoId, antigo.getProdutoId());
    }

    @Test
    void importar_em_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = criarCotacao(CotacaoStatus.FINALIZADA);

        assertThrows(ConflictException.class, () -> importar(cotacaoId, "10un sazon legumes 60g"));

        List<CotacaoProduto> itens = itensVivos(cotacaoId);
        assertTrue(itens.isEmpty());
    }
}
