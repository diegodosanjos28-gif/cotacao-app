package com.prx.cotacao.whatsapp.canal;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ParametroResolucaoWhatsapp;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ResolvedorFornecedorWhatsappStrategy;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.enums.OrigemCadastro;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.whatsapp.canal.service.WhatsappRespostaFornecedorService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Prompt 15 (unificação Web/WhatsApp) — {@link ResolvedorFornecedorWhatsappStrategy#resolver}
 * isolado da coreografia de {@link WhatsappRespostaFornecedorService} (que já é coberta
 * indiretamente em {@link WhatsappRespostaFornecedorServiceTest}). Cobre especificamente
 * a resolução/criação de {@code Fornecedor} por matching fuzzy de nome e a criação
 * idempotente do vínculo {@code CotacaoFornecedor}.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), mesma convenção dos demais
 * testes de integração deste pacote.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ResolvedorFornecedorWhatsappStrategyTest {

    @Autowired private ResolvedorFornecedorWhatsappStrategy strategy;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoFornecedorRepository cfRepository;
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
            t.setNomeFantasia("Tenant ResolvedorFornecedorWhatsapp Test");
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
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
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
            c.setTitulo("Cotação ResolvedorFornecedorWhatsapp Test");
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

    private long contarFornecedores() {
        return comoTenant(() -> fornecedorRepository.findAll().stream().count());
    }

    // ── 1. Nome fuzzy-casável contra fornecedor existente → resolve pro existente,
    // não cria um novo. ──────────────────────────────────────────────────────────

    @Test
    void resolver_com_nome_fuzzy_match_resolve_para_fornecedor_existente_sem_criar_novo() {
        UUID fornecedorExistenteId = criarFornecedor("Padaria Central");
        UUID cotacaoId = criarCotacao();
        assertEquals(1, contarFornecedores());

        // "Padaria Central Ltda" tem token extra ("ltda") mas os dois tokens
        // significativos ("padaria", "central") batem — score 2/3 (0.667) >= 0.6,
        // mesmo motor de MatchingProdutoService#calcSimilaridade usado pra produto.
        UUID resolvidoId = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp("Padaria Central Ltda")));

        assertEquals(fornecedorExistenteId, resolvidoId,
                "nome fuzzy-casável deve resolver pro fornecedor já cadastrado, não criar um novo");
        assertEquals(1, contarFornecedores(),
                "nenhum fornecedor novo deve ser criado quando há match fuzzy");
    }

    // ── 2. Nome sem nenhum match → cria Fornecedor novo PENDENTE_DADOS/WHATSAPP_AUTO ──

    @Test
    void resolver_sem_nenhum_match_cria_fornecedor_novo_pendente_dados_origem_whatsapp_auto() {
        UUID fornecedorExistenteId = criarFornecedor("Padaria Central");
        UUID cotacaoId = criarCotacao();

        UUID resolvidoId = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp("Distribuidora XYZ 999")));

        assertNotEquals(fornecedorExistenteId, resolvidoId,
                "nome sem similaridade suficiente não deve casar com o fornecedor existente");
        assertEquals(2, contarFornecedores(), "deve ter criado um fornecedor novo");

        Fornecedor novo = comoTenant(() -> fornecedorRepository.findById(resolvidoId).orElseThrow());
        assertEquals("Distribuidora XYZ 999", novo.getNome());
        assertEquals(FornecedorStatus.PENDENTE_DADOS, novo.getStatus());
        assertEquals(OrigemCadastro.WHATSAPP_AUTO, novo.getOrigemCadastro());
    }

    // ── 3. CotacaoFornecedor é criado automaticamente se ainda não existir o vínculo ──

    @Test
    void resolver_cria_cotacaoFornecedor_automaticamente_se_vinculo_ainda_nao_existe() {
        UUID cotacaoId = criarCotacao();

        UUID fornecedorId = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp("Fornecedor Novo Sem Vinculo")));

        CotacaoFornecedor cf = comoTenant(() ->
                cfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId).orElseThrow());
        assertEquals(CotacaoFornecedorStatus.PENDENTE, cf.getStatus());
    }

    // ── 4. Idempotência: chamar resolver() duas vezes pro MESMO par cotação+fornecedor
    // não duplica o CotacaoFornecedor. ──────────────────────────────────────────────

    @Test
    void resolver_chamado_duas_vezes_para_o_mesmo_par_nao_duplica_cotacaoFornecedor() {
        UUID cotacaoId = criarCotacao();

        UUID fornecedorId1 = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp("Fornecedor Idempotente")));
        UUID fornecedorId2 = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp("Fornecedor Idempotente")));

        assertEquals(fornecedorId1, fornecedorId2, "mesma chamada deve resolver pro mesmo fornecedor (match exato)");

        List<CotacaoFornecedor> vinculos = comoTenant(() -> cfRepository.findByCotacaoIdOrderByOrdem(cotacaoId));
        assertEquals(1, vinculos.size(),
                "segunda chamada não deve criar um segundo CotacaoFornecedor para o mesmo par");
    }

    // ── 5. Nome nulo/vazio usa o nome padrão "Fornecedor WhatsApp" (NOME_FORNECEDOR_PADRAO) ──

    @Test
    void resolver_com_nome_nulo_usa_nome_padrao_fornecedor_whatsapp() {
        UUID cotacaoId = criarCotacao();

        UUID fornecedorId = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp(null)));

        Fornecedor f = comoTenant(() -> fornecedorRepository.findById(fornecedorId).orElseThrow());
        assertEquals("Fornecedor WhatsApp", f.getNome());
    }

    @Test
    void resolver_com_nome_em_branco_usa_nome_padrao_fornecedor_whatsapp() {
        UUID cotacaoId = criarCotacao();

        UUID fornecedorId = comoTenant(() -> strategy.resolver(cotacaoId, new ParametroResolucaoWhatsapp("   ")));

        Fornecedor f = comoTenant(() -> fornecedorRepository.findById(fornecedorId).orElseThrow());
        assertEquals("Fornecedor WhatsApp", f.getNome());
    }
}
