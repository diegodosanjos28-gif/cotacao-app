package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ConfirmarRespostaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemConferenciaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.service.CotacaoFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.service.FornecedorRespostaService;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressão do bug corrigido por {@code ItemBaseCatalogoResolver}: item adicionado
 * manualmente na tela de Entrada de Dados vinculando um produto JÁ existente do
 * catálogo (via {@code ProdutoAutocomplete}) grava
 * {@code cotacao_produto.texto_original = ""} ({@code CotacaoProdutoItemService
 * .adicionar}, linhas 170/196). Antes da correção, os dois pontos que montam a "lista
 * base" de matching ({@link com.prx.cotacao.cotacao.respostafornecedor.processor
 * .RespostaFornecedorCoreService#processar} e {@link ConfirmacaoRespostaService
 * #confirmar}) usavam esse texto vazio cegamente como nome de referência — texto vazio
 * nunca dá match, então o item virava "extra" (não encontrado na lista base) mesmo
 * corretamente cadastrado e vinculado.
 *
 * <p>Cobre os dois consumidores que recomputam esse pipeline de forma independente
 * (preview via {@link FornecedorRespostaService#gerarPreview} e persistência via
 * {@link ConfirmacaoRespostaService#confirmar}), e a prioridade
 * {@code texto_original > nome do catálogo} quando os dois existem e divergem (fluxo de
 * colar lista, onde {@code texto_original} é sempre preenchido e não pode ser
 * silenciosamente trocado pelo nome do catálogo).
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ItemBaseCatalogoResolverRegressaoTest {

    @Autowired private FornecedorRespostaService fornecedorRespostaService;
    @Autowired private ConfirmacaoRespostaService confirmacaoRespostaService;
    @Autowired private CotacaoFornecedorService cotacaoFornecedorService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
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
            t.setNomeFantasia("Tenant ItemBaseCatalogoResolver Test");
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
            jdbc.update("DELETE FROM cotacao_fornecedor WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN " +
                    "(SELECT id FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?))", tenantId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers (mesmo padrão de ConfirmacaoRespostaServiceTest) ────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação ItemBaseCatalogoResolver Test");
            c.setStatus(CotacaoStatus.EM_ANDAMENTO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID criarFornecedor(String nome) {
        return comoTenant(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    private UUID criarProdutoCatalogo(String nome) {
        return comoTenant(() -> {
            Produto p = new Produto();
            p.setNome(nome);
            return produtoRepository.save(p).getId();
        });
    }

    /** Replica exatamente o que CotacaoProdutoItemService.adicionar grava para item
     * manual vinculado a produto já existente do catálogo: texto_original = "". */
    private UUID adicionarItemBaseVinculadoAoCatalogoSemTextoOriginal(UUID cotacaoId, UUID produtoId, int ordem) {
        return comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("");
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private UUID adicionarItemBaseComTextoOriginalEProduto(UUID cotacaoId, String textoOriginal, UUID produtoId, int ordem) {
        return comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal(textoOriginal);
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void adicionarFornecedorAsCotacao(UUID cotacaoId, UUID fornecedorId) {
        comoTenant(() -> cotacaoFornecedorService.adicionar(
                cotacaoId, new com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest(fornecedorId, null)));
    }

    private PreviewRespostaResponse preview(UUID cotacaoId, UUID fornecedorId, String texto) {
        return comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
    }

    private List<ItemRespostaResponse> confirmar(UUID cotacaoId, UUID fornecedorId, String texto) {
        return comoTenant(() -> confirmacaoRespostaService.confirmar(
                cotacaoId, fornecedorId, new ConfirmarRespostaRequest(texto, List.of())));
    }

    private List<CotacaoProdutoFornecedor> linhasPersistidas(UUID cotacaoId, UUID fornecedorId) {
        return comoTenant(() -> cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId));
    }

    // ── 1. Bug em si (preview) ───────────────────────────────────────────────────

    @Test
    void item_vinculado_ao_catalogo_sem_texto_original_e_encontrado_no_preview_pelo_nome_do_produto() {
        UUID produtoId = criarProdutoCatalogo("Arroz Especial 1kg");
        UUID cotacaoId = criarCotacao();
        UUID itemBaseId = adicionarItemBaseVinculadoAoCatalogoSemTextoOriginal(cotacaoId, produtoId, 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Item Vinculado Sem Texto");
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        PreviewRespostaResponse resultado = preview(cotacaoId, fornecedorId, "Arroz Especial 1kg - R$ 18,90");

        // Antes da correção: texto_original="" nunca casa com nada -> a linha do
        // fornecedor vira EXTRA_ITEM (itemBaseId null) e o item base (sem candidato)
        // nem aparece na Conferência (decisão F). Depois da correção: o resolver cai
        // para Produto.nome e o match acontece normalmente.
        assertEquals(1, resultado.itens().size(),
                "Deve haver só o item casado, não uma entrada extra de 'não encontrado'");
        ItemConferenciaResponse item = resultado.itens().get(0);
        assertEquals(itemBaseId, item.itemBaseId(),
                "A linha do fornecedor deve casar com o item base vinculado ao produto do catálogo");
        assertEquals("Arroz Especial 1kg", item.nomeItemBase());
        assertEquals(StatusConferencia.OK, item.status());
        assertFalse(item.motivos().contains(MotivoConferencia.EXTRA_ITEM),
                "Item corretamente cadastrado não pode ser classificado como não encontrado na lista base");
    }

    // ── 2. Bug em si (confirmação/persistência) ──────────────────────────────────

    @Test
    void item_vinculado_ao_catalogo_sem_texto_original_e_persistido_na_confirmacao() {
        UUID produtoId = criarProdutoCatalogo("Feijao Carioca 1kg");
        UUID cotacaoId = criarCotacao();
        UUID itemBaseId = adicionarItemBaseVinculadoAoCatalogoSemTextoOriginal(cotacaoId, produtoId, 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Confirma Item Vinculado");
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, "Feijao Carioca 1kg - R$ 7,50");

        assertEquals(1, resultado.size(),
                "ConfirmacaoRespostaService.confirmar recomputa o mesmo pipeline do preview de forma independente");
        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size());
        assertEquals(itemBaseId, persistidas.get(0).getCotacaoProdutoId());
        assertEquals(new BigDecimal("7.50"), persistidas.get(0).getPrecoInformado());
    }

    // ── 3. Prioridade texto_original > nome do catálogo (preview) ───────────────

    @Test
    void texto_original_preenchido_tem_prioridade_sobre_nome_do_catalogo_no_preview() {
        // produtoId vinculado com nome de catálogo BEM diferente do texto_original —
        // se o resolver invertesse a prioridade (ou ignorasse texto_original quando
        // produtoId também está presente), o matching aconteceria contra o nome
        // errado. Medidas diferentes (1kg vs 350ml) evitam qualquer bônus de
        // "mesma medida" do MatchingProdutoService mascarar o resultado.
        UUID produtoId = criarProdutoCatalogo("Refrigerante Fanta Laranja 350ml");
        UUID cotacaoId = criarCotacao();
        UUID itemBaseId = adicionarItemBaseComTextoOriginalEProduto(
                cotacaoId, "Suco de Uva Integral 1L", produtoId, 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Prioridade Texto Original");
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // 3a. Resposta que menciona o texto_original -> deve casar.
        PreviewRespostaResponse comTextoOriginal =
                preview(cotacaoId, fornecedorId, "Suco de Uva Integral 1L - R$ 12,90");
        assertEquals(1, comTextoOriginal.itens().size());
        ItemConferenciaResponse itemCasado = comTextoOriginal.itens().get(0);
        assertEquals(itemBaseId, itemCasado.itemBaseId());
        assertEquals("Suco de Uva Integral 1L", itemCasado.nomeItemBase(),
                "Nome de referência deve ser o texto_original, não o nome do catálogo");
        assertEquals(StatusConferencia.OK, itemCasado.status());

        // 3b. Resposta que menciona o nome do CATÁLOGO (não o texto_original) -> não
        // deve casar com o item base; vira item extra (não encontrado).
        PreviewRespostaResponse comNomeCatalogo =
                preview(cotacaoId, fornecedorId, "Refrigerante Fanta Laranja 350ml - R$ 5,00");
        assertEquals(1, comNomeCatalogo.itens().size());
        ItemConferenciaResponse itemNaoCasado = comNomeCatalogo.itens().get(0);
        assertNull(itemNaoCasado.itemBaseId(),
                "Nome do catálogo não pode ser usado como referência quando texto_original está preenchido");
        assertTrue(itemNaoCasado.motivos().contains(MotivoConferencia.EXTRA_ITEM));
    }

    // ── 4. Prioridade texto_original > nome do catálogo (confirmação) ───────────

    @Test
    void texto_original_preenchido_tem_prioridade_sobre_nome_do_catalogo_na_confirmacao() {
        UUID produtoId = criarProdutoCatalogo("Refrigerante Guarana 2L");
        UUID cotacaoId = criarCotacao();
        UUID itemBaseId = adicionarItemBaseComTextoOriginalEProduto(
                cotacaoId, "Achocolatado em Po 400g", produtoId, 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Prioridade Texto Original Confirmar");
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ItemRespostaResponse> resultado =
                confirmar(cotacaoId, fornecedorId, "Achocolatado em Po 400g - R$ 9,90");

        assertEquals(1, resultado.size());
        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size());
        assertEquals(itemBaseId, persistidas.get(0).getCotacaoProdutoId());
        assertEquals("Achocolatado em Po 400g - R$ 9,90", persistidas.get(0).getTextoOriginal());
    }
}
