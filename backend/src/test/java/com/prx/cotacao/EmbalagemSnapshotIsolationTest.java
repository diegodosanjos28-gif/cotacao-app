package com.prx.cotacao;

import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.shared.tenant.TenantDetails;
import com.prx.cotacao.cotacao.respostafornecedor.service.AvisoService;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes da regra de snapshot imutável de embalagem (CLAUDE.md Regra 2).
 *
 * Regra:
 * - embalagem_qtd_confirmada em CotacaoProdutoFornecedor é snapshot imutável por linha.
 * - produto.embalagemQtdSugerida só é atualizado quando >= 2 fornecedores distintos
 *   confirmam a MESMA quantidade para o mesmo CotacaoProduto.
 * - Ao detectar 2 fornecedores divergentes, nenhuma sugestão é gravada no produto.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev).
 */
@SpringBootTest
@ActiveProfiles("dev")
class EmbalagemSnapshotIsolationTest {

    @Autowired private AvisoService avisoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private final UUID usuarioFakeId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();

        // JDBC direto para evitar buffering do JPA (que causaria FK violation no usuario_tenant_id_fkey)
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Embalagem Test', 'TRIAL')",
                    tenantId);
            jdbc.update("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, ativo)
                    VALUES (?, ?, 'teste-embalagem@prx.com', 'hash_fake', 'OPERADOR_CLIENTE', true)
                    """, usuarioFakeId, tenantId);
            return null;
        });
        TenantContext.clear();

        // AvisoService.resolver() chama currentUser.usuarioId() — setup de auth fake
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
            jdbc.update("DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN " +
                    "(SELECT id FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?))", tenantId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN " +
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM usuario WHERE id = ?", usuarioFakeId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helper: cria o grafo completo Cotacao→CotacaoProduto→N CPFs ──────────

    record Cenario(UUID cotacaoId, UUID produtoId, UUID cotacaoProdutoId, List<UUID> cpfIds) {}

    private Cenario criarCenario(int qtdFornecedores) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        Cenario c = tx.execute(status -> {
            Produto produto = new Produto();
            produto.setNome("Produto Embalagem " + UUID.randomUUID());
            produtoRepository.save(produto);

            Cotacao cotacao = new Cotacao();
            cotacao.setTitulo("Cotacao Embalagem Test");
            cotacao.setStatus(CotacaoStatus.EM_ANDAMENTO);
            cotacao.setCanalOrigem(CanalOrigem.WEB);
            cotacaoRepository.save(cotacao);

            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacao.getId());
            cp.setProdutoId(produto.getId());
            cp.setTextoOriginal("produto teste");
            cp.setQuantidade(BigDecimal.TEN);
            cp.setUnidade("un");
            cp.setOrdem(1);
            cotacaoProdutoRepository.save(cp);

            List<UUID> cpfIds = new java.util.ArrayList<>();
            for (int i = 0; i < qtdFornecedores; i++) {
                Fornecedor f = new Fornecedor();
                // Sufixo aleatório: criarCenario() pode ser chamado mais de uma vez no
                // mesmo tenant dentro de um teste (ex: resolver_com_cpf_de_outra_cotacao_
                // lanca_not_found cria 2 cenários) — nome fixo colidiria com o índice
                // único de V14 (tenant_id, lower(nome)) entre fornecedores não-inativos.
                f.setNome("Fornecedor " + i + " " + UUID.randomUUID());
                fornecedorRepository.save(f);

                CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
                cpf.setCotacaoProdutoId(cp.getId());
                cpf.setFornecedorId(f.getId());
                cpf.setPrecoInformado(new BigDecimal("48.00")); // suspeito de ser preço de caixa
                cpf.setStatus(StatusItem.PENDENTE_CONFIRMACAO);
                cpfRepository.save(cpf);
                cpfIds.add(cpf.getId());
            }

            return new Cenario(cotacao.getId(), produto.getId(), cp.getId(), cpfIds);
        });
        TenantContext.clear();
        return c;
    }

    private CotacaoProdutoFornecedor buscarCpf(UUID cpfId) {
        TenantContext.setAdmin(true);
        CotacaoProdutoFornecedor cpf = tx.execute(s ->
                cpfRepository.findById(cpfId).orElseThrow());
        TenantContext.clear();
        return cpf;
    }

    private Produto buscarProduto(UUID produtoId) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        Produto p = tx.execute(s -> produtoRepository.findById(produtoId).orElseThrow());
        TenantContext.clear();
        return p;
    }

    private void resolver(UUID cotacaoId, UUID cpfId, int qtd) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        tx.execute(s -> { avisoService.resolver(cotacaoId, cpfId, qtd); return null; });
        TenantContext.clear();
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void um_fornecedor_confirmando_nao_atualiza_produto() {
        Cenario c = criarCenario(1);

        resolver(c.cotacaoId(), c.cpfIds().get(0), 12);

        CotacaoProdutoFornecedor cpf = buscarCpf(c.cpfIds().get(0));
        assertEquals(12, cpf.getEmbalagemQtdConfirmada(),
                "embalagem_qtd_confirmada deve ser gravado na linha");

        Produto produto = buscarProduto(c.produtoId());
        assertNull(produto.getEmbalagemQtdSugerida(),
                "Com 1 fornecedor, produto.embalagemQtdSugerida NÃO deve ser atualizado");
    }

    @Test
    void dois_fornecedores_concordando_atualizam_produto() {
        Cenario c = criarCenario(2);

        resolver(c.cotacaoId(), c.cpfIds().get(0), 12);
        assertNull(buscarProduto(c.produtoId()).getEmbalagemQtdSugerida(),
                "Após 1ª confirmação, produto ainda não deve ter sugestão");

        resolver(c.cotacaoId(), c.cpfIds().get(1), 12); // mesmo valor

        Produto produto = buscarProduto(c.produtoId());
        assertEquals(12, produto.getEmbalagemQtdSugerida(),
                "Com 2 fornecedores concordando, produto.embalagemQtdSugerida deve ser 12");
    }

    @Test
    void dois_fornecedores_divergindo_nao_atualizam_produto() {
        Cenario c = criarCenario(2);

        resolver(c.cotacaoId(), c.cpfIds().get(0), 10); // Fornecedor 1: 10 unidades
        resolver(c.cotacaoId(), c.cpfIds().get(1), 12); // Fornecedor 2: 12 unidades (diferente!)

        Produto produto = buscarProduto(c.produtoId());
        assertNull(produto.getEmbalagemQtdSugerida(),
                "Com fornecedores divergentes, produto.embalagemQtdSugerida NÃO deve ser atualizado");
    }

    @Test
    void segunda_confirmacao_lanca_excecao_e_preserva_valor_original() {
        Cenario c = criarCenario(1);
        UUID cpfId = c.cpfIds().get(0);

        resolver(c.cotacaoId(), cpfId, 12);

        // Segunda tentativa deve lançar exceção
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> tx.execute(s -> { avisoService.resolver(c.cotacaoId(), cpfId, 15); return null; }),
                "Re-confirmar embalagem deve lançar ConflictException");
        assertTrue(ex.getMessage().contains("Embalagem já confirmada"));
        TenantContext.clear();

        // Valor original (12) deve estar intacto
        assertEquals(12, buscarCpf(cpfId).getEmbalagemQtdConfirmada(),
                "embalagem_qtd_confirmada deve permanecer 12, não ser sobrescrito para 15");
    }

    @Test
    void duas_chamadas_concorrentes_resolver_mesmo_cpf_apenas_uma_vence() throws Exception {
        // Regressão do achado do db-schema-guardian (2026-07-09): o check-then-act
        // em resolver() (ler embalagemQtdConfirmada, depois gravar) não é atômico
        // por si só. Este teste força duas transações genuinamente concorrentes no
        // MESMO cpfId — diferente de segunda_confirmacao_lanca_excecao_e_preserva_valor_original,
        // que é puramente sequencial e nunca exercita o @Version. Aqui as duas
        // chamadas competem de verdade; o @Version deve garantir que só uma vence,
        // seja no flush (OptimisticLockingFailureException) ou, se uma delas
        // observar o commit da outra antes de ler, no check-then-act (ConflictException)
        // — ambos são desfechos legítimos da corrida resolvida; o que NUNCA pode
        // acontecer é as duas terem sucesso.
        Cenario c = criarCenario(1);
        UUID cpfId = c.cpfIds().get(0);

        int qtdA = 10;
        int qtdB = 20;

        CountDownLatch startLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Exception> taskA = () -> chamarResolverConcorrente(c.cotacaoId(), cpfId, qtdA, startLatch);
            Callable<Exception> taskB = () -> chamarResolverConcorrente(c.cotacaoId(), cpfId, qtdB, startLatch);

            Future<Exception> futureA = executor.submit(taskA);
            Future<Exception> futureB = executor.submit(taskB);

            Exception exA = futureA.get(15, TimeUnit.SECONDS);
            Exception exB = futureB.get(15, TimeUnit.SECONDS);

            long sucessos = Stream.of(exA, exB).filter(Objects::isNull).count();
            assertEquals(1, sucessos,
                    "Exatamente uma das duas chamadas concorrentes deve ter sucesso (exA=" + exA + ", exB=" + exB + ")");

            Exception falhou = (exA != null) ? exA : exB;
            assertTrue(
                    falhou instanceof OptimisticLockingFailureException || falhou instanceof ConflictException,
                    "A chamada perdedora deve falhar com lock otimista (flush concorrente) ou "
                            + "ConflictException (perdeu no check-then-act), não: " + falhou);

            CotacaoProdutoFornecedor cpfFinal = buscarCpf(cpfId);
            Integer valorGravado = cpfFinal.getEmbalagemQtdConfirmada();
            assertNotNull(valorGravado, "Uma das duas chamadas deve ter gravado o snapshot");
            assertTrue(valorGravado == qtdA || valorGravado == qtdB,
                    "Valor confirmado deve ser um dos dois valores tentados, foi: " + valorGravado);

            int qtdDaVencedora = (exA == null) ? qtdA : qtdB;
            assertEquals(qtdDaVencedora, valorGravado,
                    "O valor persistido deve corresponder exatamente à chamada que teve sucesso");

            assertEquals(1L, cpfFinal.getVersao(),
                    "versao deve ter incrementado exatamente uma vez — uma única escrita venceu a corrida");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Executa avisoService.resolver() em sua própria transação, numa thread própria
     * (TenantContext e SecurityContext são ThreadLocal — precisam ser configurados
     * de novo aqui). O CountDownLatch garante que as duas chamadas só começam a
     * transação depois que ambas as threads já estão prontas, maximizando a chance
     * de sobreposição real das duas transações no banco.
     */
    private Exception chamarResolverConcorrente(UUID cotacaoId, UUID cpfId, int qtd, CountDownLatch startLatch) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        TenantDetails details = new TenantDetails(tenantId.toString(), "OPERADOR_CLIENTE");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                usuarioFakeId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERADOR_CLIENTE")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            startLatch.countDown();
            startLatch.await();
            tx.execute(status -> { avisoService.resolver(cotacaoId, cpfId, qtd); return null; });
            return null;
        } catch (Exception e) {
            return e;
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    @Test
    void resolver_com_cpf_de_outra_cotacao_lanca_not_found() {
        // Regressão do IDOR: cpfId válido, mas de uma cotação diferente da informada
        // na URL — resolver() não pode aceitar isso mesmo dentro do mesmo tenant,
        // já que CotacaoProdutoFornecedor não tem Hibernate Filter próprio.
        Cenario c1 = criarCenario(1);
        Cenario c2 = criarCenario(1);
        UUID cpfDaC2 = c2.cpfIds().get(0);

        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        assertThrows(ResourceNotFoundException.class,
                () -> tx.execute(s -> { avisoService.resolver(c1.cotacaoId(), cpfDaC2, 12); return null; }),
                "cpfId de outra cotação deve ser rejeitado, não aplicado à cotação errada");
        TenantContext.clear();

        // O item da c2 continua não confirmado — a tentativa cross-cotação não deve
        // ter efeito colateral nenhum.
        assertNull(buscarCpf(cpfDaC2).getEmbalagemQtdConfirmada());
    }
}
