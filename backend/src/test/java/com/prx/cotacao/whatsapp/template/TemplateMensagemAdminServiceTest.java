package com.prx.cotacao.whatsapp.template;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
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
 * para {@code tenant}/{@code template_mensagem}/{@code acao_cliente}.
 *
 * <p>{@code AcaoClienteSetupRunner} já rodou no boot do contexto Spring (mesmo
 * {@code SmartInitializingSingleton} usado em produção) — os 5 cenários de
 * {@code acao_cliente} já existem quando este teste roda, buscados via
 * {@link #cenario}.</p>
 *
 * <p>Cobre: tenant inexistente → 404 (criar/listar); constraint de no-máximo-1-linha-
 * por-(tenant,acao_cliente) → 409; update de template de outro tenant via path errado →
 * 404 (não 403, não vaza existência); listagem isolada por tenant;
 * {@code acaoClienteId} imutável após criado, mesmo que o request de atualização traga
 * um valor diferente; validação de parâmetros no save.</p>
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class TemplateMensagemAdminServiceTest {

    @Autowired private TemplateMensagemAdminService templateAdminService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AcaoClienteCenarioRepository cenarioRepository;
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

    private UUID cenario(AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        AcaoCliente c = resultado == null
                ? cenarioRepository.findByAcaoAndResultadoIsNull(acao)
                        .orElseThrow(() -> new IllegalStateException("acao_cliente não semeado: " + acao))
                : cenarioRepository.findByAcaoAndResultado(acao, resultado)
                        .orElseThrow(() -> new IllegalStateException("acao_cliente não semeado: " + acao + "/" + resultado));
        return c.getId();
    }

    private TemplateMensagemAdminRequest request(UUID acaoClienteId, String nomeTemplateMeta) {
        return new TemplateMensagemAdminRequest(acaoClienteId, nomeTemplateMeta, "pt_BR",
                "conteúdo de exemplo {{tipoMensagem}} {{detalhe}}", null,
                List.of("tipoMensagem", "detalhe"), true);
    }

    private TemplateMensagemAdminRequest request(UUID acaoClienteId, String nomeTemplateMeta, String conteudo,
                                                    List<String> parametrosOrdenados) {
        return new TemplateMensagemAdminRequest(acaoClienteId, nomeTemplateMeta, "pt_BR",
                conteudo, null, parametrosOrdenados, true);
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void criar_comTenantInexistente_lancaResourceNotFoundException() {
        UUID tenantInexistente = UUID.randomUUID();
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantInexistente,
                        request(naoIdentificado, "tpl_sucesso"))));
    }

    @Test
    void listar_comTenantInexistente_lancaResourceNotFoundException() {
        UUID tenantInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> templateAdminService.listar(tenantInexistente)));
    }

    @Test
    void criar_segundaLinhaParaAMesmaAcaoClienteNoMesmoTenant_lancaConflictException() {
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        comoAdmin(() -> templateAdminService.criar(tenantAId, request(naoIdentificado, "tpl_1")));

        assertThrows(ConflictException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantAId, request(naoIdentificado, "tpl_2"))),
                "já existe uma linha para (tenantA, NAO_IDENTIFICADO) — índice único uq_template_mensagem_tenant_acao_cliente");
    }

    @Test
    void criar_acoesClienteDiferentesNoMesmoTenant_naoConflita() {
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        UUID inserirProdutosSucesso = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);

        TemplateMensagem generico = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(naoIdentificado, "tpl_generico")));
        TemplateMensagem especifico = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(inserirProdutosSucesso, "tpl_especifico",
                        "{{totalItens}} itens", List.of("totalItens"))));

        assertEquals(naoIdentificado, generico.getAcaoClienteId());
        assertEquals(inserirProdutosSucesso, especifico.getAcaoClienteId());
    }

    @Test
    void criar_mesmaAcaoClienteEmTenantsDiferentes_naoConflita() {
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);

        TemplateMensagem doA = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(naoIdentificado, "tpl_a")));
        TemplateMensagem doB = comoAdmin(() ->
                templateAdminService.criar(tenantBId, request(naoIdentificado, "tpl_b")));

        assertEquals(tenantAId, doA.getTenantId());
        assertEquals(tenantBId, doB.getTenantId());
    }

    @Test
    void atualizar_templateQuePertenceAOutroTenant_lancaResourceNotFoundExceptionSemVazarExistencia() {
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        TemplateMensagem deB = comoAdmin(() ->
                templateAdminService.criar(tenantBId, request(naoIdentificado, "tpl_do_b")));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> comoAdmin(() -> templateAdminService.atualizar(tenantAId, deB.getId(),
                        request(naoIdentificado, "tpl_tentativa_invasao"))),
                "Template existe, mas pertence ao tenant B — pedir via tenant A no path deve dar 404, não 403/200");
        assertTrue(ex.getMessage().contains(deB.getId().toString()));
    }

    @Test
    void listar_retornaSomenteLinhasDoTenantCerto() {
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        UUID inserirProdutosSucesso = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);
        comoAdmin(() -> templateAdminService.criar(tenantAId, request(naoIdentificado, "tpl_a_generico")));
        comoAdmin(() -> templateAdminService.criar(tenantAId, request(inserirProdutosSucesso, "tpl_a_especifico",
                "{{totalItens}}", List.of("totalItens"))));
        comoAdmin(() -> templateAdminService.criar(tenantBId, request(naoIdentificado, "tpl_b_generico")));

        List<TemplateMensagem> doTenantA = comoAdmin(() -> templateAdminService.listar(tenantAId));

        assertEquals(2, doTenantA.size());
        assertTrue(doTenantA.stream().allMatch(t -> tenantAId.equals(t.getTenantId())));
    }

    @Test
    void atualizar_naoAlteraAcaoClienteMesmoQueVenhaDiferenteNoRequest() {
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        UUID inserirProdutosSucesso = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);
        TemplateMensagem criado = comoAdmin(() ->
                templateAdminService.criar(tenantAId, request(naoIdentificado, "tpl_original")));

        // Request pedindo outra acao_cliente — deve ser ignorado: a vaga é imutável.
        TemplateMensagem atualizado = comoAdmin(() -> templateAdminService.atualizar(tenantAId, criado.getId(),
                request(inserirProdutosSucesso, "tpl_renomeado")));

        assertEquals(naoIdentificado, atualizado.getAcaoClienteId(),
                "acaoClienteId (a 'vaga') não pode mudar de lugar via atualizar()");
        assertEquals("tpl_renomeado", atualizado.getNomeTemplateMeta(), "campos editáveis devem ter sido aplicados normalmente");
    }

    // ── validarParametros() ─────────────────────────────────────────────────

    @Test
    void criar_comTokenNoConteudoForaDoCatalogoDoCenario_lancaIllegalArgumentException() {
        // NAO_IDENTIFICADO => catálogo efetivo é só o genérico (tipoMensagem/detalhe).
        // totalItens só existe no catálogo de INSERIR_PRODUTOS.
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        TemplateMensagemAdminRequest req = request(naoIdentificado, "tpl_token_fora_do_catalogo",
                "olá {{totalItens}}", List.of("totalItens"));

        assertThrows(IllegalArgumentException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantAId, req)),
                "totalItens não pertence ao catálogo genérico (NAO_IDENTIFICADO)");
    }

    @Test
    void criar_comTokenNoConteudoAusenteDeParametrosOrdenados_lancaIllegalArgumentException() {
        // {{detalhe}} está no conteúdo mas não foi adicionado à lista de parâmetros do envio.
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        TemplateMensagemAdminRequest req = request(naoIdentificado, "tpl_token_sem_posicao",
                "olá {{tipoMensagem}} {{detalhe}}", List.of("tipoMensagem"));

        assertThrows(IllegalArgumentException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantAId, req)),
                "detalhe aparece no conteúdo mas não está em parametrosOrdenados");
    }

    @Test
    void criar_comIdentificadorEmParametrosOrdenadosForaDoCatalogo_lancaIllegalArgumentException() {
        // nomeFornecedor não existe no catálogo efetivo de NAO_IDENTIFICADO.
        UUID naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        TemplateMensagemAdminRequest req = request(naoIdentificado, "tpl_parametro_fora_do_catalogo",
                "olá {{tipoMensagem}}", List.of("tipoMensagem", "nomeFornecedor"));

        assertThrows(IllegalArgumentException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantAId, req)),
                "nomeFornecedor em parametrosOrdenados não pertence ao catálogo de NAO_IDENTIFICADO");
    }

    @Test
    void criar_comAcaoEspecificaUsandoParametroExclusivoDoCatalogo_salvaComSucesso() {
        UUID registrarRespostaSucesso = cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO);
        TemplateMensagemAdminRequest req = request(registrarRespostaSucesso, "tpl_resposta_fornecedor",
                "obrigado, {{nomeFornecedor}}! recebemos {{totalItens}} itens para {{cotacaoTitulo}}",
                List.of("nomeFornecedor", "totalItens", "cotacaoTitulo"));

        TemplateMensagem salvo = comoAdmin(() -> templateAdminService.criar(tenantAId, req));

        assertEquals(registrarRespostaSucesso, salvo.getAcaoClienteId());
        assertEquals(List.of("nomeFornecedor", "totalItens", "cotacaoTitulo"), salvo.getParametrosOrdenados());
    }

    @Test
    void criar_segundaLinhaParaAMesmaAcaoEspecificaNoMesmoTenant_lancaConflictException() {
        UUID inserirProdutosSucesso = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);
        TemplateMensagemAdminRequest primeiro = request(inserirProdutosSucesso, "tpl_lista_produtos_1",
                "{{totalItens}} itens", List.of("totalItens"));
        TemplateMensagemAdminRequest segundo = request(inserirProdutosSucesso, "tpl_lista_produtos_2",
                "{{itensReconhecidos}} reconhecidos", List.of("itensReconhecidos"));

        comoAdmin(() -> templateAdminService.criar(tenantAId, primeiro));

        assertThrows(ConflictException.class,
                () -> comoAdmin(() -> templateAdminService.criar(tenantAId, segundo)),
                "já existe uma linha para (tenantA, INSERIR_PRODUTOS×SUCESSO)");
    }
}
