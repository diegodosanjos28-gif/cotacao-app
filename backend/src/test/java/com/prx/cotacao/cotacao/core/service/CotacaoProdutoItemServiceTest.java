package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.dto.AdicionarItemCotacaoRequest;
import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
import com.prx.cotacao.cotacao.core.dto.EditarItemCotacaoRequest;
import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
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
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.comparativo.service.ComparativoService;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

/**
 * Cobertura de {@link CotacaoProdutoItemService} (remover/editar item de
 * "Produtos já adicionados") e do campo {@code produtoId} exposto por
 * {@link ComparativoService#comparativo}. Segue o mesmo padrão de
 * {@link CotacaoListaServiceUpsertTest}: Postgres real (perfil dev), tenant criado
 * manualmente por teste, sem mocks.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoProdutoItemServiceTest {

    @Autowired private CotacaoProdutoItemService cotacaoProdutoItemService;
    @Autowired private ComparativoService comparativoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID usuarioId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant Item Service Test");
            t.setStatus(TenantStatus.TRIAL);
            tenantId = tenantRepository.save(t).getId();

            Usuario u = new Usuario();
            u.setTenantId(tenantId);
            u.setEmail("item-service-test-" + UUID.randomUUID() + "@prx-test.com");
            u.setSenhaHash("hash-nao-importa");
            u.setPapel(Papel.OPERADOR_CLIENTE);
            usuarioId = usuarioRepository.save(u).getId();
            return null;
        });
        TenantContext.clear();

        // CotacaoProdutoItemService.remover chama currentUser.usuarioId() (grava
        // removido_por) — setup de auth fake, mesmo padrão de CotacaoAjusteManualServiceTest.
        TenantDetails details = new TenantDetails(tenantId.toString(), "OPERADOR_CLIENTE");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                usuarioId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERADOR_CLIENTE")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
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
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
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

    private void comoTenantVoid(Runnable fn) {
        comoTenant(() -> { fn.run(); return null; });
    }

    private <T> T comoAdmin(Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private <T> T comoOutroTenant(UUID outroTenantId, Supplier<T> fn) {
        TenantContext.set(outroTenantId.toString());
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
            c.setTitulo("Cotação Item Service Test");
            c.setStatus(status);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID criarItem(UUID cotacaoId, String texto, BigDecimal quantidade, String unidade) {
        return comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal(texto);
            cp.setQuantidade(quantidade);
            cp.setUnidade(unidade);
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private UUID criarFornecedor() {
        return comoTenant(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome("Fornecedor Teste");
            return fornecedorRepository.save(f).getId();
        });
    }

    /** Simula uma resposta de fornecedor já confirmada (via ConfirmacaoRespostaService normalmente). */
    private void vincularRespostaFornecedor(UUID cotacaoProdutoId, UUID fornecedorId) {
        comoTenantVoid(() -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(cotacaoProdutoId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("10un sazon legumes 60g R$5,00");
            cpf.setPrecoInformado(new BigDecimal("5.00"));
            cpf.setPrecoUnitarioCalculado(new BigDecimal("5.00"));
            cpf.setStatus(StatusItem.OK);
            cpfRepository.save(cpf);
        });
    }

    // ── remover ──────────────────────────────────────────────────────────────

    @Test
    void remover_item_sem_resposta_de_fornecedor_faz_soft_delete_nao_hard_delete() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoId, itemId));

        List<CotacaoProduto> restantes = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertTrue(restantes.isEmpty(), "Item soft-deleted não deve aparecer na consulta de itens vivos");

        CotacaoProduto fisico = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertNotNull(fisico.getRemovidoEm(), "Linha deve continuar existindo fisicamente no banco (soft delete, não hard delete)");
    }

    @Test
    void remover_item_com_resposta_de_fornecedor_confirmada_agora_faz_soft_delete() {
        // Prompt 12: remover() passou a ser incondicional — item com resposta de
        // fornecedor confirmada não bloqueia mais a remoção (ConflictException), e sim
        // soft-deleta normalmente. A linha de cotacao_produto_fornecedor vinculada é
        // deliberadamente preservada (órfã), não tocada por este método.
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");
        UUID fornecedorId = criarFornecedor();
        vincularRespostaFornecedor(itemId, fornecedorId);

        comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoId, itemId));

        CotacaoProduto atualizado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertNotNull(atualizado.getRemovidoEm(), "Item com resposta de fornecedor confirmada agora deve ser soft-deleted, não bloqueado");

        List<CotacaoProduto> vivos = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertTrue(vivos.isEmpty(), "Item soft-deleted não deve mais aparecer entre os itens vivos");

        // cotacao_produto_fornecedor vinculado continua intacto (órfão), preservado para auditoria.
        long countCpf = comoTenant(() -> cpfRepository.findByCotacaoProdutoId(itemId).size());
        assertEquals(1, countCpf, "Linha de resposta de fornecedor deve permanecer intacta, mesmo órfã");
    }

    @Test
    void remover_item_ja_removido_lanca_not_found() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoId, itemId));

        assertThrows(ResourceNotFoundException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoId, itemId)));
    }

    @Test
    void remover_item_de_cotacao_diferente_da_url_lanca_not_found() {
        UUID cotacaoA = criarCotacao();
        UUID cotacaoB = criarCotacao();
        UUID itemDaCotacaoB = criarItem(cotacaoB, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        assertThrows(ResourceNotFoundException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoA, itemDaCotacaoB)));

        // linha original continua intacta
        List<CotacaoProduto> restantes = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoB));
        assertEquals(1, restantes.size());
    }

    @Test
    void remover_item_de_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = criarCotacao(CotacaoStatus.FINALIZADA);
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        assertThrows(ConflictException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoId, itemId)));

        List<CotacaoProduto> restantes = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertEquals(1, restantes.size());
    }

    // ── editar ───────────────────────────────────────────────────────────────

    @Test
    void editar_quantidade_e_unidade_sem_resposta_de_fornecedor_persiste_os_novos_valores() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("25.500"), "cx", null, null);
        comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request));

        CotacaoProduto atualizado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertEquals(new BigDecimal("25.500"), atualizado.getQuantidade().setScale(3));
        assertEquals("cx", atualizado.getUnidade());
    }

    @Test
    void editar_item_com_resposta_de_fornecedor_confirmada_lanca_conflict_e_nao_altera() {
        // Prompt 12, spec 3.3 — "trava de edição pós-conferência": editar() passou a
        // bloquear (ConflictException) quando já existe cotacao_produto_fornecedor
        // vinculado, invertendo o comportamento anterior (que permitia editar mesmo com
        // resposta confirmada). Excluir continua permitido (vira soft-delete).
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");
        UUID fornecedorId = criarFornecedor();
        vincularRespostaFornecedor(itemId, fornecedorId);

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("30.000"), "pct", null, null);
        assertThrows(ConflictException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request)));

        CotacaoProduto inalterado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertEquals(new BigDecimal("10.000"), inalterado.getQuantidade().setScale(3));
        assertEquals("un", inalterado.getUnidade());
    }

    @Test
    void editar_item_de_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = criarCotacao(CotacaoStatus.FINALIZADA);
        UUID itemId = criarItem(cotacaoId, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", null, null);
        assertThrows(ConflictException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request)));

        CotacaoProduto inalterado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertEquals(new BigDecimal("10.000"), inalterado.getQuantidade().setScale(3));
    }

    @Test
    void editar_item_de_cotacao_diferente_da_url_lanca_not_found() {
        UUID cotacaoA = criarCotacao();
        UUID cotacaoB = criarCotacao();
        UUID itemDaCotacaoB = criarItem(cotacaoB, "10un sazon legumes 60g", new BigDecimal("10.000"), "un");

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", null, null);
        assertThrows(ResourceNotFoundException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoA, itemDaCotacaoB, request)));
    }

    // ── editar: produtoId (tela Ajuste de Lista) ────────────────────────────────

    @Test
    void editar_com_produtoId_do_mesmo_tenant_atualiza_o_match() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "xyz produto sem padrao nenhum", new BigDecimal("1.000"), "un");
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Produto Correto");
            return produtoRepository.save(p).getId();
        });

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", produtoId, null);
        comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request));

        CotacaoProduto atualizado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertEquals(produtoId, atualizado.getProdutoId());
    }

    @Test
    void editar_com_nomeProdutoLivre_sem_match_cria_produto_novo_e_associa() {
        // Mesmo caso de uso de "+ usar como novo produto" na adição manual (Prompt 12),
        // agora pra um item que já existe no grid mas veio sem produto identificado
        // (ex.: linha colada que o parser não reconheceu).
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "xyz produto sem padrao nenhum", new BigDecimal("1.000"), "un");
        long produtosAntes = comoTenant(() -> produtoRepository.findAll().size());

        EditarItemCotacaoRequest request =
                new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", null, "Zzqx Produto Editar Livre 999");
        comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request));

        long produtosDepois = comoTenant(() -> produtoRepository.findAll().size());
        assertEquals(produtosAntes + 1, produtosDepois, "Nome sem match no catálogo deve criar um Produto novo");

        CotacaoProduto atualizado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertNotNull(atualizado.getProdutoId());
        Produto criado = comoTenant(() -> produtoRepository.findById(atualizado.getProdutoId()).orElseThrow());
        assertEquals("Zzqx Produto Editar Livre 999", criado.getNome());
        // textoOriginal (auditoria da linha colada) não é sobrescrito pelo nome do produto.
        assertEquals("xyz produto sem padrao nenhum", atualizado.getTextoOriginal());
    }

    @Test
    void editar_com_nomeProdutoLivre_ja_vivo_na_cotacao_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Produto Ja Vivo Editar");
            return produtoRepository.save(p).getId();
        });
        comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("1 un produto ja vivo editar");
            cp.setQuantidade(BigDecimal.ONE);
            cp.setUnidade("un");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });
        UUID outroItemId = criarItem(cotacaoId, "xyz sem padrao", new BigDecimal("1.000"), "un");

        EditarItemCotacaoRequest request =
                new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", null, "Produto Ja Vivo Editar");
        assertThrows(ConflictException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, outroItemId, request)));

        CotacaoProduto inalterado = comoTenant(() -> cotacaoProdutoRepository.findById(outroItemId).orElseThrow());
        assertNull(inalterado.getProdutoId(), "produtoId conflitante não pode ter sido gravado");
    }

    @Test
    void editar_com_produtoId_nulo_nao_altera_o_match_existente() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Produto Já Casado");
            return produtoRepository.save(p).getId();
        });
        UUID itemId = comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("10un sazon legumes 60g");
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("20.000"), "cx", null, null);
        comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request));

        CotacaoProduto atualizado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertEquals(produtoId, atualizado.getProdutoId(), "produtoId null no request não deve limpar o match atual");
    }

    @Test
    void editar_com_produtoId_ja_usado_por_outra_linha_da_mesma_cotacao_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Arroz Tio Joao 5kg");
            return produtoRepository.save(p).getId();
        });
        comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("5 kg arroz tio joao");
            cp.setQuantidade(new BigDecimal("5.000"));
            cp.setUnidade("kg");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });
        UUID outroItemId = criarItem(cotacaoId, "xyz produto sem padrao nenhum", new BigDecimal("1.000"), "un");

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", produtoId, null);
        assertThrows(ConflictException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, outroItemId, request)));

        CotacaoProduto inalterado = comoTenant(() -> cotacaoProdutoRepository.findById(outroItemId).orElseThrow());
        assertNull(inalterado.getProdutoId(), "produtoId conflitante não pode ter sido gravado");
    }

    @Test
    void editar_reenviando_o_mesmo_produtoId_ja_associado_a_linha_nao_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Feijao Carioca 1kg");
            return produtoRepository.save(p).getId();
        });
        UUID itemId = comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("1 un feijao carioca 1kg");
            cp.setQuantidade(new BigDecimal("1.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("2.000"), "un", produtoId, null);
        comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request));

        CotacaoProduto atualizado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertEquals(produtoId, atualizado.getProdutoId());
        assertEquals(new BigDecimal("2.000"), atualizado.getQuantidade().setScale(3));
    }

    @Test
    void editar_com_produtoId_de_outro_tenant_lanca_not_found_e_nao_altera_a_linha() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "xyz produto sem padrao nenhum", new BigDecimal("1.000"), "un");

        UUID outroTenantId = comoAdmin(() -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Outro Tenant");
            t.setStatus(TenantStatus.TRIAL);
            return tenantRepository.save(t).getId();
        });
        UUID produtoDeOutroTenant = comoOutroTenant(outroTenantId, () -> {
            Produto p = new Produto();
            p.setNome("Produto De Outro Tenant");
            return produtoRepository.save(p).getId();
        });

        EditarItemCotacaoRequest request =
                new EditarItemCotacaoRequest(new BigDecimal("1.000"), "un", produtoDeOutroTenant, null);
        assertThrows(ResourceNotFoundException.class,
                () -> comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request)));

        CotacaoProduto inalterado = comoTenant(() -> cotacaoProdutoRepository.findById(itemId).orElseThrow());
        assertNull(inalterado.getProdutoId(), "produtoId cross-tenant não pode ter sido gravado");

        comoAdmin(() -> {
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", outroTenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", outroTenantId);
            return null;
        });
    }

    // ── adicionar (Prompt 12 — botão "+ Adicionar Produto") ─────────────────────

    @Test
    void adicionar_com_produtoId_existente_cria_item() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Produto Já Cadastrado");
            return produtoRepository.save(p).getId();
        });

        AdicionarItemCotacaoRequest request =
                new AdicionarItemCotacaoRequest(produtoId, null, new BigDecimal("5.000"), "un", null);
        ItemListaResponse resposta = comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request));

        assertNotNull(resposta.id());
        assertEquals("", resposta.textoOriginal());
        assertEquals(new BigDecimal("5.000"), resposta.quantidade().setScale(3));
        assertEquals("un", resposta.unidade());
        assertEquals(produtoId, resposta.produtoIdEncontrado());
        assertTrue(resposta.matched());
        assertFalse(resposta.temRespostaFornecedorConfirmada(), "Item recém-criado nunca pode ter resposta confirmada");

        CotacaoProduto persistido = comoTenant(() -> cotacaoProdutoRepository.findById(resposta.id()).orElseThrow());
        assertEquals(cotacaoId, persistido.getCotacaoId());
        assertEquals(produtoId, persistido.getProdutoId());
        assertNull(persistido.getRemovidoEm());
    }

    @Test
    void adicionar_com_textoOriginal_informado_persiste_a_anotacao_livre() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Produto Com Anotacao");
            return produtoRepository.save(p).getId();
        });

        AdicionarItemCotacaoRequest request = new AdicionarItemCotacaoRequest(
                produtoId, null, BigDecimal.ONE, "un", "combinar marca com fornecedor X");
        ItemListaResponse resposta = comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request));

        assertEquals("combinar marca com fornecedor X", resposta.textoOriginal());
        CotacaoProduto persistido = comoTenant(() -> cotacaoProdutoRepository.findById(resposta.id()).orElseThrow());
        assertEquals("combinar marca com fornecedor X", persistido.getTextoOriginal());
    }

    @Test
    void adicionar_com_nomeProdutoLivre_sem_match_cria_produto_novo() {
        UUID cotacaoId = criarCotacao();
        long produtosAntes = comoTenant(() -> produtoRepository.findAll().size());

        AdicionarItemCotacaoRequest request = new AdicionarItemCotacaoRequest(
                null, "Zzqx Produto Nunca Visto 999", new BigDecimal("2.000"), "un", null);
        ItemListaResponse resposta = comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request));

        long produtosDepois = comoTenant(() -> produtoRepository.findAll().size());
        assertEquals(produtosAntes + 1, produtosDepois, "Nome sem match no catálogo deve criar um Produto novo");

        assertNotNull(resposta.produtoIdEncontrado());
        Produto criado = comoTenant(() -> produtoRepository.findById(resposta.produtoIdEncontrado()).orElseThrow());
        assertEquals("Zzqx Produto Nunca Visto 999", criado.getNome());

        CotacaoProduto persistido = comoTenant(() -> cotacaoProdutoRepository.findById(resposta.id()).orElseThrow());
        assertEquals(criado.getId(), persistido.getProdutoId());
    }

    @Test
    void adicionar_com_produtoId_de_outro_tenant_lanca_not_found() {
        UUID cotacaoId = criarCotacao();

        UUID outroTenantId = comoAdmin(() -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Outro Tenant Adicionar");
            t.setStatus(TenantStatus.TRIAL);
            return tenantRepository.save(t).getId();
        });
        UUID produtoDeOutroTenant = comoOutroTenant(outroTenantId, () -> {
            Produto p = new Produto();
            p.setNome("Produto De Outro Tenant Adicionar");
            return produtoRepository.save(p).getId();
        });

        AdicionarItemCotacaoRequest request =
                new AdicionarItemCotacaoRequest(produtoDeOutroTenant, null, BigDecimal.ONE, "un", null);
        assertThrows(ResourceNotFoundException.class,
                () -> comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request)));

        List<CotacaoProduto> itens = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertTrue(itens.isEmpty(), "Nenhum item deve ter sido criado para um produtoId cross-tenant");

        comoAdmin(() -> {
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", outroTenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", outroTenantId);
            return null;
        });
    }

    @Test
    void adicionar_com_produtoId_ja_vivo_na_cotacao_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Arroz Tio Joao 5kg Adicionar");
            return produtoRepository.save(p).getId();
        });
        comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("5 kg arroz tio joao");
            cp.setQuantidade(new BigDecimal("5.000"));
            cp.setUnidade("kg");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });

        AdicionarItemCotacaoRequest request = new AdicionarItemCotacaoRequest(produtoId, null, BigDecimal.ONE, "un", null);
        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request)));

        List<CotacaoProduto> itens = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertEquals(1, itens.size(), "Não deve ter criado uma segunda linha para o mesmo produtoId já vivo");
    }

    @Test
    void adicionar_com_produtoId_de_item_soft_deletado_sucede() {
        // Valida o novo índice único parcial (V25): um produtoId liberado por soft
        // delete pode ser reaproveitado por uma nova linha viva na mesma cotação.
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Feijao Carioca 1kg Reuso");
            return produtoRepository.save(p).getId();
        });
        UUID itemAntigoId = comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("1 un feijao carioca 1kg");
            cp.setQuantidade(BigDecimal.ONE);
            cp.setUnidade("un");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });
        comoTenantVoid(() -> cotacaoProdutoItemService.remover(cotacaoId, itemAntigoId));

        AdicionarItemCotacaoRequest request = new AdicionarItemCotacaoRequest(produtoId, null, new BigDecimal("3.000"), "un", null);
        ItemListaResponse resposta = comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request));

        assertNotNull(resposta.id());
        assertNotEquals(itemAntigoId, resposta.id());
        assertEquals(produtoId, resposta.produtoIdEncontrado());

        List<CotacaoProduto> vivos = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertEquals(1, vivos.size());
        assertEquals(resposta.id(), vivos.get(0).getId());

        CotacaoProduto antigo = comoTenant(() -> cotacaoProdutoRepository.findById(itemAntigoId).orElseThrow());
        assertNotNull(antigo.getRemovidoEm(), "Linha antiga soft-deletada continua existindo, só não é mais 'viva'");
    }

    @Test
    void adicionar_em_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = criarCotacao(CotacaoStatus.FINALIZADA);

        AdicionarItemCotacaoRequest request =
                new AdicionarItemCotacaoRequest(null, "Produto Qualquer Finalizada", BigDecimal.ONE, "un", null);
        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request)));

        List<CotacaoProduto> itens = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        assertTrue(itens.isEmpty());
    }

    @Test
    void adicionar_sem_produtoId_e_sem_nomeProdutoLivre_lanca_conflict() {
        UUID cotacaoId = criarCotacao();

        AdicionarItemCotacaoRequest request = new AdicionarItemCotacaoRequest(null, null, BigDecimal.ONE, "un", null);
        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request)));
    }

    @Test
    void adicionar_com_produtoId_e_nomeProdutoLivre_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Produto Ambiguo");
            return produtoRepository.save(p).getId();
        });

        AdicionarItemCotacaoRequest request =
                new AdicionarItemCotacaoRequest(produtoId, "Nome Livre Também Informado", BigDecimal.ONE, "un", null);
        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoProdutoItemService.adicionar(cotacaoId, request)));
    }

    // ── ComparativoService.comparativo() — campo produtoId ─────────────────────

    @Test
    void comparativo_item_com_produto_reconciliado_retorna_produtoId_preenchido() {
        UUID cotacaoId = criarCotacao();
        UUID produtoId = comoTenant(() -> {
            Produto p = new Produto();
            p.setNome("Sazon Legumes 60g");
            return produtoRepository.save(p).getId();
        });
        UUID itemId = comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setProdutoId(produtoId);
            cp.setTextoOriginal("10un sazon legumes 60g");
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(1);
            return cotacaoProdutoRepository.save(cp).getId();
        });

        List<ComparativoItemResponse> resultado = comoTenant(() -> comparativoService.comparativo(cotacaoId));

        assertEquals(1, resultado.size());
        ComparativoItemResponse item = resultado.get(0);
        assertEquals(itemId, item.cotacaoProdutoId());
        assertEquals(produtoId, item.produtoId());
        assertEquals("Sazon Legumes 60g", item.nomeProduto());
    }

    @Test
    void comparativo_item_sem_match_retorna_produtoId_nulo_e_nome_sem_quantidade_e_unidade() {
        UUID cotacaoId = criarCotacao();
        String textoOriginal = "15un sazon legumes 60g nao catalogado";
        UUID itemId = criarItem(cotacaoId, textoOriginal, new BigDecimal("15.000"), "un");

        List<ComparativoItemResponse> resultado = comoTenant(() -> comparativoService.comparativo(cotacaoId));

        assertEquals(1, resultado.size());
        ComparativoItemResponse item = resultado.get(0);
        assertEquals(itemId, item.cotacaoProdutoId());
        assertNull(item.produtoId());
        // nomeProduto não repete a quantidade/unidade já expostas nos campos próprios
        // (item.quantidade()/item.unidade()) — evita duplicação visual no frontend.
        assertEquals("sazon legumes 60g nao catalogado", item.nomeProduto());
    }

    // Bug: editar quantidade/unidade de um item sem match no catálogo não refletia na
    // exibição, porque nomeProduto usava texto_original cru — snapshot imutável de
    // auditoria que nunca é reescrito por CotacaoProdutoItemService.editar. O
    // comparativo precisa compor nome (sem qtd/unidade) + os valores ATUAIS de
    // quantidade/unidade, não os que estavam embutidos no texto colado originalmente.
    @Test
    void comparativo_reflete_quantidade_e_unidade_editadas_mesmo_sem_match_no_catalogo() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = criarItem(cotacaoId, "4un detergente neutro 500ml", new BigDecimal("4.000"), "un");

        EditarItemCotacaoRequest request = new EditarItemCotacaoRequest(new BigDecimal("10.000"), "cx", null, null);
        comoTenantVoid(() -> cotacaoProdutoItemService.editar(cotacaoId, itemId, request));

        List<ComparativoItemResponse> resultado = comoTenant(() -> comparativoService.comparativo(cotacaoId));

        assertEquals(1, resultado.size());
        ComparativoItemResponse item = resultado.get(0);
        assertNull(item.produtoId());
        assertEquals("detergente neutro 500ml", item.nomeProduto());
        assertEquals(new BigDecimal("10.000"), item.quantidade().setScale(3));
        assertEquals("cx", item.unidade());
    }
}
