package com.prx.cotacao;

import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import com.prx.cotacao.identidade.repository.UsuarioTelefoneAutorizadoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de isolamento multi-tenant usando Postgres local.
 *
 * Pré-requisito: banco cotacao_dev acessível (mesmo ambiente de dev).
 * Cada teste usa UUIDs únicos e limpa apenas seus próprios dados no @AfterEach.
 *
 * Estratégia de transação:
 * - SEM @Transactional na classe — precisamos de transações separadas por tenant.
 * - Cada bloco usa TransactionTemplate para que TenantAwareTransactionManager
 *   habilite o Hibernate Filter com o TenantContext correto em cada transação.
 */
@SpringBootTest
@ActiveProfiles("dev")
class MultiTenantIsolationTest {

    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioTelefoneAutorizadoRepository telefoneRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private UUID tenantAId;
    private UUID tenantBId;
    private UUID usuarioAId;
    private UUID usuarioBId;

    @BeforeEach
    void criarTenants() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Teste Isolamento");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Teste Isolamento");
            b.setStatus(TenantStatus.TRIAL);
            tenantBId = tenantRepository.save(b).getId();

            // usuario_telefone_autorizado.usuario_id tem FK NOT NULL para usuario(id) —
            // precisa de um usuário real por tenant para os testes de telefone abaixo.
            usuarioAId = usuarioRepository.save(novoUsuario(tenantAId)).getId();
            usuarioBId = usuarioRepository.save(novoUsuario(tenantBId)).getId();
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        // Deleta apenas os dados criados neste teste, sem afetar dados de dev existentes
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            // Ordem respeita FK: cotacao_produto/cpf são cascade de cotacao;
            // usuario_telefone_autorizado e usuario referenciam tenant, então saem antes.
            jdbc.update("DELETE FROM usuario_telefone_autorizado WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM produto WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoTenantA(java.util.function.Supplier<T> fn) {
        TenantContext.set(tenantAId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private <T> T comoTenantB(java.util.function.Supplier<T> fn) {
        TenantContext.set(tenantBId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private <T> T comoAdmin(java.util.function.Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private Cotacao novaCotacao(String titulo) {
        Cotacao c = new Cotacao();
        c.setTitulo(titulo);
        c.setStatus(CotacaoStatus.RASCUNHO);
        c.setCanalOrigem(CanalOrigem.WEB);
        return c;
    }

    private Usuario novoUsuario(UUID tenantId) {
        Usuario u = new Usuario();
        u.setTenantId(tenantId);
        u.setEmail("isolamento-telefone-" + UUID.randomUUID() + "@prx-test.com");
        u.setSenhaHash("hash-nao-importa");
        u.setPapel(Papel.OPERADOR_CLIENTE);
        return u;
    }

    private UsuarioTelefoneAutorizado novoTelefone(UUID usuarioId) {
        UsuarioTelefoneAutorizado t = new UsuarioTelefoneAutorizado();
        t.setUsuarioId(usuarioId);
        t.setNumeroWhatsapp(numeroUnico());
        // tenantId propositalmente NÃO setado — mesmo listener automático testado
        // em listener_seta_tenant_id_automaticamente_quando_nulo.
        return t;
    }

    private String numeroUnico() {
        return "+5511" + String.format("%09d", SEQ.incrementAndGet());
    }

    // ── Testes de Cotacao ────────────────────────────────────────────────────

    @Test
    void findAll_retorna_apenas_cotacoes_do_proprio_tenant() {
        UUID idA = comoTenantA(() -> cotacaoRepository.save(novaCotacao("Cotação A")).getId());
        UUID idB = comoTenantB(() -> cotacaoRepository.save(novaCotacao("Cotação B")).getId());

        List<Cotacao> vistosPorA = comoTenantA(() -> cotacaoRepository.findAll());
        assertEquals(1, vistosPorA.size(), "Tenant A deve ver apenas 1 cotação");
        assertEquals(idA, vistosPorA.get(0).getId());
        assertNotEquals(idB, vistosPorA.get(0).getId());

        List<Cotacao> vistosPorB = comoTenantB(() -> cotacaoRepository.findAll());
        assertEquals(1, vistosPorB.size(), "Tenant B deve ver apenas 1 cotação");
        assertEquals(idB, vistosPorB.get(0).getId());
    }

    @Test
    void findById_nao_retorna_cotacao_de_outro_tenant() {
        UUID idDaA = comoTenantA(() -> cotacaoRepository.save(novaCotacao("Só do A")).getId());

        // Tenant B tenta buscar pelo ID do tenant A
        Optional<Cotacao> resultado = comoTenantB(() -> cotacaoRepository.findById(idDaA));

        assertTrue(resultado.isEmpty(),
                "findById deve retornar vazio quando o ID pertence a outro tenant");
    }

    @Test
    void tenant_id_e_setado_automaticamente_no_pre_persist() {
        Fornecedor salvo = comoTenantA(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome("Fornecedor Auto-Tenant");
            // Intencionalmente NÃO setamos tenantId
            return fornecedorRepository.save(f);
        });

        assertEquals(tenantAId, salvo.getTenantId(),
                "TenantAuditEntityListener deve setar tenantId automaticamente a partir de TenantContext");
    }

    @Test
    void admin_ve_cotacoes_de_todos_os_tenants() {
        comoTenantA(() -> cotacaoRepository.save(novaCotacao("Cotação Admin A")));
        comoTenantB(() -> cotacaoRepository.save(novaCotacao("Cotação Admin B")));

        List<Cotacao> todas = comoAdmin(() -> cotacaoRepository.findAll());

        boolean temDeA = todas.stream().anyMatch(c -> tenantAId.equals(c.getTenantId()));
        boolean temDeB = todas.stream().anyMatch(c -> tenantBId.equals(c.getTenantId()));
        assertTrue(temDeA, "Admin deve ver cotações do tenant A");
        assertTrue(temDeB, "Admin deve ver cotações do tenant B");
    }

    // ── Testes de Fornecedor ────────────────────────────────────────────────

    @Test
    void fornecedor_de_um_tenant_nao_visivel_para_outro() {
        UUID idFA = comoTenantA(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome("Distribuidora A");
            return fornecedorRepository.save(f).getId();
        });

        comoTenantB(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome("Distribuidora B");
            return fornecedorRepository.save(f).getId();
        });

        List<Fornecedor> listaPorA = comoTenantA(() ->
                fornecedorRepository.findByStatusNot(FornecedorStatus.INATIVO));
        assertEquals(1, listaPorA.size());
        assertEquals(idFA, listaPorA.get(0).getId());

        Optional<Fornecedor> tentativaB = comoTenantB(() ->
                fornecedorRepository.findById(idFA));
        assertTrue(tentativaB.isEmpty(),
                "Tenant B não pode buscar fornecedor do tenant A pelo ID");
    }

    // ── Testes de Produto ───────────────────────────────────────────────────

    @Test
    void produto_de_um_tenant_nao_visivel_para_outro() {
        comoTenantA(() -> {
            Produto p = new Produto(); p.setNome("Sazon Legumes 60g");
            return produtoRepository.save(p);
        });
        comoTenantB(() -> {
            Produto p = new Produto(); p.setNome("Açúcar União 1kg");
            return produtoRepository.save(p);
        });

        List<Produto> porA = comoTenantA(() -> produtoRepository.findAll());
        List<Produto> porB = comoTenantB(() -> produtoRepository.findAll());

        assertEquals(1, porA.size());
        assertEquals("Sazon Legumes 60g", porA.get(0).getNome());
        assertEquals(1, porB.size());
        assertEquals("Açúcar União 1kg", porB.get(0).getNome());
    }

    @Test
    void busca_por_nome_respeita_isolamento_de_tenant() {
        comoTenantA(() -> {
            Produto p = new Produto(); p.setNome("Sazon Legumes 60g");
            return produtoRepository.save(p);
        });
        comoTenantB(() -> {
            Produto p = new Produto(); p.setNome("Sazon Frango 60g");
            return produtoRepository.save(p);
        });

        List<Produto> resultadoA = comoTenantA(() -> produtoRepository.buscarPorNome("sazon"));

        assertEquals(1, resultadoA.size(),
                "buscarPorNome deve retornar apenas produtos do tenant A");
        assertEquals("Sazon Legumes 60g", resultadoA.get(0).getNome());
    }

    // ── Teste de escrita cross-tenant ────────────────────────────────────────

    @Test
    void listener_seta_tenant_id_automaticamente_quando_nulo() {
        // O listener seta tenantId a partir do TenantContext quando o campo é nulo.
        // NÃO sobrescreve valores já setados — a proteção contra cross-tenant write fica no RLS.
        Cotacao c = comoTenantA(() -> {
            Cotacao nova = novaCotacao("Cotação Listener Test");
            // tenantId é null neste ponto — listener deve setar tenantAId
            return cotacaoRepository.save(nova);
        });

        assertEquals(tenantAId, c.getTenantId(),
                "Listener deve setar tenantId a partir do TenantContext quando o campo é nulo");

        // E o registro é visível apenas para tenant A
        Optional<Cotacao> vistoPorB = comoTenantB(() -> cotacaoRepository.findById(c.getId()));
        assertTrue(vistoPorB.isEmpty(),
                "Cotação do tenant A não deve ser visível para tenant B");
    }

    // ── Testes de UsuarioTelefoneAutorizado ─────────────────────────────────

    @Test
    void telefone_de_um_tenant_nao_visivel_para_outro() {
        UUID idA = comoTenantA(() -> telefoneRepository.save(novoTelefone(usuarioAId)).getId());
        UUID idB = comoTenantB(() -> telefoneRepository.save(novoTelefone(usuarioBId)).getId());

        List<UsuarioTelefoneAutorizado> vistosPorA = comoTenantA(() -> telefoneRepository.findAll());
        assertEquals(1, vistosPorA.size(), "Tenant A deve ver apenas 1 telefone");
        assertEquals(idA, vistosPorA.get(0).getId());
        assertNotEquals(idB, vistosPorA.get(0).getId());

        List<UsuarioTelefoneAutorizado> vistosPorB = comoTenantB(() -> telefoneRepository.findAll());
        assertEquals(1, vistosPorB.size(), "Tenant B deve ver apenas 1 telefone");
        assertEquals(idB, vistosPorB.get(0).getId());
    }

    @Test
    void telefone_findById_nao_retorna_telefone_de_outro_tenant() {
        UUID idDaA = comoTenantA(() -> telefoneRepository.save(novoTelefone(usuarioAId)).getId());

        Optional<UsuarioTelefoneAutorizado> resultado = comoTenantB(() -> telefoneRepository.findById(idDaA));

        assertTrue(resultado.isEmpty(),
                "Tenant B não pode buscar telefone do tenant A pelo ID");
    }

    @Test
    void telefone_findByUsuarioId_nao_retorna_telefone_de_usuario_de_outro_tenant() {
        // Mesmo que o tenant B descubra o usuarioId do tenant A (ex.: IDOR), a RLS
        // bloqueia a leitura porque tenant_id da linha não bate com o tenant autenticado.
        comoTenantA(() -> telefoneRepository.save(novoTelefone(usuarioAId)));

        List<UsuarioTelefoneAutorizado> vistoPorB = comoTenantB(() ->
                telefoneRepository.findByUsuarioIdOrderByCriadoEmDesc(usuarioAId));

        assertTrue(vistoPorB.isEmpty(),
                "RLS deve bloquear tenant B mesmo passando o usuarioId do tenant A diretamente");
    }

    @Test
    void telefone_tenant_id_e_setado_automaticamente_no_pre_persist() {
        UsuarioTelefoneAutorizado salvo = comoTenantA(() -> telefoneRepository.save(novoTelefone(usuarioAId)));

        assertEquals(tenantAId, salvo.getTenantId(),
                "TenantAuditEntityListener deve setar tenantId automaticamente a partir de TenantContext");
    }

    @Test
    void admin_ve_telefones_de_todos_os_tenants() {
        comoTenantA(() -> telefoneRepository.save(novoTelefone(usuarioAId)));
        comoTenantB(() -> telefoneRepository.save(novoTelefone(usuarioBId)));

        List<UsuarioTelefoneAutorizado> todos = comoAdmin(() -> telefoneRepository.findAll());

        boolean temDeA = todos.stream().anyMatch(t -> tenantAId.equals(t.getTenantId()));
        boolean temDeB = todos.stream().anyMatch(t -> tenantBId.equals(t.getTenantId()));
        assertTrue(temDeA, "Admin deve ver telefones do tenant A");
        assertTrue(temDeB, "Admin deve ver telefones do tenant B");
    }
}
