package com.prx.cotacao.fornecedor;

import com.prx.cotacao.fornecedor.dto.FornecedorRequest;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.service.FornecedorService;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
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

/**
 * Achado do usuário (09/08): um fornecedor criado automaticamente pelo WhatsApp nasce
 * {@code PENDENTE_DADOS} e o Mapa de Compra o exclui do cenário Compra Equilibrada
 * ({@code MapaCompraService#filtrarOfertasElegiveis}) até completar prazo de entrega,
 * condição de pagamento e pedido mínimo — mas a tela de edição rápida (Entrada de
 * Dados, {@code FornecedorRespostaBlock}) nunca manda {@code status} no PUT, só os
 * campos comerciais, então o fornecedor nunca saía de {@code PENDENTE_DADOS} mesmo com
 * o cadastro 100% completo. {@link FornecedorService#atualizar} agora promove
 * automaticamente pra {@code ATIVO} quando o status não veio explícito no request E o
 * fornecedor já estava {@code PENDENTE_DADOS} E os 3 campos ficaram completos.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev).
 */
@SpringBootTest
@ActiveProfiles("dev")
class FornecedorAutoPromocaoStatusTest {

    @Autowired private FornecedorService fornecedorService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant Fornecedor Auto Promocao Test");
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
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
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

    private Fornecedor criarPendenteDados(String nome) {
        return comoTenant(() -> fornecedorService.criar(
                new FornecedorRequest(nome, null, null, null, null, FornecedorStatus.PENDENTE_DADOS)));
    }

    @Test
    void atualizar_com_os_3_campos_comerciais_completos_promove_pendente_dados_para_ativo() {
        Fornecedor f = criarPendenteDados("Fornecedor Completar Cadastro");

        Fornecedor atualizado = comoTenant(() -> fornecedorService.atualizar(f.getId(),
                new FornecedorRequest(f.getNome(), "5 dias", "30 dias", new BigDecimal("100.00"), null, null)));

        assertEquals(FornecedorStatus.ATIVO, atualizado.getStatus(),
                "cadastro completo (prazo + pagamento + mínimo) deve promover PENDENTE_DADOS -> ATIVO automaticamente");
    }

    @Test
    void atualizar_com_pedido_minimo_zero_ainda_conta_como_completo() {
        Fornecedor f = criarPendenteDados("Fornecedor Sem Minimo");

        // pedidoMinimoPadrao = 0 é um valor legítimo ("fornecedor sem mínimo"), não
        // "campo não preenchido" — MapaCompraService só trata minimo > 0 como
        // restrição ativa (fornecedorAbaixoDoMinimo).
        Fornecedor atualizado = comoTenant(() -> fornecedorService.atualizar(f.getId(),
                new FornecedorRequest(f.getNome(), "5 dias", "30 dias", BigDecimal.ZERO, null, null)));

        assertEquals(FornecedorStatus.ATIVO, atualizado.getStatus());
    }

    @Test
    void atualizar_com_cadastro_parcial_permanece_pendente_dados() {
        Fornecedor f = criarPendenteDados("Fornecedor Cadastro Parcial");

        Fornecedor atualizado = comoTenant(() -> fornecedorService.atualizar(f.getId(),
                new FornecedorRequest(f.getNome(), "5 dias", null, new BigDecimal("100.00"), null, null)));

        assertEquals(FornecedorStatus.PENDENTE_DADOS, atualizado.getStatus(),
                "condicaoPagamentoPadrao ainda ausente — não deve promover");
    }

    @Test
    void atualizar_com_status_explicito_nao_e_sobrescrito_pela_auto_promocao() {
        Fornecedor f = criarPendenteDados("Fornecedor Status Explicito");

        Fornecedor atualizado = comoTenant(() -> fornecedorService.atualizar(f.getId(),
                new FornecedorRequest(f.getNome(), "5 dias", "30 dias", new BigDecimal("100.00"), null,
                        FornecedorStatus.INATIVO)));

        assertEquals(FornecedorStatus.INATIVO, atualizado.getStatus(),
                "status explícito no request sempre vence — auto-promoção só age quando o caller não decidiu nada");
    }

    @Test
    void atualizar_fornecedor_ja_ativo_nao_e_afetado_pela_logica_de_auto_promocao() {
        Fornecedor f = comoTenant(() -> fornecedorService.criar(
                new FornecedorRequest("Fornecedor Ja Ativo", null, null, null, null, null)));
        assertEquals(FornecedorStatus.ATIVO, f.getStatus());

        Fornecedor atualizado = comoTenant(() -> fornecedorService.atualizar(f.getId(),
                new FornecedorRequest(f.getNome(), "5 dias", "30 dias", new BigDecimal("100.00"), null, null)));

        assertEquals(FornecedorStatus.ATIVO, atualizado.getStatus());
    }
}
