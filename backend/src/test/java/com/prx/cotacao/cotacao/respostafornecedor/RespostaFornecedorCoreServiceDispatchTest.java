package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.web.ParametroResolucaoWeb;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ParametroResolucaoWhatsapp;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.enums.OrigemCadastro;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
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

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prompt posterior ao 15 (Strategy real) — {@link RespostaFornecedorCoreService#processar}
 * passou a fazer o dispatch de resolução de fornecedor ele mesmo, escolhendo a Strategy
 * certa a partir da classe concreta de {@code ParametroResolucaoFornecedor} recebido.
 * Prova o dispatch certo por COMPORTAMENTO OBSERVÁVEL divergente entre os dois canais
 * (Web nunca cria, WhatsApp cria) — sem mocks, mesmo padrão de
 * {@link ResolvedorFornecedorWhatsappStrategyTest}/{@link FornecedorRespostaServiceTest}.
 * A branch "tipo sem resolver registrado" é coberta separadamente em
 * {@link RespostaFornecedorCoreServiceSemResolverRegistradoTest} (unitário puro, sem
 * contexto Spring).
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), mesma convenção dos demais
 * testes de integração deste pacote.
 */
@SpringBootTest
@ActiveProfiles("dev")
class RespostaFornecedorCoreServiceDispatchTest {

    @Autowired private RespostaFornecedorCoreService core;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
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
            t.setNomeFantasia("Tenant RespostaFornecedorCoreService Dispatch Test");
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
            jdbc.update("DELETE FROM cotacao_fornecedor WHERE tenant_id = ?", tenantId);
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
            c.setTitulo("Cotação RespostaFornecedorCoreService Dispatch Test");
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

    private void adicionarItemBase(UUID cotacaoId, String textoOriginal, int ordem) {
        comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal(textoOriginal);
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp);
        });
    }

    // ── 1. ParametroResolucaoWeb → despacha para ResolvedorFornecedorWebStrategy,
    // que só VALIDA (nunca cria). Fornecedor existe mas nunca foi adicionado à
    // cotação (nunca passou por POST /cotacoes/{id}/fornecedores) → ResourceNotFoundException.
    // Se o dispatch estivesse errado e chamasse a Strategy do WhatsApp, ela teria
    // CRIADO o vínculo em vez de lançar essa exceção. ──────────────────────────

    @Test
    void processar_com_parametro_web_despacha_para_strategy_web_que_so_valida_sem_criar() {
        UUID cotacaoId = criarCotacao();
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Nunca Adicionado À Cotação");
        // Note: fornecedor NÃO foi adicionado à cotação (sem cotacao_fornecedor).

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                core.processar(cotacaoId, new ParametroResolucaoWeb(fornecedorId), "Sazon Legumes 60g - R$ 2,89")));
    }

    // ── 2. ParametroResolucaoWhatsapp → despacha para
    // ResolvedorFornecedorWhatsappStrategy, que CASA por similaridade ou CRIA um
    // fornecedor novo PENDENTE_DADOS/WHATSAPP_AUTO. Nome sem nenhum match →
    // deve criar. Se o dispatch estivesse errado e chamasse a Strategy Web, teria
    // lançado ResourceNotFoundException (fornecedor inexistente) em vez de criar. ──

    @Test
    void processar_com_parametro_whatsapp_despacha_para_strategy_whatsapp_que_cria_fornecedor_sem_match() {
        UUID cotacaoId = criarCotacao();
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        String nomeSemNenhumMatch = "Distribuidora Sem Nenhum Match Existente 12345";

        PreviewRespostaResponse resultado = comoTenant(() -> core.processar(
                cotacaoId, new ParametroResolucaoWhatsapp(nomeSemNenhumMatch), "Sazon Legumes 60g - R$ 2,89"));

        assertNotNull(resultado);
        Fornecedor criado = comoTenant(() -> fornecedorRepository.findAll().stream()
                .filter(f -> nomeSemNenhumMatch.equals(f.getNome()))
                .findFirst().orElseThrow());
        assertEquals(FornecedorStatus.PENDENTE_DADOS, criado.getStatus());
        assertEquals(OrigemCadastro.WHATSAPP_AUTO, criado.getOrigemCadastro());
    }
}
