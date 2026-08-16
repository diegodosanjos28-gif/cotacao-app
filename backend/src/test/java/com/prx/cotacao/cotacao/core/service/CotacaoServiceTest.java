package com.prx.cotacao.cotacao.core.service;

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

import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Testes de CotacaoService.finalizar: assinatura passou a receber o CenarioSelecionado
 * escolhido pelo usuário e gravá-lo na cotação (antes o campo nunca era setado).
 *
 * Pré-requisito: Postgres local acessível (mesmo perfil "dev" dos demais testes de
 * integração deste módulo).
 */
@SpringBootTest
@ActiveProfiles("dev")
class CotacaoServiceTest {

    @Autowired private CotacaoService cotacaoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Cotacao Service Test', 'TRIAL')",
                    tenantId);
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao(CotacaoStatus status) {
        return criarCotacao(status, CanalOrigem.WEB, true);
    }

    private UUID criarCotacao(CotacaoStatus status, CanalOrigem canalOrigem, boolean listaRevisada) {
        return criarCotacao(status, canalOrigem, listaRevisada, "Cotação Finalizar Test " + UUID.randomUUID());
    }

    private UUID criarCotacao(CotacaoStatus status, CanalOrigem canalOrigem, boolean listaRevisada, String titulo) {
        Cotacao c = new Cotacao();
        c.setTitulo(titulo);
        c.setStatus(status);
        c.setCanalOrigem(canalOrigem);
        c.setListaRevisada(listaRevisada);
        return cotacaoRepository.save(c).getId();
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void finalizar_grava_status_finalizada_e_cenario_selecionado() {
        UUID cotacaoId = comoTenant(() -> criarCotacao(CotacaoStatus.EM_ANDAMENTO));

        Cotacao resultado = comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.EQUILIBRADA));

        assertEquals(CotacaoStatus.FINALIZADA, resultado.getStatus());
        assertEquals(CenarioSelecionado.EQUILIBRADA, resultado.getCenarioSelecionado());
        assertNotNull(resultado.getFinalizadaEm());

        // Confirma que persistiu de fato, não só no objeto em memória retornado.
        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));
        assertEquals(CotacaoStatus.FINALIZADA, recarregada.getStatus());
        assertEquals(CenarioSelecionado.EQUILIBRADA, recarregada.getCenarioSelecionado());
    }

    @Test
    void finalizar_com_cenario_diferente_grava_o_cenario_correspondente() {
        UUID cotacaoId = comoTenant(() -> criarCotacao(CotacaoStatus.EM_ANDAMENTO));

        comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.MELHOR_PRAZO));

        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));
        assertEquals(CenarioSelecionado.MELHOR_PRAZO, recarregada.getCenarioSelecionado());
    }

    @Test
    void finalizar_cotacao_ja_finalizada_lanca_conflict_exception() {
        UUID cotacaoId = comoTenant(() -> criarCotacao(CotacaoStatus.EM_ANDAMENTO));
        comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoService.finalizar(cotacaoId, CenarioSelecionado.EQUILIBRADA)),
                "Finalizar uma cotação já finalizada deve lançar ConflictException");
        assertTrue(ex.getMessage().contains("já finalizada"));

        // O cenário da primeira finalização não deve ter sido sobrescrito pela tentativa que falhou.
        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));
        assertEquals(CenarioSelecionado.MENOR_PRECO, recarregada.getCenarioSelecionado());
    }

    @Test
    void finalizar_cotacao_inexistente_lanca_resource_not_found() {
        UUID idInexistente = UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class,
                () -> comoTenant(() -> cotacaoService.finalizar(idInexistente, CenarioSelecionado.MENOR_PRECO)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cotacao.listaRevisada (V22__add_lista_revisada_cotacao.sql) — sinaliza se o
    // usuário já revisou a lista recebida via WhatsApp (tela "Ajuste de Lista",
    // Fase 3). Cotações web nunca passam por essa tela — nascem TRUE e nunca mudam.

    @Test
    void nova_cotacao_persistida_nasce_com_lista_revisada_true() {
        UUID cotacaoId = comoTenant(() -> criarCotacao(CotacaoStatus.RASCUNHO));

        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));

        assertTrue(recarregada.isListaRevisada(),
                "Cotação web (canal WEB) deve nascer com listaRevisada = true por padrão");
    }

    @Test
    void setar_lista_revisada_false_e_persistido_apos_recarregar() {
        UUID cotacaoId = comoTenant(() -> criarCotacao(CotacaoStatus.RASCUNHO));

        comoTenant(() -> {
            Cotacao c = cotacaoRepository.findByIdOrThrow(cotacaoId);
            c.setListaRevisada(false);
            return cotacaoRepository.save(c);
        });

        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));
        assertFalse(recarregada.isListaRevisada(),
                "listaRevisada = false deve persistir e ser lido de volta do banco, não só em memória");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // concluirAjusteLista — botão "Concluir ajuste e seguir para conferência" da tela
    // Ajuste de Lista (Fase 3 WhatsApp).

    @Test
    void concluir_ajuste_lista_em_cotacao_whatsapp_nao_revisada_vira_true_sem_tocar_status_ou_canal() {
        UUID cotacaoId = comoTenant(
                () -> criarCotacao(CotacaoStatus.EM_ANDAMENTO, CanalOrigem.WHATSAPP, false));

        Cotacao resultado = comoTenant(() -> cotacaoService.concluirAjusteLista(cotacaoId));

        assertTrue(resultado.isListaRevisada());
        assertEquals(CotacaoStatus.EM_ANDAMENTO, resultado.getStatus());
        assertEquals(CanalOrigem.WHATSAPP, resultado.getCanalOrigem());

        Cotacao recarregada = comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId));
        assertTrue(recarregada.isListaRevisada());
        assertEquals(CotacaoStatus.EM_ANDAMENTO, recarregada.getStatus());
        assertEquals(CanalOrigem.WHATSAPP, recarregada.getCanalOrigem());
    }

    @Test
    void concluir_ajuste_lista_ja_revisada_lanca_conflict() {
        UUID cotacaoId = comoTenant(
                () -> criarCotacao(CotacaoStatus.EM_ANDAMENTO, CanalOrigem.WHATSAPP, true));

        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoService.concluirAjusteLista(cotacaoId)));
    }

    @Test
    void concluir_ajuste_lista_em_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = comoTenant(
                () -> criarCotacao(CotacaoStatus.FINALIZADA, CanalOrigem.WHATSAPP, false));

        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoService.concluirAjusteLista(cotacaoId)));
    }

    @Test
    void concluir_ajuste_lista_em_cotacao_web_lanca_conflict() {
        UUID cotacaoId = comoTenant(
                () -> criarCotacao(CotacaoStatus.EM_ANDAMENTO, CanalOrigem.WEB, true));

        assertThrows(ConflictException.class,
                () -> comoTenant(() -> cotacaoService.concluirAjusteLista(cotacaoId)));
    }

    @Test
    void concluir_ajuste_lista_inexistente_lanca_resource_not_found() {
        UUID idInexistente = UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class,
                () -> comoTenant(() -> cotacaoService.concluirAjusteLista(idInexistente)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // listar(Pageable, status, termo) — Prompt 24: filtro server-side por status e
    // por termo de busca (título OU canal), usado pelo lembrete de EM_ANDAMENTO e
    // pela busca da tabela "Todas as cotações" no dashboard.

    @Test
    void listar_sem_status_e_sem_termo_retorna_tudo_do_tenant() {
        comoTenant(() -> criarCotacao(CotacaoStatus.EM_ANDAMENTO));
        comoTenant(() -> criarCotacao(CotacaoStatus.FINALIZADA));

        List<Cotacao> resultado = comoTenant(
                () -> cotacaoService.listar(PageRequest.of(0, 20), null, null).getContent());

        assertEquals(2, resultado.size());
    }

    @Test
    void listar_filtra_por_status() {
        comoTenant(() -> criarCotacao(CotacaoStatus.EM_ANDAMENTO));
        comoTenant(() -> criarCotacao(CotacaoStatus.FINALIZADA));

        List<Cotacao> resultado = comoTenant(
                () -> cotacaoService.listar(PageRequest.of(0, 20), CotacaoStatus.EM_ANDAMENTO, null).getContent());

        assertEquals(1, resultado.size());
        assertEquals(CotacaoStatus.EM_ANDAMENTO, resultado.get(0).getStatus());
    }

    @Test
    void listar_filtra_por_termo_no_titulo() {
        comoTenant(() -> criarCotacao(CotacaoStatus.RASCUNHO, CanalOrigem.WEB, true, "Compra de Hortifruti Semanal"));
        comoTenant(() -> criarCotacao(CotacaoStatus.RASCUNHO, CanalOrigem.WEB, true, "Reposição de Bebidas"));

        List<Cotacao> resultado = comoTenant(
                () -> cotacaoService.listar(PageRequest.of(0, 20), null, "hortifruti").getContent());

        assertEquals(1, resultado.size());
        assertEquals("Compra de Hortifruti Semanal", resultado.get(0).getTitulo());
    }

    @Test
    void listar_filtra_por_termo_no_canal_case_insensitive() {
        comoTenant(() -> criarCotacao(CotacaoStatus.RASCUNHO, CanalOrigem.WHATSAPP, true, "Cotação A"));
        comoTenant(() -> criarCotacao(CotacaoStatus.RASCUNHO, CanalOrigem.WEB, true, "Cotação B"));

        List<Cotacao> resultado = comoTenant(
                () -> cotacaoService.listar(PageRequest.of(0, 20), null, "WhatsApp").getContent());

        assertEquals(1, resultado.size());
        assertEquals(CanalOrigem.WHATSAPP, resultado.get(0).getCanalOrigem());
    }

    @Test
    void listar_combina_status_e_termo() {
        comoTenant(() -> criarCotacao(CotacaoStatus.EM_ANDAMENTO, CanalOrigem.WHATSAPP, true, "Cotação Alfa"));
        comoTenant(() -> criarCotacao(CotacaoStatus.FINALIZADA, CanalOrigem.WHATSAPP, true, "Cotação Alfa"));

        List<Cotacao> resultado = comoTenant(() -> cotacaoService
                .listar(PageRequest.of(0, 20), CotacaoStatus.EM_ANDAMENTO, "alfa").getContent());

        assertEquals(1, resultado.size());
        assertEquals(CotacaoStatus.EM_ANDAMENTO, resultado.get(0).getStatus());
    }
}
