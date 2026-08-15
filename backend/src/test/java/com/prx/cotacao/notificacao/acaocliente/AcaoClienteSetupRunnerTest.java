package com.prx.cotacao.notificacao.acaocliente;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import com.prx.cotacao.shared.setup.AcaoClienteSetupRunner;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AcaoClienteSetupRunner}: {@code @SpringBootTest} com Postgres real (perfil
 * dev), mesmo padrão de {@code AdminBootstrapRunnerTest} — invoca
 * {@code afterSingletonsInstantiated()} diretamente, mas aqui contra banco real (não
 * mockado), porque a lógica sob teste é essencialmente SQL (JdbcTemplate cru, RLS,
 * atomicidade via TransactionTemplate) que um mock de repository não reproduziria.
 *
 * <p>O runner já rodou uma vez no boot deste mesmo contexto Spring (schema sempre
 * vazio de dado legado no boot dos outros testes da suíte) — os 5 cenários de
 * {@code acao_cliente} já existem quando esta classe roda. Cada teste aqui insere seu
 * próprio dado legado (linha de {@code template_mensagem} com {@code acao_cliente_id
 * IS NULL}) via SQL cru antes de invocar o runner de novo, simulando o boot de uma
 * versão anterior ao Prompt 20 sendo atualizada.</p>
 *
 * <p>Todo INSERT/SELECT/DELETE direto contra {@code template_mensagem} e
 * {@code template_mensagem_legado_backup} precisa rodar com
 * {@code TenantContext.setAdmin(true)} (via {@link #comoAdmin}) — a policy
 * {@code tenant_isolation} de {@code template_mensagem} (V28) e a policy admin-only de
 * {@code template_mensagem_legado_backup} (V30) bloqueiam leitura/escrita fora de
 * contexto admin ou do tenant dono da linha.</p>
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AcaoClienteSetupRunnerTest {

    @Autowired private AcaoClienteSetupRunner runner;
    @Autowired private AcaoClienteCenarioRepository cenarioRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void criarTenants() {
        comoAdmin(() -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - AcaoClienteSetupRunner");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - AcaoClienteSetupRunner");
            b.setStatus(TenantStatus.TRIAL);
            tenantBId = tenantRepository.save(b).getId();
            return null;
        });
    }

    @AfterEach
    void limpar() {
        comoAdmin(() -> {
            jdbc.update("DELETE FROM template_mensagem_legado_backup WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM template_mensagem WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoAdmin(Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID cenario(AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        AcaoCliente c = resultado == null
                ? cenarioRepository.findByAcaoAndResultadoIsNull(acao)
                        .orElseThrow(() -> new IllegalStateException("acao_cliente não semeado: " + acao))
                : cenarioRepository.findByAcaoAndResultado(acao, resultado)
                        .orElseThrow(() -> new IllegalStateException("acao_cliente não semeado: " + acao + "/" + resultado));
        return c.getId();
    }

    // Simula um template configurado antes do Prompt 20 (acao_cliente_id ainda não
    // existia): preenche tenant_id/resultado/nome_template_meta/idioma/conteudo,
    // deixa acao_cliente_id de fora (NULL por ausência) e parametros_ordenados usa o
    // default '{}' da coluna (V30).
    private UUID inserirTemplateLegado(UUID tenantId, String resultado, String nomeTemplateMeta, String conteudo) {
        return comoAdmin(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO template_mensagem (id, tenant_id, resultado, nome_template_meta, idioma, conteudo) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    id, tenantId, resultado, nomeTemplateMeta, "pt_BR", conteudo);
            return id;
        });
    }

    private Map<String, Object> buscarTemplate(UUID templateId) {
        return comoAdmin(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM template_mensagem WHERE id = ?", templateId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    private List<Map<String, Object>> buscarBackups(UUID templateId) {
        return comoAdmin(() -> jdbc.queryForList(
                "SELECT * FROM template_mensagem_legado_backup WHERE template_mensagem_id = ?", templateId));
    }

    // ── 1. Seed idempotente ─────────────────────────────────────────────────

    @Test
    void seedCenarios_rodandoDeNovoAposOBootDoContexto_naoDuplicaNenhumDos5Cenarios() {
        long antes = cenarioRepository.count();
        assertEquals(5, antes, "os 5 cenários (INSERIR_PRODUTOS×SUCESSO/ERRO, "
                + "REGISTRAR_RESPOSTA×SUCESSO/ERRO, NAO_IDENTIFICADO) já deveriam existir do boot do contexto");

        runner.afterSingletonsInstantiated();

        long depois = cenarioRepository.count();
        assertEquals(5, depois, "rodar o runner de novo não pode duplicar nenhuma linha de acao_cliente");
        assertEquals(antes, depois);
    }

    // ── 2. Backfill — só ERRO existe pro tenant ─────────────────────────────

    @Test
    void backfill_somenteErroExistente_promovidoParaNaoIdentificadoSemBackup() {
        UUID templateId = inserirTemplateLegado(tenantAId, "ERRO", "tpl_erro_legado", "conteúdo de erro legado");

        runner.afterSingletonsInstantiated();

        UUID naoIdentificadoId = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        Map<String, Object> template = buscarTemplate(templateId);
        assertEquals(naoIdentificadoId, template.get("acao_cliente_id"),
                "único template legado do tenant (ERRO) deve ser promovido a NAO_IDENTIFICADO");
        assertTrue(buscarBackups(templateId).isEmpty(),
                "nenhum backup deveria ser gravado quando só existia 1 linha legada (nada foi descartado)");
    }

    // ── 3. Backfill — só SUCESSO existe pro tenant ──────────────────────────

    @Test
    void backfill_somenteSucessoExistente_promovidoParaNaoIdentificadoSemBackup() {
        UUID templateId = inserirTemplateLegado(tenantAId, "SUCESSO", "tpl_sucesso_legado", "conteúdo de sucesso legado");

        runner.afterSingletonsInstantiated();

        UUID naoIdentificadoId = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        Map<String, Object> template = buscarTemplate(templateId);
        assertEquals(naoIdentificadoId, template.get("acao_cliente_id"),
                "único template legado do tenant (SUCESSO) também deve ser promovido, nunca descartado");
        assertTrue(buscarBackups(templateId).isEmpty(),
                "nenhum backup deveria ser gravado quando só existia 1 linha legada (nada foi descartado)");
    }

    // ── 4. Backfill — ERRO e SUCESSO coexistem no mesmo tenant ─────────────

    @Test
    void backfill_erroESucessoCoexistindo_promoveErroEArquivaSucessoComBackup() {
        UUID idErro = inserirTemplateLegado(tenantAId, "ERRO", "tpl_erro_legado", "conteúdo de erro");
        UUID idSucesso = inserirTemplateLegado(tenantAId, "SUCESSO", "tpl_sucesso_legado", "conteúdo de sucesso");

        runner.afterSingletonsInstantiated();

        UUID naoIdentificadoId = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);

        Map<String, Object> templateErro = buscarTemplate(idErro);
        assertEquals(naoIdentificadoId, templateErro.get("acao_cliente_id"),
                "linha de ERRO vence e é promovida a NAO_IDENTIFICADO");

        assertFalse(buscarTemplate(idSucesso) != null, "linha de SUCESSO deve ser deletada de template_mensagem");

        List<Map<String, Object>> backups = buscarBackups(idSucesso);
        assertEquals(1, backups.size(), "linha de SUCESSO descartada deve ter exatamente 1 cópia em template_mensagem_legado_backup");
        Map<String, Object> backup = backups.get(0);
        assertEquals(tenantAId, backup.get("tenant_id"));
        assertEquals("SUCESSO", backup.get("resultado"));
        assertEquals("tpl_sucesso_legado", backup.get("nome_template_meta"));
        assertEquals("pt_BR", backup.get("idioma"));
        assertEquals("conteúdo de sucesso", backup.get("conteudo"));
    }

    // ── 5. Backfill — nenhum dado legado ────────────────────────────────────

    @Test
    void backfill_semNenhumDadoLegado_naoQuebra() {
        assertDoesNotThrow(runner::afterSingletonsInstantiated,
                "sem nenhuma linha com acao_cliente_id NULL, o backfill deve ser um no-op");
    }

    // ── 6. Isolamento entre tenants ─────────────────────────────────────────

    @Test
    void backfill_doisTenantsComParErroSucessoCadaUm_naoMisturaDados() {
        UUID erroA = inserirTemplateLegado(tenantAId, "ERRO", "tpl_erro_a", "conteúdo erro A");
        UUID sucessoA = inserirTemplateLegado(tenantAId, "SUCESSO", "tpl_sucesso_a", "conteúdo sucesso A");
        UUID erroB = inserirTemplateLegado(tenantBId, "ERRO", "tpl_erro_b", "conteúdo erro B");
        UUID sucessoB = inserirTemplateLegado(tenantBId, "SUCESSO", "tpl_sucesso_b", "conteúdo sucesso B");

        runner.afterSingletonsInstantiated();

        UUID naoIdentificadoId = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);

        // Linhas de ERRO promovidas, cada uma no seu próprio tenant.
        Map<String, Object> templateErroA = buscarTemplate(erroA);
        assertEquals(naoIdentificadoId, templateErroA.get("acao_cliente_id"));
        assertEquals(tenantAId, templateErroA.get("tenant_id"));

        Map<String, Object> templateErroB = buscarTemplate(erroB);
        assertEquals(naoIdentificadoId, templateErroB.get("acao_cliente_id"));
        assertEquals(tenantBId, templateErroB.get("tenant_id"));

        // Linhas de SUCESSO deletadas de template_mensagem.
        assertFalse(buscarTemplate(sucessoA) != null, "SUCESSO do tenant A deve ser deletado");
        assertFalse(buscarTemplate(sucessoB) != null, "SUCESSO do tenant B deve ser deletado");

        // Backup de cada tenant existe, com o tenant_id e o conteúdo corretos — nunca
        // cruzado com o outro tenant.
        List<Map<String, Object>> backupsA = buscarBackups(sucessoA);
        assertEquals(1, backupsA.size());
        assertEquals(tenantAId, backupsA.get(0).get("tenant_id"));
        assertEquals("conteúdo sucesso A", backupsA.get(0).get("conteudo"));

        List<Map<String, Object>> backupsB = buscarBackups(sucessoB);
        assertEquals(1, backupsB.size());
        assertEquals(tenantBId, backupsB.get(0).get("tenant_id"));
        assertEquals("conteúdo sucesso B", backupsB.get(0).get("conteudo"));

        // Nenhum backup do tenant A referencia a linha de SUCESSO do tenant B e vice-versa.
        assertTrue(buscarBackups(sucessoB).stream().noneMatch(b -> tenantAId.equals(b.get("tenant_id"))));
        assertTrue(buscarBackups(sucessoA).stream().noneMatch(b -> tenantBId.equals(b.get("tenant_id"))));
    }
}
