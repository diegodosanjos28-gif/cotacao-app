package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Catálogo "nasce do matching da lista": quando {@link CotacaoListaService#processarLista}
 * não encontra um produto do catálogo com score suficiente, cria um novo Produto em vez
 * de deixar produto_id nulo pra sempre (achado do diagnóstico de Histórico de Preços,
 * 01/08 — nada no backend criava Produto até então). Cobre os cenários que importam pra
 * essa decisão matched-vs-novo estar certa: variação de caixa/acento não deve duplicar,
 * o mesmo produto citado duas vezes na MESMA lista não deve duplicar (catálogo dinâmico
 * incremental), peso/volume diferente ou produtos genuinamente diferentes não podem se
 * fundir, e os campos extras (marca/peso/embalagem) devem ser preenchidos na criação.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5433.
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoListaServiceMatchingTest {

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
            t.setNomeFantasia("Tenant Lista Matching Test");
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

    // ── Helpers (mesmo padrão de CotacaoListaServiceUpsertTest) ────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Matching Test");
            c.setStatus(CotacaoStatus.RASCUNHO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private List<CotacaoProduto> itensDaCotacao(UUID cotacaoId) {
        return comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
    }

    private List<Produto> produtosDoTenant() {
        return comoTenant(() -> produtoRepository.findAll());
    }

    // ── Bootstrap do zero ────────────────────────────────────────────────────

    @Test
    void catalogo_vazio_cola_lista_com_itens_distintos_cria_um_produto_por_item() {
        UUID cotacaoId = criarCotacao();
        assertTrue(produtosDoTenant().isEmpty(), "catálogo deve começar vazio");

        List<ItemListaResponse> resultado = comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "10un sazon legumes 60g\n2cx bombom nestle\n5un arroz tipo1 5kg"));

        assertEquals(3, resultado.size());
        assertTrue(resultado.stream().allMatch(ItemListaResponse::matched),
                "toda linha parseável deve resolver pra algum produto (existente ou recém-criado)");
        assertTrue(resultado.stream().allMatch(i -> i.produtoIdEncontrado() != null));

        assertEquals(3, produtosDoTenant().size(), "3 itens distintos → 3 produtos novos no catálogo");
    }

    // ── Case/acento não duplicam ─────────────────────────────────────────────

    @Test
    void mesmo_produto_com_grafia_diferente_em_cotacoes_diferentes_nao_duplica_no_catalogo() {
        UUID cotacao1 = criarCotacao();
        UUID cotacao2 = criarCotacao();

        List<ItemListaResponse> envio1 = comoTenant(() ->
                cotacaoListaService.processarLista(cotacao1, "5un Leite Condensado Moça 395g"));
        List<ItemListaResponse> envio2 = comoTenant(() ->
                cotacaoListaService.processarLista(cotacao2, "8un leite condensado moça 395g"));

        assertEquals(1, produtosDoTenant().size(),
                "case diferente do mesmo produto não deve criar um segundo produto no catálogo");
        assertEquals(envio1.get(0).produtoIdEncontrado(), envio2.get(0).produtoIdEncontrado(),
                "as duas cotações devem referenciar o MESMO produto do catálogo");
    }

    // ── Duplicata dentro da MESMA lista colada (catálogo dinâmico incremental) ─

    @Test
    void mesmo_produto_citado_duas_vezes_na_mesma_lista_reaproveita_o_produto_recem_criado() {
        UUID cotacaoId = criarCotacao();

        // 2ª linha não é idêntica após normTxt (tem "Refinado" a mais) — só bate via
        // matching por token, não pelo atalho de igualdade exata do calcSimilaridade.
        // Só passa se o catálogo dinâmico incluir o produto criado pela 1ª linha ANTES
        // de processar a 2ª (senão as duas ficariam sem match e criariam 2 produtos).
        List<ItemListaResponse> resultado = comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "1un Acucar Uniao 1kg\n1un Acucar Uniao Refinado 1kg"));

        assertEquals(2, resultado.size());
        assertEquals(resultado.get(0).produtoIdEncontrado(), resultado.get(1).produtoIdEncontrado(),
                "a 2ª linha deve casar com o produto criado pela 1ª linha da mesma lista");
        assertEquals(1, produtosDoTenant().size(), "só 1 produto deve ter sido criado, não 2");
    }

    // ── Peso/volume diferente não pode fundir ───────────────────────────────

    @Test
    void mesmo_nome_com_peso_diferente_cria_dois_produtos_distintos() {
        UUID cotacaoId = criarCotacao();

        List<ItemListaResponse> resultado = comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "1un Leite Condensado 395g\n1un Leite Condensado 400g"));

        assertEquals(2, resultado.size());
        assertNotEquals(resultado.get(0).produtoIdEncontrado(), resultado.get(1).produtoIdEncontrado(),
                "395g e 400g são embalagens diferentes — não podem virar o mesmo produto");
        assertEquals(2, produtosDoTenant().size());
    }

    // ── Produtos genuinamente diferentes com o mesmo peso não podem fundir ──

    @Test
    void produtos_diferentes_com_mesmo_peso_nao_se_fundem() {
        UUID cotacaoId = criarCotacao();

        List<ItemListaResponse> resultado = comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "1un Arroz Tipo 1 5kg\n1un Feijao Carioca 5kg"));

        assertEquals(2, resultado.size());
        assertNotEquals(resultado.get(0).produtoIdEncontrado(), resultado.get(1).produtoIdEncontrado(),
                "arroz e feijão não podem virar o mesmo produto só por pesarem 5kg os dois");
        assertEquals(2, produtosDoTenant().size());

        Set<String> nomes = produtosDoTenant().stream().map(Produto::getNome).collect(Collectors.toSet());
        assertEquals(Set.of("Arroz Tipo 1 5kg", "Feijao Carioca 5kg"), nomes);
    }

    // ── Enriquecimento de campos na criação ─────────────────────────────────

    @Test
    void produto_criado_e_enriquecido_com_marca_peso_volume_e_embalagem_sugerida() {
        UUID cotacaoId = criarCotacao();

        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "2un Caixa com 12 Bombom Uniao 50g"));

        List<Produto> produtos = produtosDoTenant();
        assertEquals(1, produtos.size());
        Produto p = produtos.get(0);

        assertEquals("Caixa com 12 Bombom Uniao 50g", p.getNome(),
                "nome armazenado com grafia original, não normalizado/minúsculo");
        assertEquals("uniao", p.getMarca());
        assertEquals(0, new BigDecimal("50").compareTo(p.getPesoVolumeValor()));
        assertEquals("g", p.getPesoVolumeUnidade());
        assertEquals("un", p.getUnidadePadrao(), "unidade de compra vem do parser (2UN), não da embalagem citada no nome");
        assertEquals(12, p.getEmbalagemQtdSugerida());
    }

    // ── Reenvio depois de criado não duplica nem o produto nem o vínculo ────

    @Test
    void reenviar_a_mesma_lista_apos_criacao_nao_duplica_produto_nem_cotacao_produto() {
        UUID cotacaoId = criarCotacao();
        String lista = "3un Detergente Ype 500ml";

        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId, lista));
        assertEquals(1, produtosDoTenant().size());
        assertEquals(1, itensDaCotacao(cotacaoId).size());

        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId, lista));

        assertEquals(1, produtosDoTenant().size(), "reenvio não deve criar um segundo produto");
        assertEquals(1, itensDaCotacao(cotacaoId).size(), "reenvio não deve duplicar o CotacaoProduto");
    }
}
