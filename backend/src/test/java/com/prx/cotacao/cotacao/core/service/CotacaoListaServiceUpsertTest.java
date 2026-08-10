package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

/**
 * Regressão do Fix 2 (produtos duplicados ao reenviar a lista):
 * {@link CotacaoListaService#processarLista} deve fazer upsert por produtoId
 * (quando reconciliado com o catálogo) ou por textoOriginal (quando ainda não
 * reconciliado), preservando id/ordem originais em vez de inserir linhas novas.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5433.
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoListaServiceUpsertTest {

    @Autowired private CotacaoListaService cotacaoListaService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant Lista Upsert Test");
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
            jdbc.update("DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN " +
                    "(SELECT cp.id FROM cotacao_produto cp JOIN cotacao c ON c.id = cp.cotacao_id " +
                    "WHERE c.tenant_id = ?)", tenantId);
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Upsert Test");
            c.setStatus(CotacaoStatus.RASCUNHO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private List<CotacaoProduto> itensDaCotacao(UUID cotacaoId) {
        return comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void reenviar_lista_identica_nao_duplica_linhas() {
        UUID cotacaoId = criarCotacao();
        String lista = "10un sazon legumes 60g\n2cx bombom nestle";

        List<ItemListaResponse> primeiroEnvio = comoTenant(() ->
                cotacaoListaService.processarLista(cotacaoId, lista));
        assertEquals(2, primeiroEnvio.size());

        List<CotacaoProduto> apos1 = itensDaCotacao(cotacaoId);
        assertEquals(2, apos1.size());

        List<ItemListaResponse> segundoEnvio = comoTenant(() ->
                cotacaoListaService.processarLista(cotacaoId, lista));
        assertEquals(2, segundoEnvio.size());

        List<CotacaoProduto> apos2 = itensDaCotacao(cotacaoId);
        assertEquals(2, apos2.size(),
                "Reenviar a mesma lista não deve duplicar CotacaoProduto");

        // ids preservados entre os dois envios
        List<UUID> idsApos1 = apos1.stream().map(CotacaoProduto::getId).sorted().toList();
        List<UUID> idsApos2 = apos2.stream().map(CotacaoProduto::getId).sorted().toList();
        assertEquals(idsApos1, idsApos2, "ids das linhas devem ser preservados no upsert");
    }

    @Test
    void reenvio_reflete_quantidade_e_texto_mais_recentes() {
        // Dedup por produtoId exige que o item esteja reconciliado com o catálogo —
        // sem isso, dedup só ocorre com textoOriginal IDÊNTICO (ver comentário em
        // CotacaoListaService.processarLista), então a quantidade não pode mudar no
        // texto bruto sem um match de catálogo estável por trás.
        UUID cotacaoId = criarCotacao();
        comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Sazon Legumes 60g");
            return produtoRepository.save(p);
        });

        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId, "10un sazon legumes 60g"));
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId, "25un sazon legumes 60g"));

        List<CotacaoProduto> itens = itensDaCotacao(cotacaoId);
        assertEquals(1, itens.size());
        assertEquals(new BigDecimal("25.000"), itens.get(0).getQuantidade().setScale(3));
    }

    @Test
    void reenvio_com_linha_nova_junto_de_repetidas_so_insere_a_nova() {
        UUID cotacaoId = criarCotacao();
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "10un sazon legumes 60g\n2cx bombom nestle"));

        List<CotacaoProduto> apos1 = itensDaCotacao(cotacaoId);
        assertEquals(2, apos1.size());

        List<ItemListaResponse> segundoEnvio = comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "10un sazon legumes 60g\n2cx bombom nestle\n5un arroz tipo1 5kg"));
        assertEquals(3, segundoEnvio.size());

        List<CotacaoProduto> apos2 = itensDaCotacao(cotacaoId);
        assertEquals(3, apos2.size(),
                "Número final de itens deve ser o de itens únicos, não a soma dos dois envios");

        // As duas linhas do primeiro envio continuam com o mesmo id
        List<UUID> idsOriginais = apos1.stream().map(CotacaoProduto::getId).toList();
        long preservados = apos2.stream().filter(cp -> idsOriginais.contains(cp.getId())).count();
        assertEquals(2, preservados, "As duas linhas repetidas devem ter sido atualizadas (UPDATE), não recriadas");
    }

    @Test
    void ordem_do_item_original_e_preservada_no_upsert() {
        UUID cotacaoId = criarCotacao();
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "10un sazon legumes 60g\n2cx bombom nestle\n5un arroz tipo1 5kg"));

        List<CotacaoProduto> apos1 = itensDaCotacao(cotacaoId);
        CotacaoProduto primeiraLinhaOriginal = apos1.stream()
                .filter(cp -> cp.getTextoOriginal().contains("sazon"))
                .findFirst().orElseThrow();
        Integer ordemOriginal = primeiraLinhaOriginal.getOrdem();
        assertEquals(1, ordemOriginal);

        // Reenvia com a linha do sazon (já existente) + uma linha nova antes dela
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "1un produto novo\n10un sazon legumes 60g"));

        List<CotacaoProduto> apos2 = itensDaCotacao(cotacaoId);
        CotacaoProduto sazonDepois = apos2.stream()
                .filter(cp -> cp.getId().equals(primeiraLinhaOriginal.getId()))
                .findFirst().orElseThrow();

        assertEquals(ordemOriginal, sazonDepois.getOrdem(),
                "ordem da linha original não deve mudar para 'última posição' no upsert");
    }

    @Test
    void reenvio_com_produto_ja_reconciliado_no_catalogo_faz_upsert_por_produto_id() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Sazon Legumes 60g");
            return produtoRepository.save(p).getId();
        });

        List<ItemListaResponse> primeiroEnvio = comoTenant(() ->
                cotacaoListaService.processarLista(cotacaoId, "10un sazon legumes 60g"));
        assertTrue(primeiroEnvio.get(0).matched(), "Linha deve casar com o produto do catálogo");
        assertEquals(produtoId, primeiroEnvio.get(0).produtoIdEncontrado());

        List<CotacaoProduto> apos1 = itensDaCotacao(cotacaoId);
        assertEquals(1, apos1.size());
        UUID cotacaoProdutoIdOriginal = apos1.get(0).getId();

        // Reenvio com texto ligeiramente diferente, mas que ainda casa com o mesmo produto
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId, "12un sazon legumes 60g"));

        List<CotacaoProduto> apos2 = itensDaCotacao(cotacaoId);
        assertEquals(1, apos2.size(), "Upsert por produtoId não deve duplicar mesmo com texto diferente");
        assertEquals(cotacaoProdutoIdOriginal, apos2.get(0).getId());
        assertEquals(new BigDecimal("12.000"), apos2.get(0).getQuantidade().setScale(3));
    }

    // ── listar (GET .../lista) — hidratação da lista já persistida ─────────────

    @Test
    void listar_retorna_itens_ja_persistidos_na_ordem_correta() {
        UUID cotacaoId = criarCotacao();
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "10un sazon legumes 60g\n2cx bombom nestle\n5un arroz tipo1 5kg"));

        List<ItemListaResponse> itens = comoTenant(() -> cotacaoListaService.listar(cotacaoId));

        assertEquals(3, itens.size());
        assertEquals("10un sazon legumes 60g", itens.get(0).textoOriginal());
        assertEquals("2cx bombom nestle", itens.get(1).textoOriginal());
        assertEquals("5un arroz tipo1 5kg", itens.get(2).textoOriginal());
    }

    @Test
    void listar_em_cotacao_sem_itens_retorna_lista_vazia() {
        UUID cotacaoId = criarCotacao();

        List<ItemListaResponse> itens = comoTenant(() -> cotacaoListaService.listar(cotacaoId));

        assertTrue(itens.isEmpty());
    }

    @Test
    void listar_reflete_item_novo_criado_a_partir_de_fornecedor_no_fim_da_lista() {
        // Simula o que o pacote de ajustes pós-call (B.1/ADICIONAR_A_LISTA) faz:
        // cria um CotacaoProduto novo diretamente (sem passar pelo parser da lista),
        // com ordem maior que os itens já existentes — listar() precisa devolvê-lo
        // no fim, não perdê-lo nem reordenar os itens originais.
        UUID cotacaoId = criarCotacao();
        comoTenant(() -> cotacaoListaService.processarLista(cotacaoId,
                "10un sazon legumes 60g\n2cx bombom nestle"));

        comoTenant(() -> {
            CotacaoProduto novo = new CotacaoProduto();
            novo.setCotacaoId(cotacaoId);
            novo.setTextoOriginal("Sazon Legumes 60g Light - R$ 5,49");
            novo.setQuantidade(BigDecimal.ONE);
            novo.setUnidade("un");
            novo.setOrdem(99);
            return cotacaoProdutoRepository.save(novo);
        });

        List<ItemListaResponse> itens = comoTenant(() -> cotacaoListaService.listar(cotacaoId));

        assertEquals(3, itens.size());
        assertEquals("Sazon Legumes 60g Light - R$ 5,49", itens.get(2).textoOriginal(),
                "Item criado a partir do fornecedor deve aparecer no fim da lista");
    }

    // ── temRespostaFornecedorConfirmada (Prompt 12 — trava de edição do grid) ───

    @Test
    void listar_e_processarLista_marcam_temRespostaFornecedorConfirmada_quando_ha_cpf_vinculado() {
        UUID cotacaoId = criarCotacao();
        List<ItemListaResponse> primeiroEnvio = comoTenant(() ->
                cotacaoListaService.processarLista(cotacaoId, "10un sazon legumes 60g\n2cx bombom nestle"));
        assertTrue(primeiroEnvio.stream().noneMatch(ItemListaResponse::temRespostaFornecedorConfirmada),
                "Nenhum item recém-criado deve ter resposta de fornecedor confirmada");

        UUID itemComRespostaId = primeiroEnvio.get(0).id();
        UUID fornecedorId = comoTenant(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome("Fornecedor Teste Upsert");
            return fornecedorRepository.save(f).getId();
        });
        comoTenant(() -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemComRespostaId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("10un sazon legumes 60g R$5,00");
            cpf.setPrecoInformado(new BigDecimal("5.00"));
            cpf.setPrecoUnitarioCalculado(new BigDecimal("5.00"));
            cpf.setStatus(StatusItem.OK);
            return cpfRepository.save(cpf);
        });

        List<ItemListaResponse> viaListar = comoTenant(() -> cotacaoListaService.listar(cotacaoId));
        ItemListaResponse itemListado = viaListar.stream()
                .filter(i -> i.id().equals(itemComRespostaId)).findFirst().orElseThrow();
        assertTrue(itemListado.temRespostaFornecedorConfirmada(),
                "listar() deve refletir que o item já tem resposta de fornecedor confirmada");
        assertTrue(viaListar.stream().filter(i -> !i.id().equals(itemComRespostaId))
                        .allMatch(i -> !i.temRespostaFornecedorConfirmada()),
                "Os demais itens, sem cpf vinculado, não devem ser marcados");

        List<ItemListaResponse> viaProcessarListaNovamente = comoTenant(() ->
                cotacaoListaService.processarLista(cotacaoId, "10un sazon legumes 60g\n2cx bombom nestle"));
        ItemListaResponse itemReprocessado = viaProcessarListaNovamente.stream()
                .filter(i -> i.id().equals(itemComRespostaId)).findFirst().orElseThrow();
        assertTrue(itemReprocessado.temRespostaFornecedorConfirmada(),
                "processarLista() (upsert) também deve refletir a resposta de fornecedor já confirmada");
    }
}
