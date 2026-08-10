package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.respostafornecedor.dto.ConfirmarRespostaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ResolucaoItemRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.TipoResolucao;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.service.CotacaoFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.service.FornecedorRespostaService;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Épico B5 — {@link ConfirmacaoRespostaService#confirmar}: persistência de fato da
 * resposta de um fornecedor em cotacao_produto_fornecedor. Recomputa o mesmo pipeline
 * do preview ({@link FornecedorRespostaService#gerarPreview}, ver
 * FornecedorRespostaServiceTest para o padrão de setup) a partir do texto reenviado.
 *
 * <p>Cobre em especial as 3 correções feitas na revisão de segurança desta mudança
 * (ver ConfirmacaoRespostaService — comentários "Achado do security-reviewer"):
 * resolução só se aplica a itens REVISAR, precoInformado da resolução rejeita
 * valores <= 0, e SELECIONAR_CANDIDATO nunca aceita override de preço do cliente.
 * Ver /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md (épico B5).
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ConfirmacaoRespostaServiceTest {

    @Autowired private ConfirmacaoRespostaService confirmacaoRespostaService;
    @Autowired private FornecedorRespostaService fornecedorRespostaService;
    @Autowired private CotacaoFornecedorService cotacaoFornecedorService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
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
            t.setNomeFantasia("Tenant ConfirmacaoResposta Test");
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
                    "(SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
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

    // ── Helpers (mesmo padrão de FornecedorRespostaServiceTest) ─────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação ConfirmacaoResposta Test");
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

    private UUID adicionarItemBase(UUID cotacaoId, String textoOriginal, int ordem) {
        return comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal(textoOriginal);
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(ordem);
            return cotacaoProdutoRepository.save(cp).getId();
        });
    }

    private void adicionarFornecedorAsCotacao(UUID cotacaoId, UUID fornecedorId) {
        comoTenant(() -> cotacaoFornecedorService.adicionar(
                cotacaoId, new com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest(fornecedorId, null)));
    }

    private List<ItemRespostaResponse> confirmar(UUID cotacaoId, UUID fornecedorId, String texto,
                                                  List<ResolucaoItemRequest> resolucoes) {
        return comoTenant(() -> confirmacaoRespostaService.confirmar(
                cotacaoId, fornecedorId, new ConfirmarRespostaRequest(texto, resolucoes)));
    }

    private List<CotacaoProdutoFornecedor> linhasPersistidas(UUID cotacaoId, UUID fornecedorId) {
        return comoTenant(() -> cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId));
    }

    private CotacaoFornecedorStatus statusFornecedorNaCotacao(UUID cotacaoId, UUID fornecedorId) {
        return comoTenant(() -> cfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                .orElseThrow().getStatus());
    }

    private List<CotacaoProduto> itensBase(UUID cotacaoId) {
        return comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
    }

    // ── 1. Resolução só se aplica a itens REVISAR (achado do security-reviewer) ─

    @Test
    void resolucao_para_item_classificado_como_ok_lanca_excecao_e_nao_persiste_nada() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Resolucao Item OK");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // "Sazon Legumes 60g - R$ 2,89" bate exato com o item base -> classificado OK,
        // preço real (2.89) vem do texto. Uma resolução anexada a este itemBaseId não é
        // aplicável (não está em Revisar) — antes da correção, isso sobrescrevia
        // silenciosamente o preço com o valor arbitrário do cliente.
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Sazon Legumes 60g - preço forjado",
                new BigDecimal("999.99"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89", resolucoes));

        // A chamada inteira falha antes do commit — nada foi persistido.
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty(),
                "Resolução inaplicável a item OK deve abortar a transação inteira, sem gravar nada");
    }

    @Test
    void resolucao_para_item_classificado_como_ok_lanca_excecao() {
        // A trava que importa: item que a recomputação classificou como OK não aceita
        // NADA vindo do cliente — é o vetor pelo qual se fabricaria preço para um item
        // que o servidor já considerou resolvido sem intervenção humana.
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Resolucao Item OK");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Ketchup Heinz 400g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Ketchup Heinz 400g - preço forjado",
                new BigDecimal("500.00"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Ketchup Heinz 400g - R$ 8,90", resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    @Test
    void resolucao_para_item_classificado_como_atencao_e_aceita() {
        // Marca diferente da lista base -> ATENCAO (BRAND_CHANGED). A tela oferece
        // Aceitar/Editar/Recusar nessas linhas, então a resolução TEM que ser aceita —
        // antes o backend recusava e a confirmação inteira caía com "não está em
        // Revisar" depois de o operador ter resolvido tudo o que a tela pedia.
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Resolucao Item Atencao");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Ketchup Heinz 400g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Ketchup Predilecta 400g",
                new BigDecimal("9.50"), null, null, null));

        confirmar(cotacaoId, fornecedorId, "Ketchup Predilecta 400g - R$ 8,90", resolucoes);

        List<CotacaoProdutoFornecedor> linhas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, linhas.size());
        assertEquals(0, new BigDecimal("9.50").compareTo(linhas.get(0).getPrecoInformado()));
    }

    // ── 2. precoInformado da resolução rejeita valores <= 0 (validação de bean) ─

    private Set<ConstraintViolation<ResolucaoItemRequest>> validar(ResolucaoItemRequest r) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(r);
        }
    }

    @Test
    void resolucao_com_preco_informado_zero_falha_validacao_de_bean() {
        ResolucaoItemRequest r = new ResolucaoItemRequest(
                UUID.randomUUID(), null, TipoResolucao.EDITAR_MANUAL, "Produto X", BigDecimal.ZERO, null, null, null);

        Set<ConstraintViolation<ResolucaoItemRequest>> violacoes = validar(r);

        assertFalse(violacoes.isEmpty(), "precoInformado = 0 deveria falhar @DecimalMin(inclusive=false)");
        assertTrue(violacoes.stream().anyMatch(v -> "precoInformado".equals(v.getPropertyPath().toString())));
    }

    @Test
    void resolucao_com_preco_informado_negativo_falha_validacao_de_bean() {
        ResolucaoItemRequest r = new ResolucaoItemRequest(
                UUID.randomUUID(), null, TipoResolucao.EDITAR_MANUAL, "Produto X", new BigDecimal("-5.00"), null, null, null);

        Set<ConstraintViolation<ResolucaoItemRequest>> violacoes = validar(r);

        assertFalse(violacoes.isEmpty(), "precoInformado negativo deveria falhar @DecimalMin(inclusive=false)");
        assertTrue(violacoes.stream().anyMatch(v -> "precoInformado".equals(v.getPropertyPath().toString())));
    }

    @Test
    void resolucao_com_preco_informado_positivo_passa_na_validacao_de_bean() {
        ResolucaoItemRequest r = new ResolucaoItemRequest(
                UUID.randomUUID(), null, TipoResolucao.EDITAR_MANUAL, "Produto X", new BigDecimal("0.01"), null, null, null);

        assertTrue(validar(r).isEmpty());
    }

    // ── 3. SELECIONAR_CANDIDATO nunca aceita override de preço do cliente ───────

    @Test
    void selecionar_candidato_ignora_preco_informado_do_cliente_usa_preco_do_candidato_recomputado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Selecionar Candidato");
        // Um único item base para o qual DUAS linhas da resposta batem -> MULTIPLE_OPTIONS
        // (mesmo cenário de ClassificacaoConferenciaServiceTest.mais_de_um_candidato...).
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String linhaCandidato1 = "Sazon Legumes 60g - R$ 2,89";
        String linhaCandidato2 = "Sazon Ervas 60g - R$ 2,99";
        String texto = linhaCandidato1 + "\n" + linhaCandidato2;

        // Confere a pré-condição: realmente vira REVISAR/MULTIPLE_OPTIONS com 2 candidatos.
        var preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
        var itemPreview = preview.itens().stream().findFirst().orElseThrow();
        assertEquals(StatusConferencia.REVISAR, itemPreview.status());
        assertEquals(2, itemPreview.candidatos().size());

        // Cliente aponta o candidato 1 (preço real R$ 2,89) mas anexa um precoInformado
        // bem diferente — só EDITAR_MANUAL pode usar preço arbitrário do cliente.
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.SELECIONAR_CANDIDATO, linhaCandidato1,
                new BigDecimal("500.00"), null, null, null));

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        assertEquals(1, resultado.size());
        assertEquals(new BigDecimal("2.89"), resultado.get(0).precoInformado(),
                "Preço persistido deve ser o do candidato recomputado no servidor, não o precoInformado do request");

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size());
        assertEquals(new BigDecimal("2.89"), persistidas.get(0).getPrecoInformado());
        assertEquals(linhaCandidato1, persistidas.get(0).getTextoOriginal());
    }

    // ── 4. Preservação de snapshot de embalagem em reconfirmação ────────────────

    @Test
    void reconfirmar_sem_nova_embalagem_qtd_preserva_snapshot_da_confirmacao_anterior() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Preserva Embalagem");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Header explícito para não deixar a linha "consultar" (sem R$ nem marcador de
        // sem-estoque) ser engolida como nome do fornecedor pelo parser quando é a
        // única linha de produto do texto (ver pareceLinhaDeProduto).
        String texto = "Fornecedor Preserva Embalagem\nArroz Especial 1kg - consultar"; // REVISAR / PACKAGE_PRICE_SUSPECTED

        // Primeira confirmação: resolve manualmente e informa embalagemQtd=12.
        List<ResolucaoItemRequest> resolucoes1 = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Arroz Especial 1kg - R$ 18,90",
                new BigDecimal("18.90"), 12, null, null));
        confirmar(cotacaoId, fornecedorId, texto, resolucoes1);

        List<CotacaoProdutoFornecedor> primeira = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, primeira.size());
        assertEquals(Integer.valueOf(12), primeira.get(0).getEmbalagemQtdConfirmada());

        // Reprocessa o preview (fluxo real: status volta para PROCESSADO) e reconfirma,
        // desta vez SEM informar embalagemQtd na resolução.
        comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
        assertEquals(CotacaoFornecedorStatus.PROCESSADO, statusFornecedorNaCotacao(cotacaoId, fornecedorId));

        List<ResolucaoItemRequest> resolucoes2 = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Arroz Especial 1kg - R$ 18,90",
                new BigDecimal("18.90"), null, null, null));
        confirmar(cotacaoId, fornecedorId, texto, resolucoes2);

        List<CotacaoProdutoFornecedor> segunda = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, segunda.size());
        assertEquals(Integer.valueOf(12), segunda.get(0).getEmbalagemQtdConfirmada(),
                "Reconfirmação sem embalagemQtd deve preservar o snapshot já confirmado, não perdê-lo");
    }

    @Test
    void reconfirmar_com_embalagem_qtd_diferente_preserva_snapshot_antigo_em_vez_de_sobrescrever() {
        // Regressão (B.0): antes desta correção, um embalagemQtd DIFERENTE vindo de uma
        // rodada de reconfirmação sobrescrevia silenciosamente o snapshot já gravado,
        // violando a garantia de imutabilidade da regra 2 do CLAUDE.md — o único lugar
        // que de fato protegia contra isso era AvisoService.resolver, não este caminho.
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Embalagem Divergente");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Feijão Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Fornecedor Embalagem Divergente\nFeijão Especial 1kg - consultar";

        List<ResolucaoItemRequest> resolucoes1 = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Feijão Especial 1kg - R$ 9,90",
                new BigDecimal("9.90"), 12, null, null));
        confirmar(cotacaoId, fornecedorId, texto, resolucoes1);

        List<CotacaoProdutoFornecedor> primeira = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(Integer.valueOf(12), primeira.get(0).getEmbalagemQtdConfirmada());

        // Reconfirma tentando mudar embalagemQtd para 6 (valor DIFERENTE do já gravado).
        comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
        List<ResolucaoItemRequest> resolucoes2 = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.EDITAR_MANUAL, "Feijão Especial 1kg - R$ 9,90",
                new BigDecimal("9.90"), 6, null, null));
        confirmar(cotacaoId, fornecedorId, texto, resolucoes2);

        List<CotacaoProdutoFornecedor> segunda = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, segunda.size());
        assertEquals(Integer.valueOf(12), segunda.get(0).getEmbalagemQtdConfirmada(),
                "Snapshot já confirmado (12) deve vencer sobre o valor diferente (6) desta rodada");
        // Coluna é NUMERIC(12,2) — 9.90/12=0.8250 é persistido arredondado (HALF_UP) a 0.83.
        assertEquals(0, new BigDecimal("0.83").compareTo(segunda.get(0).getPrecoUnitarioCalculado()),
                () -> "Preço unitário recalculado deve usar o snapshot preservado (12), não o valor descartado (6) — atual: "
                        + segunda.get(0).getPrecoUnitarioCalculado());
    }

    // ── 5. Persiste de fato em cotacao_produto_fornecedor ────────────────────────

    @Test
    void confirmar_persiste_linha_em_cotacao_produto_fornecedor() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Persiste De Fato");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89", List.of());

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size());
        CotacaoProdutoFornecedor cpf = persistidas.get(0);
        assertEquals(new BigDecimal("2.89"), cpf.getPrecoInformado());
        assertEquals(new BigDecimal("2.89"), cpf.getPrecoUnitarioCalculado());
        assertEquals(StatusItem.OK, cpf.getStatus());
        assertEquals(fornecedorId, cpf.getFornecedorId());
    }

    // ── 6. SEM_OFERTA não persiste aquele item ───────────────────────────────────

    @Test
    void sem_oferta_nao_persiste_o_item_correspondente() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Sem Oferta");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Header explícito — ver comentário equivalente no teste de preservação de
        // embalagem: sem ele, a única linha "consultar" (sem R$) seria engolida como
        // nome do fornecedor, e o teste passaria de forma vazia/falso-positiva.
        String texto = "Fornecedor Sem Oferta\nArroz Especial 1kg - consultar"; // REVISAR, exige resolução

        // Sanity check: confirma que realmente é REVISAR antes de testar a resolução
        // (evita um falso positivo se o parsing da linha "consultar" regredir).
        var preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
        assertEquals(1, preview.itens().size());
        assertEquals(StatusConferencia.REVISAR, preview.itens().get(0).status());

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.SEM_OFERTA, null, null, null, null, null));

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        assertTrue(resultado.isEmpty());
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    // ── 7. EXTRA_ITEM (itemBaseId null) nunca persiste ───────────────────────────

    @Test
    void extra_item_sem_item_base_correspondente_nunca_persiste_mesmo_sem_resolucao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Extra Item");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Segunda linha não corresponde a nenhum item base -> EXTRA_ITEM (itemBaseId
        // null), que a classificação sempre marca como REVISAR — mas por não ter
        // itemBaseId, nunca passa pelo gate de resolução obrigatória nem persiste.
        String texto = "Sazon Legumes 60g - R$ 2,89\nDetergente Ype 500ml - R$ 3,50";

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, List.of());

        assertEquals(1, resultado.size(), "Só o item com item base correspondente é persistido");
        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size());
        assertEquals("Sazon Legumes 60g - R$ 2,89", persistidas.get(0).getTextoOriginal());
    }

    // ── 8. REVISAR sem resolução correspondente lança exceção ───────────────────

    @Test
    void item_revisar_sem_resolucao_correspondente_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Revisar Sem Resolucao");
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Header explícito — ver comentário no teste de preservação de embalagem.
        String texto = "Fornecedor Revisar Sem Resolucao\nArroz Especial 1kg - consultar"; // REVISAR

        assertThrows(IllegalArgumentException.class, () ->
                confirmar(cotacaoId, fornecedorId, texto, List.of()));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    // ── 9. Efeito colateral: cotacao_fornecedor.status -> CONFIRMADO ────────────

    @Test
    void confirmar_com_sucesso_marca_fornecedor_como_confirmado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Vira Confirmado");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);
        assertEquals(CotacaoFornecedorStatus.PENDENTE, statusFornecedorNaCotacao(cotacaoId, fornecedorId));

        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89", List.of());

        assertEquals(CotacaoFornecedorStatus.CONFIRMADO, statusFornecedorNaCotacao(cotacaoId, fornecedorId));
    }

    // ── 10. Idempotência: reconfirmar substitui, não duplica ─────────────────────

    @Test
    void reconfirmar_substitui_linhas_anteriores_sem_duplicar() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Reconfirma Idempotente");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89", List.of());
        assertEquals(1, linhasPersistidas(cotacaoId, fornecedorId).size());

        // Reconfirma com preço diferente — não deve duplicar nem violar
        // UNIQUE(cotacao_produto_id, fornecedor_id).
        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 3,50", List.of());

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size(), "Reconfirmar não deve duplicar a linha, e sim substituí-la");
        assertEquals(new BigDecimal("3.50"), persistidas.get(0).getPrecoInformado());
    }

    // ── 11. ACEITAR_SUGESTAO com múltiplos candidatos exige seleção explícita ───

    @Test
    void aceitar_sugestao_em_item_com_multiplos_candidatos_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Aceitar Sugestao Multiplo");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Sazon Legumes 60g - R$ 2,89\nSazon Ervas 60g - R$ 2,99";

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.ACEITAR_SUGESTAO, null, null, null, null, null));

        assertThrows(IllegalArgumentException.class, () ->
                confirmar(cotacaoId, fornecedorId, texto, resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    // ── Regressão: bug de perda de dado no reprocesso parcial ───────────────────
    //
    // Antes da correção, confirmar() fazia deleteAll(existentes) incondicional e só
    // reinseria os itens da mensagem reenviada — qualquer item base já confirmado numa
    // rodada anterior e não mencionado na mensagem atual era apagado em silêncio. Os
    // testes abaixo cobrem o merge (ClassificacaoConferenciaService
    // .mesclarComConfirmacoesAnteriores) chamado por confirmar() antes de decidir o que
    // salvar/apagar.

    @Test
    void reconfirmar_mencionando_so_um_item_preserva_os_outros_4_ja_confirmados() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Reprocesso Parcial 5 Itens");
        UUID item1 = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID item2 = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        UUID item3 = adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 3);
        UUID item4 = adicionarItemBase(cotacaoId, "Macarrao Espaguete 500g", 4);
        UUID item5 = adicionarItemBase(cotacaoId, "Oleo Soja 900ml", 5);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoCompleto = "Sazon Legumes 60g - R$ 2,89\n"
                + "Arroz Especial 1kg - R$ 18,90\n"
                + "Feijao Preto 1kg - R$ 7,50\n"
                + "Macarrao Espaguete 500g - R$ 4,20\n"
                + "Oleo Soja 900ml - R$ 9,99";
        confirmar(cotacaoId, fornecedorId, textoCompleto, List.of());
        assertEquals(5, linhasPersistidas(cotacaoId, fornecedorId).size());

        // Reprocesso só menciona "Arroz Especial 1kg", com preço diferente.
        confirmar(cotacaoId, fornecedorId, "Arroz Especial 1kg - R$ 21,00", List.of());

        List<CotacaoProdutoFornecedor> depois = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(5, depois.size(), "Os 4 itens não mencionados no reprocesso não podem ser apagados");

        Map<UUID, BigDecimal> precoPorItem = depois.stream()
                .collect(Collectors.toMap(CotacaoProdutoFornecedor::getCotacaoProdutoId,
                        CotacaoProdutoFornecedor::getPrecoInformado));
        assertEquals(new BigDecimal("21.00"), precoPorItem.get(item2), "Item reenviado deve ter o novo preço");
        assertEquals(new BigDecimal("2.89"), precoPorItem.get(item1), "Item não reenviado preserva preço anterior");
        assertEquals(new BigDecimal("7.50"), precoPorItem.get(item3), "Item não reenviado preserva preço anterior");
        assertEquals(new BigDecimal("4.20"), precoPorItem.get(item4), "Item não reenviado preserva preço anterior");
        assertEquals(new BigDecimal("9.99"), precoPorItem.get(item5), "Item não reenviado preserva preço anterior");
    }

    @Test
    void reconfirmar_parcial_nao_ressuscita_item_marcado_sem_oferta_na_rodada_anterior() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Sem Oferta Preservado");
        UUID item1 = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID item2 = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        UUID item3 = adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 3);
        UUID item4 = adicionarItemBase(cotacaoId, "Macarrao Espaguete 500g", 4);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Primeira linha tem preço reconhecível, então não é engolida como cabeçalho de
        // fornecedor (ver pareceLinhaDeProduto) — sem precisar de header explícito aqui.
        String textoInicial = "Sazon Legumes 60g - R$ 2,89\n"
                + "Arroz Especial 1kg - R$ 18,90\n"
                + "Feijao Preto 1kg - R$ 7,50\n"
                + "Macarrao Espaguete 500g - consultar";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                item4, null, TipoResolucao.SEM_OFERTA, null, null, null, null, null));
        confirmar(cotacaoId, fornecedorId, textoInicial, resolucoes);

        List<CotacaoProdutoFornecedor> primeira = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(3, primeira.size(), "Item sem oferta não deve gerar linha");
        assertTrue(primeira.stream().noneMatch(l -> l.getCotacaoProdutoId().equals(item4)));

        // Reprocesso menciona só 1 dos 3 itens confirmados, com preço diferente.
        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 3,10", List.of());

        List<CotacaoProdutoFornecedor> depois = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(3, depois.size(), "Os itens confirmados e não reenviados continuam persistidos");
        assertTrue(depois.stream().noneMatch(l -> l.getCotacaoProdutoId().equals(item4)),
                "Item marcado sem oferta antes não pode reaparecer só porque não foi mencionado de novo");

        Map<UUID, BigDecimal> precoPorItem = depois.stream()
                .collect(Collectors.toMap(CotacaoProdutoFornecedor::getCotacaoProdutoId,
                        CotacaoProdutoFornecedor::getPrecoInformado));
        assertEquals(new BigDecimal("3.10"), precoPorItem.get(item1));
        assertEquals(new BigDecimal("18.90"), precoPorItem.get(item2), "Item não reenviado preserva preço anterior");
        assertEquals(new BigDecimal("7.50"), precoPorItem.get(item3), "Item não reenviado preserva preço anterior");
    }

    @Test
    void reconfirmar_com_linha_sem_correspondencia_nao_quebra_preservacao_dos_outros_itens() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Linha Sem Correspondencia");
        UUID item1 = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID item2 = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        confirmar(cotacaoId, fornecedorId,
                "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90", List.of());
        assertEquals(2, linhasPersistidas(cotacaoId, fornecedorId).size());

        // Reprocesso: reenvia item1 com preço novo + uma linha totalmente alheia à
        // lista base (nenhum item corresponde) — não deve lançar exceção nem
        // persistir a linha alheia, e o item2 (não mencionado) deve sobreviver.
        String texto = "Sazon Legumes 60g - R$ 3,10\nProduto Totalmente Diferente XYZ 999g - R$ 50,00";
        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, List.of());

        assertEquals(1, resultado.size(), "Só o item com correspondência real é retornado (o extra não persiste)");

        List<CotacaoProdutoFornecedor> depois = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, depois.size(), "Item2 preservado + item1 atualizado, sem linha para o produto alheio");

        Map<UUID, BigDecimal> precoPorItem = depois.stream()
                .collect(Collectors.toMap(CotacaoProdutoFornecedor::getCotacaoProdutoId,
                        CotacaoProdutoFornecedor::getPrecoInformado));
        assertEquals(new BigDecimal("3.10"), precoPorItem.get(item1));
        assertEquals(new BigDecimal("18.90"), precoPorItem.get(item2), "Item não reenviado preserva preço anterior");
    }

    @Test
    void reconfirmar_com_texto_identico_nao_duplica_linhas_com_2_ou_mais_itens() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Reconfirma Identico");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90";
        confirmar(cotacaoId, fornecedorId, texto, List.of());
        assertEquals(2, linhasPersistidas(cotacaoId, fornecedorId).size());

        // Reenvia exatamente o mesmo texto — idempotência: nem duplica, nem muda preço.
        confirmar(cotacaoId, fornecedorId, texto, List.of());

        List<CotacaoProdutoFornecedor> depois = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, depois.size(), "Reenviar o mesmo texto não deve duplicar linhas");
        List<BigDecimal> precos = depois.stream().map(CotacaoProdutoFornecedor::getPrecoInformado).sorted().toList();
        assertEquals(List.of(new BigDecimal("2.89"), new BigDecimal("18.90")), precos);
    }

    @Test
    void confirmar_em_cotacao_finalizada_lanca_conflict_e_nao_altera_nada() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Cotacao Finalizada Confirmar");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89", List.of());
        assertEquals(1, linhasPersistidas(cotacaoId, fornecedorId).size());

        comoTenant(() -> {
            Cotacao c = cotacaoRepository.findByIdOrThrow(cotacaoId);
            c.setStatus(CotacaoStatus.FINALIZADA);
            return cotacaoRepository.save(c);
        });

        assertThrows(ConflictException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 99,00", List.of()));

        List<CotacaoProdutoFornecedor> depois = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, depois.size(), "Tentativa bloqueada não pode alterar o que já estava persistido");
        assertEquals(new BigDecimal("2.89"), depois.get(0).getPrecoInformado(),
                "Preço da confirmação anterior à finalização deve permanecer intacto");
        assertEquals(CotacaoStatus.FINALIZADA,
                comoTenant(() -> cotacaoRepository.findByIdOrThrow(cotacaoId)).getStatus());
    }

    @Test
    void resolucao_anexada_a_item_preservado_nao_reprocessado_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Resolucao Item Preservado");
        UUID item1 = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID item2 = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        confirmar(cotacaoId, fornecedorId,
                "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90", List.of());
        assertEquals(2, linhasPersistidas(cotacaoId, fornecedorId).size());

        // Reprocesso menciona só item1 — item2 vira "preservado" no merge (não foi
        // reprocessado nesta rodada). Anexar uma resolução ao itemBaseId de item2 é
        // fabricação de dado pelo cliente para um item que o servidor nem reclassificou.
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                item2, null, TipoResolucao.SEM_OFERTA, null, null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 3,10", resolucoes));

        // A transação inteira falha — nem o item1 (que seria legitimamente atualizado)
        // deve ter sido alterado.
        List<CotacaoProdutoFornecedor> depois = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, depois.size());
        Map<UUID, BigDecimal> precoPorItem = depois.stream()
                .collect(Collectors.toMap(CotacaoProdutoFornecedor::getCotacaoProdutoId,
                        CotacaoProdutoFornecedor::getPrecoInformado));
        assertEquals(new BigDecimal("2.89"), precoPorItem.get(item1),
                "Resolução inválida anexada a item preservado deve abortar a transação inteira");
        assertEquals(new BigDecimal("18.90"), precoPorItem.get(item2));
    }

    // ── 12. Peça 2 — ADICIONAR_A_LISTA / ASSOCIAR_A_ITEM (T-15/T-24) ────────────
    //
    // Item extra do fornecedor (sem correspondência na lista base, itemBaseId==null no
    // ItemConferencia) pode virar um CotacaoProduto novo (ADICIONAR_A_LISTA) ou ser
    // associado manualmente a um item base já existente (ASSOCIAR_A_ITEM). Casado por
    // textoOriginalExtra == CandidatoResposta.textoOriginal (texto bruto da linha).

    @Test
    void adicionar_a_lista_cria_novo_item_base_e_persiste_preco_do_fornecedor() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Adiciona Item");
        // Item base sem peso/volume, de propósito: um item extra com unidade de medida
        // igual à do item base (ex.: ambos "1kg") ganha bônus de similaridade
        // (BONUS_MESMA_MEDIDA/BONUS_VALOR_BRUTO_IGUAL em MatchingProdutoService) que pode
        // cruzar o limiar de "provável" e virar falso MULTIPLE_OPTIONS — não relacionado ao
        // comportamento sob teste aqui, então os nomes usados evitam essa colisão.
        adicionarItemBase(cotacaoId, "Arroz", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra = "Feijao Preto - R$ 8,50";
        String texto = "Arroz - R$ 18,90\n" + textoExtra;

        // textoOriginalSelecionado e precoInformado são obrigatórios para item extra
        // (achado do security-reviewer: sem candidato "recomputado no servidor" para um
        // item extra, texto/preço sempre vêm explicitamente da resolução).
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ADICIONAR_A_LISTA,
                "Feijao Preto", new BigDecimal("8.50"), null, null, null));

        confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        List<CotacaoProduto> itens = itensBase(cotacaoId);
        assertEquals(2, itens.size(), "Um novo CotacaoProduto deve ter sido criado para o item extra");
        CotacaoProduto feijao = itens.stream()
                .filter(cp -> cp.getTextoOriginal().toLowerCase().contains("feijao"))
                .findFirst().orElseThrow();
        // Sem qtd/unidade no início do texto do fornecedor -> fallback quantidade=1, unidade="un".
        assertEquals(0, feijao.getQuantidade().compareTo(BigDecimal.ONE));
        assertEquals("un", feijao.getUnidade());

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, persistidas.size());
        CotacaoProdutoFornecedor cpfFeijao = persistidas.stream()
                .filter(p -> p.getCotacaoProdutoId().equals(feijao.getId()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("8.50"), cpfFeijao.getPrecoInformado());
    }

    @Test
    void adicionar_a_lista_com_quantidade_e_unidade_informadas_herda_do_texto_do_fornecedor() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Adiciona Item Com Qtd");
        adicionarItemBase(cotacaoId, "Arroz", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra = "3 cx Feijao Preto - R$ 8,50";
        String texto = "Arroz - R$ 18,90\n" + textoExtra;

        // Quantidade/unidade herdadas vêm de qtdInformada/unInformada do PARSER sobre o
        // texto bruto da linha (textoOriginalExtra) — independente do texto escolhido
        // (textoOriginalSelecionado) na resolução, que pode ser um nome "limpo".
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ADICIONAR_A_LISTA,
                "Feijao Preto", new BigDecimal("8.50"), null, null, null));

        confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        List<CotacaoProduto> itens = itensBase(cotacaoId);
        CotacaoProduto feijao = itens.stream()
                .filter(cp -> cp.getTextoOriginal().toLowerCase().contains("feijao"))
                .findFirst().orElseThrow();
        assertEquals(0, feijao.getQuantidade().compareTo(new BigDecimal("3")),
                "Quantidade do novo item deve vir de qtdInformada do parser (3 cx ...), não hardcoded");
        assertEquals("cx", feijao.getUnidade());
    }

    @Test
    void adicionar_a_lista_reenviando_mesmo_texto_extra_nao_duplica_item_base_dedup_por_nome() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Adiciona Item Dedup");
        adicionarItemBase(cotacaoId, "Arroz", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra = "Feijao Preto - R$ 8,50";
        String texto = "Arroz - R$ 18,90\n" + textoExtra;
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ADICIONAR_A_LISTA,
                "Feijao Preto", new BigDecimal("8.50"), null, null, null));

        confirmar(cotacaoId, fornecedorId, texto, resolucoes);
        assertEquals(2, itensBase(cotacaoId).size());

        // Reprocessamento: reenvia exatamente o mesmo texto, mesma resolução.
        confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        List<CotacaoProduto> itensDepois = itensBase(cotacaoId);
        assertEquals(2, itensDepois.size(),
                "Reenviar o mesmo item extra com ADICIONAR_A_LISTA não deve duplicar o CotacaoProduto (dedup por nome normalizado)");

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, persistidas.size(), "Reprocessamento também não deve duplicar a linha de preço");
    }

    @Test
    void adicionar_a_lista_dois_itens_extra_diferentes_na_mesma_confirmacao_geram_ordens_sequenciais() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Dois Itens Extra");
        adicionarItemBase(cotacaoId, "Arroz", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra1 = "Feijao Preto - R$ 8,50";
        String textoExtra2 = "Macarrao Espaguete - R$ 4,20";
        String texto = "Arroz - R$ 18,90\n" + textoExtra1 + "\n" + textoExtra2;

        List<ResolucaoItemRequest> resolucoes = List.of(
                new ResolucaoItemRequest(null, textoExtra1, TipoResolucao.ADICIONAR_A_LISTA,
                        "Feijao Preto", new BigDecimal("8.50"), null, null, null),
                new ResolucaoItemRequest(null, textoExtra2, TipoResolucao.ADICIONAR_A_LISTA,
                        "Macarrao Espaguete", new BigDecimal("4.20"), null, null, null));

        confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        List<CotacaoProduto> itens = itensBase(cotacaoId);
        assertEquals(3, itens.size(), "Os 2 itens extra + o item base original devem existir");
        List<Integer> ordens = itens.stream().map(CotacaoProduto::getOrdem).sorted().toList();
        assertEquals(List.of(1, 2, 3), ordens,
                "Ordens dos 2 itens extra criados na mesma confirmação não podem colidir");

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(3, persistidas.size());
    }

    @Test
    void associar_a_item_grava_preco_no_item_base_de_destino_a_partir_do_item_extra() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Associa Item");
        adicionarItemBase(cotacaoId, "Arroz", 1);
        // Alvo da associação NÃO é mencionado nesta rodada — nem "Feijao Preto" nem
        // similar aparece no texto do fornecedor, então não recebe classificação própria
        // (itemBaseIdsComClassificacaoReal fica sem ele), condição exigida pela trava do
        // security-reviewer para aceitar uma associação manual.
        UUID itemFeijao = adicionarItemBase(cotacaoId, "Feijao Preto", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Não corresponde automaticamente a nenhum item base -> vira item extra, que o
        // operador associa manualmente ao Feijão Preto.
        String textoExtra = "Detergente Ype 500ml - R$ 3,50";
        String texto = "Arroz - R$ 18,90\n" + textoExtra;

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ASSOCIAR_A_ITEM,
                "Detergente Ype 500ml", new BigDecimal("3.50"), null, null, itemFeijao));

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        assertEquals(2, resultado.size(), "Arroz (match real) + Feijao Preto (associado) devem persistir");
        assertEquals(2, itensBase(cotacaoId).size(), "ASSOCIAR_A_ITEM não cria nenhum item base novo");

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, persistidas.size());
        CotacaoProdutoFornecedor cpfFeijao = persistidas.stream()
                .filter(p -> p.getCotacaoProdutoId().equals(itemFeijao))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("3.50"), cpfFeijao.getPrecoInformado());
    }

    @Test
    void associar_a_item_de_outra_cotacao_lanca_resource_not_found_e_nao_persiste_nada() {
        UUID cotacaoId = criarCotacao();
        UUID outraCotacaoId = criarCotacao();
        UUID itemDeOutraCotacao = adicionarItemBase(outraCotacaoId, "Item De Outra Cotacao", 1);

        UUID fornecedorId = criarFornecedor("Fornecedor Associa Item Outra Cotacao");
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra = "Detergente Ype 500ml - R$ 3,50";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ASSOCIAR_A_ITEM,
                "Detergente Ype 500ml", new BigDecimal("3.50"), null, null, itemDeOutraCotacao));

        assertThrows(ResourceNotFoundException.class, () -> confirmar(
                cotacaoId, fornecedorId, textoExtra, resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
        assertEquals(1, itensBase(cotacaoId).size(), "Nenhum item novo deve ter sido criado na cotação de origem");
    }

    @Test
    void associar_a_item_com_destino_que_ja_tem_classificacao_propria_nesta_rodada_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Associa Item Colidindo Com Match Real");
        // "Arroz" é mencionado e recebe classificação OK real nesta rodada — não pode
        // virar alvo de uma associação manual de item extra (achado do
        // security-reviewer: evita sobrescrever silenciosamente um preço legítimo).
        UUID itemArroz = adicionarItemBase(cotacaoId, "Arroz", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra = "Detergente Ype 500ml - R$ 3,50";
        String texto = "Arroz - R$ 18,90\n" + textoExtra;

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ASSOCIAR_A_ITEM,
                "Detergente Ype 500ml", new BigDecimal("3.50"), null, null, itemArroz));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, texto, resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty(),
                "Resolução inválida deve abortar a transação inteira, inclusive o match real do Arroz");
    }

    // ── 13. Validações de ResolucaoItemRequest para item extra (T-15/T-24) ──────

    @Test
    void resolucao_com_item_base_id_e_texto_original_extra_ao_mesmo_tempo_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Validacao Ambos Preenchidos");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, "Feijao Preto 1kg - R$ 8,50", TipoResolucao.ADICIONAR_A_LISTA,
                null, null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Arroz Especial 1kg - R$ 18,90", resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    @Test
    void associar_a_item_sem_associar_a_item_base_id_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Validacao Associar Sem Destino");
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoExtra = "Detergente Ype 500ml - R$ 3,50";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                null, textoExtra, TipoResolucao.ASSOCIAR_A_ITEM, null, null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, textoExtra, resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    @Test
    void adicionar_a_lista_com_item_base_id_em_vez_de_texto_original_extra_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Validacao Item Base Errado");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.ADICIONAR_A_LISTA, null, null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Arroz Especial 1kg - R$ 18,90", resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    @Test
    void associar_a_item_com_item_base_id_em_vez_de_texto_original_extra_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Validacao Associar Item Base Errado");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID outroItemBaseId = adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, null, TipoResolucao.ASSOCIAR_A_ITEM, null, null, null, null, outroItemBaseId));

        assertThrows(IllegalArgumentException.class, () -> confirmar(
                cotacaoId, fornecedorId, "Arroz Especial 1kg - R$ 18,90", resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    // ── 6. ADICIONAR_CANDIDATO_A_LISTA (B.1: opção não selecionada de MULTIPLE_OPTIONS) ─

    @Test
    void adicionar_candidato_a_lista_cria_novo_item_base_a_partir_de_opcao_nao_selecionada() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Feliz");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String linhaCandidato1 = "Sazon Legumes 60g - R$ 2,89";
        String linhaCandidato2 = "Sazon Ervas 60g - R$ 2,99";
        String texto = linhaCandidato1 + "\n" + linhaCandidato2;

        var preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
        var itemPreview = preview.itens().stream().findFirst().orElseThrow();
        assertEquals(2, itemPreview.candidatos().size());

        // Resolve o item original (candidato 1) via SELECIONAR_CANDIDATO e transforma o
        // candidato 2 (não escolhido) num item novo, com texto/preço EDITADOS pelo
        // operador (decisão confirmada: spin-off aceita edição manual).
        List<ResolucaoItemRequest> resolucoes = List.of(
                new ResolucaoItemRequest(itemBaseId, null, TipoResolucao.SELECIONAR_CANDIDATO,
                        linhaCandidato1, null, null, null, null),
                new ResolucaoItemRequest(itemBaseId, linhaCandidato2, TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                        "Sazon Ervas 60g (editado)", new BigDecimal("3.50"), null, null, null));

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, resolucoes);
        assertEquals(2, resultado.size());

        List<CotacaoProduto> itens = itensBase(cotacaoId);
        assertEquals(2, itens.size(), "Deve ter criado um CotacaoProduto novo além do original");
        CotacaoProduto novoItem = itens.stream()
                .filter(cp -> !cp.getId().equals(itemBaseId))
                .findFirst().orElseThrow();
        assertEquals("Sazon Ervas 60g (editado)", novoItem.getTextoOriginal());

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(2, persistidas.size());
        CotacaoProdutoFornecedor cpfNovo = persistidas.stream()
                .filter(p -> p.getCotacaoProdutoId().equals(novoItem.getId()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("3.50"), cpfNovo.getPrecoInformado());
        assertEquals("Sazon Ervas 60g (editado)", cpfNovo.getTextoOriginal());

        CotacaoProdutoFornecedor cpfOriginal = persistidas.stream()
                .filter(p -> p.getCotacaoProdutoId().equals(itemBaseId))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("2.89"), cpfOriginal.getPrecoInformado(),
                "Item original deve manter o preço do candidato recomputado no servidor, intocado pelo spin-off");
    }

    @Test
    void adicionar_candidato_a_lista_em_item_sem_multiplas_opcoes_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Sem Multiplas Opcoes");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Arroz Especial 1kg - R$ 18,90";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, texto, TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                "Arroz Especial 1kg (novo)", new BigDecimal("18.90"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
        assertEquals(1, itensBase(cotacaoId).size());
    }

    @Test
    void adicionar_candidato_a_lista_com_texto_extra_inexistente_lanca_excecao() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Candidato Fantasma");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String linhaCandidato1 = "Sazon Legumes 60g - R$ 2,89";
        String linhaCandidato2 = "Sazon Ervas 60g - R$ 2,99";
        String texto = linhaCandidato1 + "\n" + linhaCandidato2;

        List<ResolucaoItemRequest> resolucoes = List.of(
                new ResolucaoItemRequest(itemBaseId, null, TipoResolucao.SELECIONAR_CANDIDATO,
                        linhaCandidato1, null, null, null, null),
                new ResolucaoItemRequest(itemBaseId, "Candidato Fabricado Que Nao Existe",
                        TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA, "Item Fabricado", new BigDecimal("1.00"),
                        null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
        assertEquals(1, itensBase(cotacaoId).size());
    }

    @Test
    void adicionar_candidato_a_lista_sem_preco_informado_lanca_excecao_na_validacao_de_entrada() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Sem Preco");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String linhaCandidato1 = "Sazon Legumes 60g - R$ 2,89";
        String linhaCandidato2 = "Sazon Ervas 60g - R$ 2,99";
        String texto = linhaCandidato1 + "\n" + linhaCandidato2;

        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, linhaCandidato2, TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                "Sazon Ervas 60g (editado)", null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
    }

    @Test
    void adicionar_candidato_a_lista_sem_resolver_item_original_bloqueia_confirmacao_inteira() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Sem Resolver Original");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String linhaCandidato1 = "Sazon Legumes 60g - R$ 2,89";
        String linhaCandidato2 = "Sazon Ervas 60g - R$ 2,99";
        String texto = linhaCandidato1 + "\n" + linhaCandidato2;

        // Só spin-off do candidato 2 — o item original (MULTIPLE_OPTIONS/REVISAR)
        // continua sem resolução própria (nenhum SELECIONAR_CANDIDATO enviado): a
        // confirmação inteira deve ser bloqueada, não só o item original.
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemBaseId, linhaCandidato2, TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                "Sazon Ervas 60g (editado)", new BigDecimal("3.50"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
        assertEquals(1, itensBase(cotacaoId).size(),
                "Item novo do spin-off não deve sobreviver ao rollback da confirmação bloqueada");
    }

    // ── 7. B.2: item sem preço não persiste linha nem bloqueia a confirmação ────────

    @Test
    void confirmar_item_identificado_sem_preco_nao_persiste_linha_nem_lanca_erro() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor B2 Sem Preco");
        adicionarItemBase(cotacaoId, "Sal Grosso 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Linha casa com o item base (mesmo nome) mas não traz nenhum preço
        // reconhecível — nem "consultar/a combinar" (isso continua REVISAR).
        String texto = "Fornecedor B2 Sem Preco\nSal Grosso 1kg";

        var preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
        var itemPreview = preview.itens().stream().findFirst().orElseThrow();
        assertEquals(StatusConferencia.OK, itemPreview.status(),
                "Pré-condição: item sem preço não deve escalar para REVISAR (B.2)");
        assertTrue(itemPreview.motivos().isEmpty());

        // Não lança exceção mesmo sem nenhuma resolução — item OK sem preço é skip
        // silencioso, não erro bloqueante.
        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, List.of());

        assertTrue(resultado.isEmpty());
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty(),
                "Item sem preço não deve gerar linha em cotacao_produto_fornecedor");
    }

    // ── 8. Achado do security-reviewer: spinOffItemBaseIdsPendentes fail-loud ──────
    //
    // ADICIONAR_CANDIDATO_A_LISTA referenciando um itemBaseId que não corresponde a
    // NENHUM ItemConferencia desta rodada (id de outra cotação, typo do frontend, ou
    // item que nem foi tocado nem preservado nesta rodada) não pode ser descartado em
    // silêncio — precisa falhar alto, igual ao resto do método.

    @Test
    void adicionar_candidato_a_lista_com_item_base_id_de_outra_cotacao_lanca_excecao_fail_loud() {
        UUID cotacaoId = criarCotacao();
        UUID outraCotacaoId = criarCotacao();
        UUID itemDeOutraCotacao = adicionarItemBase(outraCotacaoId, "Item De Outra Cotacao", 1);

        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Item De Outra Cotacao");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Sazon Legumes 60g - R$ 2,89";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemDeOutraCotacao, "Sazon Legumes 60g - R$ 2,89", TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                "Item Fabricado", new BigDecimal("1.00"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty(),
                "spinOffItemBaseIdsPendentes deve bloquear a confirmação inteira, sem persistir nem o match real");
        assertEquals(1, itensBase(cotacaoId).size(), "Nenhum item novo deve ter sido criado na cotação de origem");
    }

    @Test
    void adicionar_candidato_a_lista_com_item_base_id_inexistente_random_lanca_excecao_fail_loud() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Item Id Aleatorio");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Sazon Legumes 60g - R$ 2,89";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                UUID.randomUUID(), "Sazon Legumes 60g - R$ 2,89", TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                "Item Fabricado", new BigDecimal("1.00"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
        assertEquals(1, itensBase(cotacaoId).size());
    }

    @Test
    void adicionar_candidato_a_lista_com_item_base_id_de_item_preservado_nao_reprocessado_lanca_excecao() {
        // itemBaseId existe NESTA cotação, mas não foi mencionado na mensagem desta
        // rodada nem tem confirmação anterior — "preservado" não se aplica (não havia
        // nada a preservar) e o item simplesmente não entra em `classificados`. Mesmo
        // efeito prático do id de outra cotação/aleatório: cai no fail-loud.
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Item Nao Mencionado");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID itemNaoMencionado = adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Sazon Legumes 60g - R$ 2,89";
        List<ResolucaoItemRequest> resolucoes = List.of(new ResolucaoItemRequest(
                itemNaoMencionado, "Sazon Legumes 60g - R$ 2,89", TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                "Item Fabricado", new BigDecimal("1.00"), null, null, null));

        assertThrows(IllegalArgumentException.class, () -> confirmar(cotacaoId, fornecedorId, texto, resolucoes));

        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty());
        assertEquals(2, itensBase(cotacaoId).size());
    }

    // ── 9. Efeito colateral aceito: 2 spin-offs no MESMO candidato (mesmo itemBaseId +
    // mesmo textoOriginalExtra) na mesma submissão criam 2 CotacaoProduto duplicados —
    // documentado pelo security-reviewer como efeito colateral aceito, não corrigido
    // (o segundo spin-off já não vê o primeiro como dedup-ável porque o item recém
    // criado pelo primeiro já entrou em itemBaseIdsComClassificacaoReal, que é
    // justamente o que resolverOuCriarItemBase usa para EXCLUIR itens de dedup). Este
    // teste apenas documenta o comportamento atual, não afirma que é o desejado.

    @Test
    void dois_spin_offs_do_mesmo_candidato_na_mesma_submissao_criam_dois_itens_duplicados() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor SpinOff Duplicado");
        UUID itemBaseId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String linhaCandidato1 = "Sazon Legumes 60g - R$ 2,89";
        String linhaCandidato2 = "Sazon Ervas 60g - R$ 2,99";
        String texto = linhaCandidato1 + "\n" + linhaCandidato2;

        // Duas resoluções ADICIONAR_CANDIDATO_A_LISTA para o MESMO candidato (candidato
        // 2, mesmo textoOriginalExtra) na mesma submissão — nada na validação de entrada
        // impede isso (spinOffsPorItemBase apenas agrupa por itemBaseId numa lista).
        List<ResolucaoItemRequest> resolucoes = List.of(
                new ResolucaoItemRequest(itemBaseId, null, TipoResolucao.SELECIONAR_CANDIDATO,
                        linhaCandidato1, null, null, null, null),
                new ResolucaoItemRequest(itemBaseId, linhaCandidato2, TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                        "Sazon Ervas 60g (editado 1)", new BigDecimal("3.50"), null, null, null),
                new ResolucaoItemRequest(itemBaseId, linhaCandidato2, TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA,
                        "Sazon Ervas 60g (editado 2)", new BigDecimal("3.70"), null, null, null));

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, texto, resolucoes);

        // Original + 2 spin-offs do mesmo candidato = 3 linhas persistidas.
        assertEquals(3, resultado.size());
        List<CotacaoProduto> itens = itensBase(cotacaoId);
        assertEquals(3, itens.size(),
                "Efeito colateral aceito (security-reviewer): 2 spin-offs do mesmo candidato criam 2 "
                        + "CotacaoProduto separados, não deduplicados entre si");
        List<String> nomesNovos = itens.stream()
                .filter(cp -> !cp.getId().equals(itemBaseId))
                .map(CotacaoProduto::getTextoOriginal)
                .sorted()
                .toList();
        assertEquals(List.of("Sazon Ervas 60g (editado 1)", "Sazon Ervas 60g (editado 2)"), nomesNovos);
    }

    @Test
    void confirmar_item_que_perdeu_preco_em_reconfirmacao_remove_linha_existente() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor B2 Perdeu Preco");
        adicionarItemBase(cotacaoId, "Sal Grosso 1kg", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Primeira rodada: fornecedor informa preço, linha é persistida normalmente.
        String textoComPreco = "Fornecedor B2 Perdeu Preco\nSal Grosso 1kg - R$ 12,50";
        confirmar(cotacaoId, fornecedorId, textoComPreco, List.of());
        assertEquals(1, linhasPersistidas(cotacaoId, fornecedorId).size());

        // Segunda rodada: mesmo fornecedor reenvia o item, mas desta vez sem preço —
        // trata como "não informado", revertendo pra ausência de linha (mesmo destino
        // de SEM_OFERTA), não como erro nem como preservação do preço antigo.
        String textoSemPreco = "Fornecedor B2 Perdeu Preco\nSal Grosso 1kg";
        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId, textoSemPreco, List.of());

        assertTrue(resultado.isEmpty());
        assertTrue(linhasPersistidas(cotacaoId, fornecedorId).isEmpty(),
                "Linha confirmada anteriormente deve ser removida quando o preço deixa de ser informado");
    }

    // ── Prompt 12: item soft-deletado não pode receber nova resposta confirmada ─

    @Test
    void confirmar_nao_persiste_resposta_associada_a_item_soft_deletado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Item Soft Deletado Confirmar");
        UUID itemVivoId = adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID itemRemovidoId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(itemRemovidoId).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        List<ItemRespostaResponse> resultado = confirmar(cotacaoId, fornecedorId,
                "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90", List.of());

        assertEquals(1, resultado.size(), "Só o item vivo deve ter sido persistido — a linha do item soft-deletado vira item extra e não persiste");

        List<CotacaoProdutoFornecedor> persistidas = linhasPersistidas(cotacaoId, fornecedorId);
        assertEquals(1, persistidas.size());
        assertEquals(itemVivoId, persistidas.get(0).getCotacaoProdutoId());
    }
}
