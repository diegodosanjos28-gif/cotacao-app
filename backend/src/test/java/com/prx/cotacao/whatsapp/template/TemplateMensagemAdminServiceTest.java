package com.prx.cotacao.whatsapp.template;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.whatsapp.template.dto.TemplateMensagemAdminRequest;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.service.TemplateMensagemAdminService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateMensagemAdminService}: mesmo padrão de {@code UsuarioAdminServiceTest}
 * — {@code @SpringBootTest} com Postgres real (perfil dev), rodando dentro de
 * {@link #comoAdmin} porque as rotas reais (/admin/tenants/{id}/templates-mensagem) só
 * chegam a este service com {@code TenantContext.setAdmin(true)} (papel ADMIN_PRX),
 * mesma convenção usada para reproduzir a policy de RLS de fato em vigor em runtime
 * para {@code tenant}/{@code template_mensagem}.
 *
 * <p>Cobre: tenant inexistente → 404 (criar/listar); constraint de no-máximo-1-linha-
 * por-(tenant,resultado) → 409; update de template de outro tenant via path errado →
 * 404 (não 403, não vaza existência); listagem isolada por tenant;
 * {@code resultado} imutável após criado, mesmo que o request de atualização traga um
 * valor diferente.</p>
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class TemplateMensagemAdminServiceTest {

    @Autowired private TemplateMensagemAdminService templateAdminService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void criarTenants() {
        comoAdmin(() -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Template Mensagem Admin Service");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Template Mensagem Admin Service");
            b.setStatus(TenantStatus.TRIAL);
            tenantBId = tenantRepository.save(b).getId();
            return null;
        });
    }

    @AfterEach
    void limpar() {
        comoAdmin(() -> {
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

    private TemplateMensagemAdminRequest request(ResultadoNotificacao resultado, String nomeTemplateMeta) {
        return new TemplateMensagemAdminRequest(resultado, nomeTemplateMeta, "pt_BR",
                "conteúdo de exemplo {{1}} {{2}}", "1=tipoMensagem, 2=detalhe", true);
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void criar_comTenantInexistente_lancaResourceNotFoundException() {
        UUID tenantInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantInexistente,
                        request(ResultadoNotificacao.SUCESSO, "tpl_sucesso"))));
    }

    @Test
    void listar_comTenantInexistente_lancaResourceNotFoundException() {
        UUID tenantInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> templateAdminService.listar(tenantInexistente)));
    }

    @Test
    void criar_segundaLinhaParaOMesmoResultadoNoMesmoTenant_lancaConflictException() {
        comoAdmin(() -> templateAdminService.criar(tenantAId, request(ResultadoNotificacao.SUCESSO, "tpl_sucesso_1")));

        assertThrows(ConflictException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantAId,
                        request(ResultadoNotificacao.SUCESSO, "tpl_sucesso_2"))),
                "já existe uma linha para (tenantA, SUCESSO) — constraint UNIQUE(tenant_id, resultado)");
    }

    @Test
    void criar_resultadosDiferentesNoMesmoTenant_naoConflita() {
        TemplateMensagem sucesso = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(ResultadoNotificacao.SUCESSO, "tpl_sucesso")));
        TemplateMensagem erro = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(ResultadoNotificacao.ERRO, "tpl_erro")));

        assertEquals(ResultadoNotificacao.SUCESSO, sucesso.getResultado());
        assertEquals(ResultadoNotificacao.ERRO, erro.getResultado());
    }

    @Test
    void criar_mesmoResultadoEmTenantsDiferentes_naoConflita() {
        TemplateMensagem doA = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(ResultadoNotificacao.SUCESSO, "tpl_a_sucesso")));
        TemplateMensagem doB = comoAdmin(() ->
                templateAdminService.criar(tenantBId, request(ResultadoNotificacao.SUCESSO, "tpl_b_sucesso")));

        assertEquals(tenantAId, doA.getTenantId());
        assertEquals(tenantBId, doB.getTenantId());
    }

    @Test
    void atualizar_templateQuePertenceAOutroTenant_lancaResourceNotFoundExceptionSemVazarExistencia() {
        TemplateMensagem deB = comoAdmin(() ->
                templateAdminService.criar(tenantBId, request(ResultadoNotificacao.SUCESSO, "tpl_do_b")));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> templateAdminService.atualizar(tenantAId, deB.getId(),
                        request(ResultadoNotificacao.SUCESSO, "tpl_tentativa_invasao"))),
                "Template existe, mas pertence ao tenant B — pedir via tenant A no path deve dar 404, não 403/200");
        assertTrue(ex.getMessage().contains(deB.getId().toString()));
    }

    @Test
    void listar_retornaSomenteLinhasDoTenantCerto() {
        comoAdmin(() -> templateAdminService.criar(tenantAId, request(ResultadoNotificacao.SUCESSO, "tpl_a_sucesso")));
        comoAdmin(() -> templateAdminService.criar(tenantAId, request(ResultadoNotificacao.ERRO, "tpl_a_erro")));
        comoAdmin(() -> templateAdminService.criar(tenantBId, request(ResultadoNotificacao.SUCESSO, "tpl_b_sucesso")));

        List<TemplateMensagem> doTenantA = comoAdmin(() -> templateAdminService.listar(tenantAId));

        assertEquals(2, doTenantA.size());
        assertTrue(doTenantA.stream().allMatch(t -> tenantAId.equals(t.getTenantId())));
    }

    @Test
    void atualizar_naoAlteraResultadoMesmoQueVenhaDiferenteNoRequest() {
        TemplateMensagem criado = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(ResultadoNotificacao.SUCESSO, "tpl_original")));

        // Request pedindo ERRO — deve ser ignorado: resultado é imutável após criado.
        TemplateMensagem atualizado = comoAdmin(() -> templateAdminService.atualizar(tenantAId, criado.getId(),
                request(ResultadoNotificacao.ERRO, "tpl_renomeado")));

        assertEquals(ResultadoNotificacao.SUCESSO, atualizado.getResultado(),
                "resultado (a 'vaga' SUCESSO/ERRO) não pode mudar de lugar via atualizar()");
        assertEquals("tpl_renomeado", atualizado.getNomeTemplateMeta(), "campos editáveis devem ter sido aplicados normalmente");
    }
}
