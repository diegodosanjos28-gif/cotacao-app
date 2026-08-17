package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.CotacaoFornecedorResponse;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.CotacaoFornecedorService;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.dto.FornecedorRequest;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Épico B1 — {@link CotacaoFornecedorService}: progresso PENDENTE/PROCESSADO/CONFIRMADO
 * de cada fornecedor dentro de uma cotação, no fluxo sequencial de Entrada de Dados.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoFornecedorServiceTest {

    @Autowired private CotacaoFornecedorService cotacaoFornecedorService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    // Só criado (e limpo) pelo teste de isolamento entre tenants.
    private UUID outroTenantId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant CotacaoFornecedor Test");
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
                    "(SELECT id FROM cotacao WHERE tenant_id IN (?, ?))", tenantId, outroTenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id IN (?, ?)", tenantId, outroTenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id IN (?, ?)", tenantId, outroTenantId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantId, outroTenantId);
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

    private <T> T comoOutroTenant(Supplier<T> fn) {
        TenantContext.set(outroTenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return criarCotacao(CotacaoStatus.EM_ANDAMENTO);
    }

    private UUID criarCotacao(CotacaoStatus status) {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação CotacaoFornecedor Test");
            c.setStatus(status);
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

    private AdicionarFornecedorCotacaoRequest requestExistente(UUID fornecedorId) {
        return new AdicionarFornecedorCotacaoRequest(fornecedorId, null);
    }

    private AdicionarFornecedorCotacaoRequest requestNovo(String nome) {
        FornecedorRequest novo = new FornecedorRequest(nome, null, null, null, null, null);
        return new AdicionarFornecedorCotacaoRequest(null, novo);
    }

    /** Marca a linha cotacao_fornecedor do par (cotacao, fornecedor) como CONFIRMADO. */
    private void confirmar(UUID cotacaoId, UUID fornecedorId) {
        comoTenant(() -> {
            CotacaoFornecedor cf = cfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                    .orElseThrow();
            cf.setStatus(CotacaoFornecedorStatus.CONFIRMADO);
            return cfRepository.save(cf);
        });
    }

    // ── Validação XOR (fornecedorId x novoFornecedor) ───────────────────────

    @Test
    void adicionar_sem_fornecedorId_e_sem_novoFornecedor_lanca_illegal_argument() {
        UUID cotacaoId = criarCotacao();
        AdicionarFornecedorCotacaoRequest request = new AdicionarFornecedorCotacaoRequest(null, null);

        assertThrows(IllegalArgumentException.class,
                () -> comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, request)));
    }

    @Test
    void adicionar_com_fornecedorId_e_novoFornecedor_ambos_preenchidos_lanca_illegal_argument() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Existente XOR");
        AdicionarFornecedorCotacaoRequest request = new AdicionarFornecedorCotacaoRequest(
                fornecedorId, new FornecedorRequest("Outro Nome", null, null, null, null, null));

        assertThrows(IllegalArgumentException.class,
                () -> comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, request)));
    }

    // ── Criação a partir de fornecedor existente vs. novoFornecedor ─────────

    @Test
    void adicionar_com_fornecedor_existente_associa_a_cotacao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Distribuidora Existente");

        CotacaoFornecedorResponse resposta = comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorId)));

        assertEquals(fornecedorId, resposta.fornecedorId());
        assertEquals("Distribuidora Existente", resposta.nomeFornecedor());
        assertEquals(1, resposta.ordem());
        assertEquals(CotacaoFornecedorStatus.PENDENTE, resposta.status());
    }

    @Test
    void adicionar_com_novo_fornecedor_cria_fornecedor_e_associa_em_um_passo() {
        UUID cotacaoId = criarCotacao();

        CotacaoFornecedorResponse resposta = comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestNovo("Fornecedor Recém Criado")));

        assertNotNull(resposta.fornecedorId());
        assertEquals("Fornecedor Recém Criado", resposta.nomeFornecedor());

        Fornecedor persistido = comoTenant(() ->
                fornecedorRepository.findById(resposta.fornecedorId()).orElseThrow());
        assertEquals("Fornecedor Recém Criado", persistido.getNome());
    }

    // ── Gate sequencial ───────────────────────────────────────────────────────

    @Test
    void adicionar_segundo_fornecedor_enquanto_primeiro_nao_esta_confirmado_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorA = criarFornecedor("Fornecedor Sequencial A");
        UUID fornecedorB = criarFornecedor("Fornecedor Sequencial B");

        comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorA)));
        // fornecedorA continua PENDENTE (nunca processado/confirmado)

        assertThrows(ConflictException.class, () -> comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorB))));
    }

    @Test
    void adicionar_segundo_fornecedor_apos_primeiro_confirmado_e_permitido() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorA = criarFornecedor("Fornecedor Confirmado A");
        UUID fornecedorB = criarFornecedor("Fornecedor Confirmado B");

        comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorA)));
        confirmar(cotacaoId, fornecedorA);

        CotacaoFornecedorResponse resposta = comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorB)));

        assertEquals(fornecedorB, resposta.fornecedorId());
        assertEquals(2, resposta.ordem());
    }

    // ── Status da cotação ────────────────────────────────────────────────────

    @Test
    void adicionar_em_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = criarCotacao(CotacaoStatus.FINALIZADA);
        UUID fornecedorId = criarFornecedor("Fornecedor Cotação Finalizada");

        assertThrows(ConflictException.class, () -> comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorId))));
    }

    // ── Duplicidade ───────────────────────────────────────────────────────────

    @Test
    void adicionar_mesmo_fornecedor_duas_vezes_na_mesma_cotacao_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Duplicado");

        comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorId)));
        confirmar(cotacaoId, fornecedorId);

        assertThrows(ConflictException.class, () -> comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorId))));
    }

    // ── Ordem sequencial ─────────────────────────────────────────────────────

    @Test
    void ordem_incrementa_sequencialmente_conforme_fornecedores_sao_adicionados() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorA = criarFornecedor("Fornecedor Ordem A");
        UUID fornecedorB = criarFornecedor("Fornecedor Ordem B");
        UUID fornecedorC = criarFornecedor("Fornecedor Ordem C");

        CotacaoFornecedorResponse respostaA = comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorA)));
        confirmar(cotacaoId, fornecedorA);

        CotacaoFornecedorResponse respostaB = comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorB)));
        confirmar(cotacaoId, fornecedorB);

        CotacaoFornecedorResponse respostaC = comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorC)));

        assertEquals(1, respostaA.ordem());
        assertEquals(2, respostaB.ordem());
        assertEquals(3, respostaC.ordem());
    }

    // ── listar ───────────────────────────────────────────────────────────────

    @Test
    void listar_retorna_fornecedores_ordenados_por_ordem_com_nome_populado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorA = criarFornecedor("Zebra Distribuidora");
        UUID fornecedorB = criarFornecedor("Alfa Atacado");

        comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorA)));
        confirmar(cotacaoId, fornecedorA);
        comoTenant(() -> cotacaoFornecedorService.adicionar(cotacaoId, requestExistente(fornecedorB)));

        List<CotacaoFornecedorResponse> lista = comoTenant(() -> cotacaoFornecedorService.listar(cotacaoId));

        assertEquals(2, lista.size());
        // Ordenado por 'ordem' (ordem de inserção), não por nome — fornecedorA (ordem 1,
        // "Zebra") deve vir antes de fornecedorB (ordem 2, "Alfa").
        assertEquals(fornecedorA, lista.get(0).fornecedorId());
        assertEquals("Zebra Distribuidora", lista.get(0).nomeFornecedor());
        assertEquals(1, lista.get(0).ordem());

        assertEquals(fornecedorB, lista.get(1).fornecedorId());
        assertEquals("Alfa Atacado", lista.get(1).nomeFornecedor());
        assertEquals(2, lista.get(1).ordem());
    }

    @Test
    void listar_cotacao_sem_fornecedores_retorna_lista_vazia() {
        UUID cotacaoId = criarCotacao();

        List<CotacaoFornecedorResponse> lista = comoTenant(() -> cotacaoFornecedorService.listar(cotacaoId));

        assertTrue(lista.isEmpty());
    }

    // ── Isolamento multi-tenant ──────────────────────────────────────────────

    @Test
    void adicionar_em_cotacao_de_outro_tenant_lanca_not_found() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Outro Tenant CotacaoFornecedor Test");
            t.setStatus(TenantStatus.TRIAL);
            outroTenantId = tenantRepository.save(t).getId();
            return null;
        });
        TenantContext.clear();

        UUID cotacaoDoOutroTenant = comoOutroTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação de Outro Tenant");
            c.setStatus(CotacaoStatus.EM_ANDAMENTO);
            c.setCanalOrigem(CanalOrigem.WEB);
            return cotacaoRepository.save(c).getId();
        });

        UUID fornecedorId = criarFornecedor("Fornecedor Tentativa Cross-Tenant");

        // Tenant "principal" tenta adicionar um fornecedor à cotação do outro tenant.
        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                cotacaoFornecedorService.adicionar(cotacaoDoOutroTenant, requestExistente(fornecedorId))));

        // E também não enxerga a cotação do outro tenant via listar().
        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                cotacaoFornecedorService.listar(cotacaoDoOutroTenant)));

        // Confirma que nenhuma linha cross-tenant foi criada em cotacao_fornecedor.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cotacao_fornecedor WHERE cotacao_id = ?",
                Integer.class, cotacaoDoOutroTenant);
        assertEquals(0, count);
    }
}
