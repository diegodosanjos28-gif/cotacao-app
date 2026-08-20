package com.prx.cotacao.cotacao.hall.service;

import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoResponse;
import com.prx.cotacao.cotacao.hall.dto.SeloConfiabilidade;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HallFornecedoresService#historico} — Hall dos Fornecedores, modo histórico
 * (landing da Entrada de Dados, sem cotação em andamento). Cobre a agregação feita em
 * {@link CotacaoFornecedorRepository#buscarHistoricoFornecedores} (query nativa) —
 * isolamento multi-tenant dessa mesma query já é coberto separadamente em
 * {@code NativeQueryMultiTenantIsolationTest}; aqui o foco é o comportamento de
 * negócio (cobertura, selo, fornecedor sem histórico, filtro por status ATIVO).
 */
@SpringBootTest
@ActiveProfiles("dev")
class HallFornecedoresServiceTest {

    @Autowired private HallFornecedoresService hallFornecedoresService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - HallFornecedoresService Test");
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

    private UUID criarFornecedor(String nome, FornecedorStatus status) {
        return comoTenantA(() -> {
            Fornecedor f = new Fornecedor();
            f.setNome(nome);
            f.setStatus(status);
            return fornecedorRepository.save(f).getId();
        });
    }

    /**
     * Cotação FINALIZADA com {@code totalItens} itens vivos, dos quais
     * {@code itensCotadosPeloFornecedor} têm oferta OK do fornecedor dado, mais um
     * vínculo {@link CotacaoFornecedor} CONFIRMADO cujo atualizado_em/criado_em são
     * sobrescritos via JDBC direto para simular {@code minutosDeResposta} de
     * diferença (a entidade sempre grava os dois campos como "agora" no
     * {@code @PrePersist}, ver TenantAuditEntityListener).
     */
    private UUID criarParticipacaoConfirmada(UUID fornecedorId, int totalItens, int itensCotadosPeloFornecedor,
                                              long minutosDeResposta) {
        return comoTenantA(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Hall Histórico Test");
            c.setStatus(CotacaoStatus.FINALIZADA);
            c.setCanalOrigem(CanalOrigem.WEB);
            c.setFinalizadaEm(OffsetDateTime.now());
            UUID cotacaoId = cotacaoRepository.save(c).getId();

            for (int i = 0; i < totalItens; i++) {
                CotacaoProduto cp = new CotacaoProduto();
                cp.setCotacaoId(cotacaoId);
                cp.setTextoOriginal("Item " + i);
                cp.setQuantidade(new BigDecimal("1.000"));
                cp.setUnidade("un");
                cp.setOrdem(i + 1);
                UUID itemId = cotacaoProdutoRepository.save(cp).getId();

                if (i < itensCotadosPeloFornecedor) {
                    CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
                    cpf.setCotacaoProdutoId(itemId);
                    cpf.setFornecedorId(fornecedorId);
                    cpf.setTextoOriginal("Oferta");
                    cpf.setPrecoInformado(new BigDecimal("2.00"));
                    cpf.setPrecoUnitarioCalculado(new BigDecimal("2.00"));
                    cpf.setSemEstoque(false);
                    cpf.setStatus(StatusItem.OK);
                    cpfRepository.save(cpf);
                }
            }

            CotacaoFornecedor cf = new CotacaoFornecedor();
            cf.setCotacaoId(cotacaoId);
            cf.setFornecedorId(fornecedorId);
            cf.setOrdem(1);
            cf.setStatus(CotacaoFornecedorStatus.CONFIRMADO);
            // saveAndFlush (não save): o UPDATE via JdbcTemplate abaixo é JDBC puro, não
            // passa pela sessão do Hibernate — sem o flush explícito, o INSERT deste
            // registro ainda não teria ido ao banco (GenerationType.UUID gera o id em
            // memória, sem round-trip), e o UPDATE por id afetaria 0 linhas em silêncio.
            UUID cfId = cfRepository.saveAndFlush(cf).getId();

            // Sobrescreve os timestamps diretamente — o listener sempre grava os dois
            // como "agora" no insert, então isso precisa ser feito depois, fora do JPA.
            OffsetDateTime criadoEm = OffsetDateTime.now().minusMinutes(minutosDeResposta);
            OffsetDateTime atualizadoEm = OffsetDateTime.now();
            jdbc.update("UPDATE cotacao_fornecedor SET criado_em = ?, atualizado_em = ? WHERE id = ?",
                    criadoEm, atualizadoEm, cfId);

            return cotacaoId;
        });
    }

    private List<FornecedorHistoricoResponse> historico() {
        return comoTenantA(hallFornecedoresService::historico);
    }

    private FornecedorHistoricoResponse doFornecedor(List<FornecedorHistoricoResponse> lista, UUID fornecedorId) {
        return lista.stream().filter(f -> f.fornecedorId().equals(fornecedorId)).findFirst()
                .orElseThrow(() -> new AssertionError("Fornecedor " + fornecedorId + " não apareceu no histórico"));
    }

    // ── 1. Fornecedor sem cotação FINALIZADA aparece com zeros ──────────────

    @Test
    void historico_fornecedor_sem_cotacao_finalizada_aparece_com_zeros_nao_e_excluido() {
        UUID fornecedorId = criarFornecedor("Fornecedor Sem Histórico", FornecedorStatus.ATIVO);

        FornecedorHistoricoResponse resposta = doFornecedor(historico(), fornecedorId);

        assertEquals(0, resposta.cotacoesParticipadas());
        assertEquals(0.0, resposta.coberturaMediaPct());
        assertEquals(0.0, resposta.tempoRespostaMedioMinutos());
        assertEquals(SeloConfiabilidade.AGIL, resposta.selo(), "0 minutos cai no bucket AGIL (< 60)");
    }

    // ── 2. Selo nos 3 buckets de tempo ───────────────────────────────────────

    @Test
    void historico_selo_agil_quando_tempo_resposta_menor_que_60_minutos() {
        UUID fornecedorId = criarFornecedor("Fornecedor Ágil", FornecedorStatus.ATIVO);
        criarParticipacaoConfirmada(fornecedorId, 2, 2, 30);

        FornecedorHistoricoResponse resposta = doFornecedor(historico(), fornecedorId);

        assertEquals(SeloConfiabilidade.AGIL, resposta.selo());
        assertEquals(1, resposta.cotacoesParticipadas());
    }

    @Test
    void historico_selo_regular_quando_tempo_resposta_entre_60_e_180_minutos() {
        UUID fornecedorId = criarFornecedor("Fornecedor Regular", FornecedorStatus.ATIVO);
        criarParticipacaoConfirmada(fornecedorId, 2, 2, 90);

        FornecedorHistoricoResponse resposta = doFornecedor(historico(), fornecedorId);

        assertEquals(SeloConfiabilidade.REGULAR, resposta.selo());
    }

    @Test
    void historico_selo_atrasa_quando_tempo_resposta_maior_ou_igual_a_180_minutos() {
        UUID fornecedorId = criarFornecedor("Fornecedor Atrasado", FornecedorStatus.ATIVO);
        criarParticipacaoConfirmada(fornecedorId, 2, 2, 200);

        FornecedorHistoricoResponse resposta = doFornecedor(historico(), fornecedorId);

        assertEquals(SeloConfiabilidade.ATRASA, resposta.selo());
    }

    // ── 3. Cobertura média ────────────────────────────────────────────────────

    @Test
    void historico_coberturaMediaPct_calculada_como_percentual_de_itens_cotados_sobre_lista_base() {
        UUID fornecedorId = criarFornecedor("Fornecedor Cobertura", FornecedorStatus.ATIVO);
        // 4 itens na lista base, fornecedor cotou 1 com OK -> 25%.
        criarParticipacaoConfirmada(fornecedorId, 4, 1, 10);

        FornecedorHistoricoResponse resposta = doFornecedor(historico(), fornecedorId);

        assertEquals(1, resposta.cotacoesParticipadas());
        assertEquals(25.0, resposta.coberturaMediaPct(), 0.01);
    }

    // ── 4. Só fornecedor ATIVO aparece ───────────────────────────────────────

    @Test
    void historico_apenas_fornecedor_ativo_aparece_inativo_e_pendente_dados_ficam_de_fora() {
        UUID fornecedorAtivo = criarFornecedor("Fornecedor Ativo", FornecedorStatus.ATIVO);
        UUID fornecedorInativo = criarFornecedor("Fornecedor Inativo", FornecedorStatus.INATIVO);
        UUID fornecedorPendente = criarFornecedor("Fornecedor Pendente Dados", FornecedorStatus.PENDENTE_DADOS);

        List<UUID> idsRetornados = historico().stream().map(FornecedorHistoricoResponse::fornecedorId).toList();

        assertTrue(idsRetornados.contains(fornecedorAtivo));
        assertFalse(idsRetornados.contains(fornecedorInativo), "Fornecedor INATIVO não deve aparecer no Hall histórico");
        assertFalse(idsRetornados.contains(fornecedorPendente), "Fornecedor PENDENTE_DADOS não deve aparecer no Hall histórico");
    }
}
