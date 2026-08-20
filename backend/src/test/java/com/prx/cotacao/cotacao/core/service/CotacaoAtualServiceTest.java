package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.cotacao.core.dto.CotacaoAtualResponse;
import com.prx.cotacao.cotacao.core.dto.FornecedorRespostaResumo;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CotacaoAtualService#buscar} — card "Cotação atual" da landing da Entrada de
 * Dados: a cotação RASCUNHO/EM_ANDAMENTO mais recente do TENANT INTEIRO (não filtrada
 * por usuário criador, ver comentário na própria classe de produção).
 *
 * <p>Mesmo padrão de {@link com.prx.cotacao.cotacao.comparativo.service.EconomiaCarrosselServiceTest}
 * (Postgres local, porta 5555).</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoAtualServiceTest {

    @Autowired private CotacaoAtualService cotacaoAtualService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - CotacaoAtualService Test");
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
            jdbc.update("DELETE FROM cotacao_fornecedor WHERE tenant_id = ?", tenantAId);
            jdbc.update("DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN " +
                    "(SELECT id FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?))", tenantAId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantAId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantAId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantAId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantAId);
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

    private UUID criarUsuario() {
        return comoTenantA(() -> {
            Usuario u = new Usuario();
            u.setTenantId(tenantAId);
            u.setEmail("cotacao-atual-" + UUID.randomUUID() + "@prx-test.com");
            u.setSenhaHash("hash-nao-importa");
            u.setPapel(Papel.OPERADOR_CLIENTE);
            return usuarioRepository.save(u).getId();
        });
    }

    private UUID criarFornecedor(String nome) {
        return comoTenantA(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            return fornecedorRepository.save(f).getId();
        });
    }

    private UUID criarCotacao(CotacaoStatus status, OffsetDateTime ultimaAtividadeEm, UUID criadoPor) {
        return comoTenantA(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Atual Test " + status);
            c.setStatus(status);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setUltimaAtividadeEm(ultimaAtividadeEm);
            c.setCriadoPor(criadoPor);
            return cotacaoRepository.save(c).getId();
        });
    }

    private UUID criarItem(UUID cotacaoId, int ordem, boolean removido) {
        return comoTenantA(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal("Item " + ordem);
            cp.setQuantidade(new BigDecimal("2.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            if (removido) {
                cp.setRemovidoEm(OffsetDateTime.now());
            }
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void criarOferta(UUID itemId, UUID fornecedorId, StatusItem status) {
        comoTenantA(() -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta");
            cpf.setPrecoInformado(new BigDecimal("3.00"));
            cpf.setPrecoUnitarioCalculado(new BigDecimal("3.00"));
            cpf.setSemEstoque(false);
            cpf.setStatus(status);
            return cpfRepository.save(cpf);
        });
    }

    private void criarCotacaoFornecedor(UUID cotacaoId, UUID fornecedorId, int ordem, CotacaoFornecedorStatus status) {
        comoTenantA(() -> {
            CotacaoFornecedor cf = new CotacaoFornecedor();
            cf.setCotacaoId(cotacaoId);
            cf.setFornecedorId(fornecedorId);
            cf.setOrdem(ordem);
            cf.setStatus(status);
            return cfRepository.save(cf);
        });
    }

    private Optional<CotacaoAtualResponse> buscar() {
        return comoTenantA(cotacaoAtualService::buscar);
    }

    // ── 1. Vazio quando não há RASCUNHO/EM_ANDAMENTO ────────────────────────

    @Test
    void buscar_retorna_vazio_quando_nao_ha_cotacao_em_andamento_no_tenant() {
        UUID usuario = criarUsuario();
        criarCotacao(CotacaoStatus.FINALIZADA, OffsetDateTime.now(), usuario);
        criarCotacao(CotacaoStatus.CANCELADA, OffsetDateTime.now(), usuario);

        assertTrue(buscar().isEmpty(),
                "Só cotações FINALIZADA/CANCELADA no tenant — buscar() deve retornar vazio");
    }

    @Test
    void buscar_retorna_vazio_quando_tenant_nao_tem_nenhuma_cotacao() {
        assertTrue(buscar().isEmpty());
    }

    // ── 2. Retorna a mais recente por ultimaAtividadeEm, ignora FINALIZADA/CANCELADA ──

    @Test
    void buscar_retorna_a_mais_recente_entre_rascunho_e_em_andamento_ignorando_finalizada_e_cancelada() {
        UUID usuario = criarUsuario();
        OffsetDateTime agora = OffsetDateTime.now();
        criarCotacao(CotacaoStatus.RASCUNHO, agora.minusHours(2), usuario);
        UUID maisRecenteEmAndamento = criarCotacao(CotacaoStatus.EM_ANDAMENTO, agora.minusMinutes(5), usuario);
        // Mais recente de todas, mas FINALIZADA — não deve "vencer" a mais recente em andamento.
        criarCotacao(CotacaoStatus.FINALIZADA, agora, usuario);

        Optional<CotacaoAtualResponse> resultado = buscar();

        assertTrue(resultado.isPresent());
        assertEquals(maisRecenteEmAndamento, resultado.get().id());
    }

    // ── 3. itensListaBase / itensCotados (nível cotação) ────────────────────

    @Test
    void buscar_conta_itensListaBase_ignorando_removidos_e_itensCotados_como_itens_distintos_com_oferta_ok() {
        UUID fornecedorId = criarFornecedor("Fornecedor Contagem");
        UUID cotacaoId = criarCotacao(CotacaoStatus.EM_ANDAMENTO, OffsetDateTime.now(), criarUsuario());

        UUID item1 = criarItem(cotacaoId, 1, false);
        UUID item2 = criarItem(cotacaoId, 2, false);
        criarItem(cotacaoId, 3, false); // sem oferta nenhuma
        criarItem(cotacaoId, 4, true);  // removido — não conta em itensListaBase

        criarOferta(item1, fornecedorId, StatusItem.OK);
        criarOferta(item2, fornecedorId, StatusItem.NAO_IDENTIFICADO); // não conta em itensCotados

        CotacaoAtualResponse resposta = buscar().orElseThrow();

        assertEquals(3, resposta.itensListaBase(), "3 itens vivos (o removido não conta)");
        assertEquals(1, resposta.itensCotados(), "só item1 tem oferta OK");
    }

    // ── 4. itensCotados por fornecedor ───────────────────────────────────────

    @Test
    void buscar_conta_itensCotados_por_fornecedor_corretamente() {
        UUID fornecedorX = criarFornecedor("Fornecedor X");
        UUID fornecedorY = criarFornecedor("Fornecedor Y");
        UUID cotacaoId = criarCotacao(CotacaoStatus.EM_ANDAMENTO, OffsetDateTime.now(), criarUsuario());

        UUID item1 = criarItem(cotacaoId, 1, false);
        UUID item2 = criarItem(cotacaoId, 2, false);

        criarOferta(item1, fornecedorX, StatusItem.OK);
        criarOferta(item2, fornecedorX, StatusItem.OK);
        criarOferta(item1, fornecedorY, StatusItem.OK);

        criarCotacaoFornecedor(cotacaoId, fornecedorX, 1, CotacaoFornecedorStatus.CONFIRMADO);
        criarCotacaoFornecedor(cotacaoId, fornecedorY, 2, CotacaoFornecedorStatus.CONFIRMADO);

        CotacaoAtualResponse resposta = buscar().orElseThrow();

        FornecedorRespostaResumo resumoX = resposta.fornecedores().stream()
                .filter(f -> f.fornecedorId().equals(fornecedorX)).findFirst().orElseThrow();
        FornecedorRespostaResumo resumoY = resposta.fornecedores().stream()
                .filter(f -> f.fornecedorId().equals(fornecedorY)).findFirst().orElseThrow();

        assertEquals(2, resumoX.itensCotados());
        assertEquals(1, resumoY.itensCotados());
    }

    // ── 5. respondeuEm ────────────────────────────────────────────────────────

    @Test
    void buscar_respondeuEm_null_quando_pendente_e_preenchido_quando_ja_respondeu() {
        UUID fornecedorPendente = criarFornecedor("Fornecedor Pendente");
        UUID fornecedorProcessado = criarFornecedor("Fornecedor Processado");
        UUID fornecedorConfirmado = criarFornecedor("Fornecedor Confirmado");
        UUID cotacaoId = criarCotacao(CotacaoStatus.EM_ANDAMENTO, OffsetDateTime.now(), criarUsuario());

        criarCotacaoFornecedor(cotacaoId, fornecedorPendente, 1, CotacaoFornecedorStatus.PENDENTE);
        criarCotacaoFornecedor(cotacaoId, fornecedorProcessado, 2, CotacaoFornecedorStatus.PROCESSADO);
        criarCotacaoFornecedor(cotacaoId, fornecedorConfirmado, 3, CotacaoFornecedorStatus.CONFIRMADO);

        CotacaoAtualResponse resposta = buscar().orElseThrow();

        assertNull(resumoDe(resposta, fornecedorPendente).respondeuEm());
        assertNotNull(resumoDe(resposta, fornecedorProcessado).respondeuEm());
        assertNotNull(resumoDe(resposta, fornecedorConfirmado).respondeuEm());
    }

    private FornecedorRespostaResumo resumoDe(CotacaoAtualResponse resposta, UUID fornecedorId) {
        return resposta.fornecedores().stream()
                .filter(f -> f.fornecedorId().equals(fornecedorId)).findFirst().orElseThrow();
    }

    // ── 6. Tenant-wide: não filtra por usuário criador ──────────────────────

    @Test
    void buscar_e_tenant_wide_retorna_cotacao_independente_de_quem_criou() {
        UUID usuarioQueCriou = criarUsuario();
        UUID cotacaoId = criarCotacao(CotacaoStatus.RASCUNHO, OffsetDateTime.now(), usuarioQueCriou);

        // buscar() não recebe (nem filtra por) identidade de usuário — qualquer
        // chamada no contexto do tenant enxerga a mesma cotação, não importa quem
        // a criou. Isso é a prova de que a busca é "da loja", não "da sessão".
        CotacaoAtualResponse resposta = buscar().orElseThrow();

        assertEquals(cotacaoId, resposta.id());
    }
}
