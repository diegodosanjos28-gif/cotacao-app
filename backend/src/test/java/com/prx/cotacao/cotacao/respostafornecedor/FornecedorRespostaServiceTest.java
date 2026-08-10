package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.respostafornecedor.dto.ConfirmarRespostaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemConferenciaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
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

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Épico B4 — {@link FornecedorRespostaService#gerarPreview}: pipeline de preview
 * (parser + matching + classificação) que NÃO persiste em cotacao_produto_fornecedor,
 * só recalcula a Conferência a cada chamada e marca cotacao_fornecedor→PROCESSADO como
 * único efeito colateral. Ver
 * /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md, seções 1/3/4.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5433.
 */
@SpringBootTest
@ActiveProfiles("dev")
class FornecedorRespostaServiceTest {

    @Autowired private FornecedorRespostaService fornecedorRespostaService;
    @Autowired private ConfirmacaoRespostaService confirmacaoRespostaService;
    @Autowired private CotacaoFornecedorService cotacaoFornecedorService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
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
            t.setNomeFantasia("Tenant FornecedorResposta Test");
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
            c.setTitulo("Cotação FornecedorResposta Test");
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

    /** Associa o fornecedor à cotação (pré-requisito de gerarPreview), status PENDENTE. */
    private void adicionarFornecedorAsCotacao(UUID cotacaoId, UUID fornecedorId) {
        comoTenant(() -> cotacaoFornecedorService.adicionar(
                cotacaoId, new com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest(fornecedorId, null)));
    }

    private CotacaoFornecedorStatus statusFornecedorNaCotacao(UUID cotacaoId, UUID fornecedorId) {
        return comoTenant(() -> cfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                .orElseThrow().getStatus());
    }

    /** Persiste de fato via ConfirmacaoRespostaService — usado só para montar estado
     * prévio de confirmação antes de exercitar o preview de um reprocesso parcial. */
    private void confirmar(UUID cotacaoId, UUID fornecedorId, String texto) {
        comoTenant(() -> confirmacaoRespostaService.confirmar(
                cotacaoId, fornecedorId, new ConfirmarRespostaRequest(texto, List.of())));
    }

    // ── 1. Não persiste nada em cotacao_produto_fornecedor ──────────────────

    @Test
    void gerarPreview_nao_persiste_nada_em_cotacao_produto_fornecedor() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Preview Não Persiste");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cotacao_produto_fornecedor cpf " +
                        "JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id " +
                        "WHERE cp.cotacao_id = ? AND cpf.fornecedor_id = ?",
                Integer.class, cotacaoId, fornecedorId);
        assertEquals(0, count, "gerarPreview não deve gravar nada em cotacao_produto_fornecedor");
    }

    // ── 2. Pré-requisito: fornecedor precisa ter sido adicionado à cotação ──

    @Test
    void gerarPreview_para_fornecedor_nao_adicionado_a_cotacao_lanca_not_found() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Nunca Adicionado");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        // Note: NÃO chama adicionarFornecedorAsCotacao — não existe cotacao_fornecedor.

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89")));
    }

    // ── 3. Efeito colateral: cotacao_fornecedor.status → PROCESSADO ─────────

    @Test
    void gerarPreview_marca_fornecedor_pendente_como_processado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Pendente Para Processado");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);
        assertEquals(CotacaoFornecedorStatus.PENDENTE, statusFornecedorNaCotacao(cotacaoId, fornecedorId));

        comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89"));

        assertEquals(CotacaoFornecedorStatus.PROCESSADO, statusFornecedorNaCotacao(cotacaoId, fornecedorId));
    }

    @Test
    void reprocessar_fornecedor_ja_confirmado_desconfirma_e_volta_a_processado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Confirmado Para Processado");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Simula confirmação prévia (feita hoje pelo endpoint /confirmar, fora de escopo
        // deste service) diretamente via jdbc.
        comoTenant(() -> {
            jdbc.update("UPDATE cotacao_fornecedor SET status = 'CONFIRMADO' WHERE cotacao_id = ? AND fornecedor_id = ?",
                    cotacaoId, fornecedorId);
            return null;
        });
        assertEquals(CotacaoFornecedorStatus.CONFIRMADO, statusFornecedorNaCotacao(cotacaoId, fornecedorId));

        comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89"));

        assertEquals(CotacaoFornecedorStatus.PROCESSADO, statusFornecedorNaCotacao(cotacaoId, fornecedorId),
                "Reprocessar um fornecedor já confirmado deve 'desconfirmá-lo', exigindo nova confirmação");
    }

    // ── 4. Cotação finalizada rejeita ────────────────────────────────────────

    @Test
    void gerarPreview_em_cotacao_finalizada_lanca_conflict() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Cotação Finalizada");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        comoTenant(() -> {
            Cotacao c = cotacaoRepository.findByIdOrThrow(cotacaoId);
            c.setStatus(CotacaoStatus.FINALIZADA);
            return cotacaoRepository.save(c);
        });

        assertThrows(ConflictException.class, () -> comoTenant(() ->
                fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89")));
    }

    // ── 5. Pipeline ponta a ponta: OK + REVISAR(PACKAGE_PRICE_SUSPECTED) ────

    @Test
    void pipeline_completo_classifica_match_direto_como_ok_e_linha_consultar_como_revisar() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Pipeline Completo");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarItemBase(cotacaoId, "Bombom Nestle 250g", 3); // sem resposta — não deve aparecer

        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String texto = "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - consultar";

        PreviewRespostaResponse preview = comoTenant(() ->
                fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));

        // Contadores batem com a contagem real dos itens retornados
        assertEquals(preview.itens().size(), preview.contadores().total());
        long okReal = preview.itens().stream().filter(i -> i.status() == StatusConferencia.OK).count();
        long atencaoReal = preview.itens().stream().filter(i -> i.status() == StatusConferencia.ATENCAO).count();
        long revisarReal = preview.itens().stream().filter(i -> i.status() == StatusConferencia.REVISAR).count();
        assertEquals(okReal, preview.contadores().ok());
        assertEquals(atencaoReal, preview.contadores().atencao());
        assertEquals(revisarReal, preview.contadores().revisar());

        // Item base sem resposta ("Bombom Nestle 250g") não aparece (decisão F do plano)
        assertEquals(2, preview.itens().size());

        // Item OK: match direto, sem divergência
        var itemOk = preview.itens().stream()
                .filter(i -> "Sazon Legumes 60g".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertEquals(StatusConferencia.OK, itemOk.status());
        assertTrue(itemOk.motivos().isEmpty());

        // Item REVISAR: linha "consultar" → PACKAGE_PRICE_SUSPECTED
        var itemRevisar = preview.itens().stream()
                .filter(i -> "Arroz Especial 1kg".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertEquals(StatusConferencia.REVISAR, itemRevisar.status());
        assertTrue(itemRevisar.motivos().contains(MotivoConferencia.PACKAGE_PRICE_SUSPECTED));

        assertEquals(1, preview.contadores().ok());
        assertEquals(1, preview.contadores().revisar());
        assertEquals(0, preview.contadores().atencao());
    }

    // ── 6. Item base sem nenhuma resposta não aparece na lista ──────────────

    @Test
    void item_base_sem_nenhuma_resposta_nao_aparece_na_lista_de_itens() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Item Sem Resposta");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarItemBase(cotacaoId, "Bombom Nestle 250g", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Resposta cobre só o primeiro item base.
        PreviewRespostaResponse preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89"));

        assertEquals(1, preview.itens().size());
        assertEquals("Sazon Legumes 60g", preview.itens().get(0).nomeItemBase());
    }

    // ── 6b. Prompt 12: item base soft-deletado não é candidato de matching ─────

    @Test
    void gerarPreview_nao_reconcilia_linha_do_fornecedor_com_item_base_soft_deletado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Item Base Removido");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID itemRemovidoId = comoTenant(() -> {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal("Arroz Especial 1kg");
            cp.setQuantidade(new BigDecimal("10.000"));
            cp.setUnidade("un");
            cp.setOrdem(2);
            return cotacaoProdutoRepository.save(cp).getId();
        });
        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(itemRemovidoId).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        PreviewRespostaResponse preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90"));

        assertEquals(2, preview.itens().size(),
                "Sazon (matched) + Arroz (não pode casar com o item soft-deletado, vira item extra)");

        var itemSazon = preview.itens().stream()
                .filter(i -> "Sazon Legumes 60g".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertEquals(StatusConferencia.OK, itemSazon.status());

        var itemArroz = preview.itens().stream()
                .filter(i -> i.itemBaseId() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Linha do fornecedor para o item soft-deletado deveria virar EXTRA_ITEM, não casar com ele"));
        assertTrue(itemArroz.motivos().contains(MotivoConferencia.EXTRA_ITEM));
        assertNotEquals(itemRemovidoId, itemArroz.itemBaseId());
    }

    // ── 7. Regressão: preview de reprocesso parcial reflete o conjunto completo ─
    // (tocados + preservados), não só o que está na mensagem atual reenviada.

    @Test
    void preview_de_reprocesso_parcial_inclui_itens_preservados_com_flags_corretas() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Preview Reprocesso Parcial");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 3);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Confirma os 3 itens numa rodada anterior.
        confirmar(cotacaoId, fornecedorId,
                "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90\nFeijao Preto 1kg - R$ 7,50");

        // Preview de reprocesso mencionando só "Arroz Especial 1kg", com preço novo.
        PreviewRespostaResponse preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Arroz Especial 1kg - R$ 21,00"));

        assertEquals(3, preview.contadores().total(),
                "Total deve refletir o conjunto completo (tocado + preservados), não só a mensagem atual");
        assertEquals(3, preview.itens().size());

        ItemConferenciaResponse itemTocado = preview.itens().stream()
                .filter(i -> "Arroz Especial 1kg".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertFalse(itemTocado.preservado(), "Item reenviado nesta rodada não é 'preservado'");
        assertEquals(new BigDecimal("18.90"), itemTocado.precoAnteriorConfirmado(),
                "Item tocado deve trazer o preço confirmado na rodada anterior para exibição de comparação");

        ItemConferenciaResponse itemPreservado1 = preview.itens().stream()
                .filter(i -> "Sazon Legumes 60g".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertTrue(itemPreservado1.preservado(), "Item não mencionado nesta rodada deve vir marcado como preservado");
        assertNull(itemPreservado1.precoAnteriorConfirmado(),
                "precoAnteriorConfirmado só é preenchido para itens tocados nesta rodada, não para preservados");
        assertEquals(1, itemPreservado1.candidatos().size());
        assertEquals(new BigDecimal("2.89"), itemPreservado1.candidatos().get(0).precoInformado(),
                "Candidato sintetizado do item preservado reflete o preço já confirmado antes");

        ItemConferenciaResponse itemPreservado2 = preview.itens().stream()
                .filter(i -> "Feijao Preto 1kg".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertTrue(itemPreservado2.preservado());
        assertEquals(new BigDecimal("7.50"), itemPreservado2.candidatos().get(0).precoInformado());
    }

    // ── B.3: preço >=1.5x a referência de outro fornecedor JÁ CONFIRMADO gera
    // PACKAGE_PRICE_SUSPECTED dentro da Conferência (não só como badge no Comparativo) ──

    @Test
    void preview_com_preco_muito_acima_de_fornecedor_ja_confirmado_gera_package_price_suspected() {
        UUID cotacaoId = criarCotacao();
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID fornecedorReferencia = criarFornecedor("Fornecedor Referência");
        UUID fornecedorSuspeito = criarFornecedor("Fornecedor Suspeito");

        // Fluxo sequencial: primeiro fornecedor precisa ser confirmado antes de
        // adicionar o segundo — só então ele vira referência disponível.
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorReferencia);
        confirmar(cotacaoId, fornecedorReferencia, "Arroz Especial 1kg - R$ 20,00");
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorSuspeito);

        // Segundo fornecedor: preço reconhecido normalmente (não "consultar"), mas
        // 40,00 >= 1.5x a referência (20,00) — deve escalar para REVISAR mesmo sem
        // nenhum sinal suspeito no próprio texto.
        PreviewRespostaResponse preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorSuspeito, "Arroz Especial 1kg - R$ 40,00"));

        ItemConferenciaResponse item = preview.itens().stream()
                .filter(i -> "Arroz Especial 1kg".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertEquals(StatusConferencia.REVISAR, item.status());
        assertTrue(item.motivos().contains(MotivoConferencia.PACKAGE_PRICE_SUSPECTED));
    }

    @Test
    void preview_com_preco_normal_perto_de_fornecedor_ja_confirmado_nao_gera_package_price_suspected() {
        UUID cotacaoId = criarCotacao();
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID fornecedorReferencia = criarFornecedor("Fornecedor Referência 2");
        UUID fornecedorNormal = criarFornecedor("Fornecedor Normal");

        adicionarFornecedorAsCotacao(cotacaoId, fornecedorReferencia);
        confirmar(cotacaoId, fornecedorReferencia, "Arroz Especial 1kg - R$ 20,00");
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorNormal);

        // 21,00 é só um pouco acima de 20,00 — bem abaixo de 1.5x (30,00).
        PreviewRespostaResponse preview = comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorNormal, "Arroz Especial 1kg - R$ 21,00"));

        ItemConferenciaResponse item = preview.itens().stream()
                .filter(i -> "Arroz Especial 1kg".equals(i.nomeItemBase()))
                .findFirst().orElseThrow();
        assertEquals(StatusConferencia.OK, item.status());
        assertTrue(item.motivos().isEmpty());
    }

    // ── textoPersistido — GET /cotacoes/{id}/fornecedores/{fornId}/resposta-persistida ──
    // Reconstrói o texto que o operador colaria de novo pra reabrir a Conferência de uma
    // resposta já persistida sem ter passado por gerarPreview (hoje, só o caminho
    // WhatsApp — ver WhatsappRespostaFornecedorServiceTest).

    // ── 8. Happy path: junta texto_original de cada linha persistida, na ordem da
    // lista base (cp.ordem) — NÃO na ordem em que apareceram na mensagem original ──

    @Test
    void textoPersistido_reconstroi_texto_na_ordem_da_lista_base_nao_na_ordem_da_mensagem() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Texto Persistido");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 3);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        // Mensagem do fornecedor menciona os itens fora da ordem da lista base.
        confirmar(cotacaoId, fornecedorId,
                "Arroz Especial 1kg - R$ 18,90\nFeijao Preto 1kg - R$ 7,50\nSazon Legumes 60g - R$ 2,89");

        String textoPersistido = comoTenant(() -> fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId));

        assertEquals(
                "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90\nFeijao Preto 1kg - R$ 7,50",
                textoPersistido,
                "deve reconstruir na ordem da lista base (cp.ordem), não na ordem em que apareceram na mensagem");
    }

    // ── 9. Fornecedor adicionado à cotação mas ainda sem nenhuma resposta persistida
    // (ex.: adicionado via web, nunca respondeu) → string vazia, não erro ──────────

    @Test
    void textoPersistido_sem_nenhuma_resposta_persistida_retorna_string_vazia() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Sem Resposta Persistida");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoPersistido = comoTenant(() -> fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId));

        assertEquals("", textoPersistido);
    }

    // ── 8b. Preview gerado mas AINDA NÃO confirmado (Prompt 15, V27): textoPersistido
    // devolve o texto bruto pendente gravado por gerarPreview, sem precisar de nenhuma
    // linha em cotacao_produto_fornecedor — é o que permite reabrir a Conferência de
    // uma resposta WhatsApp que só gerou preview, nunca foi confirmada. ────────────

    @Test
    void textoPersistido_apos_preview_sem_confirmar_retorna_texto_pendente() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Preview Sem Confirmar");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);

        String textoOriginal = "Sazon Legumes 60g - R$ 2,89";
        comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, textoOriginal));

        String textoPersistido = comoTenant(() -> fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId));

        assertEquals(textoOriginal, textoPersistido,
                "sem nenhuma linha em cotacao_produto_fornecedor, deve vir do texto pendente gravado pelo preview");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cotacao_produto_fornecedor cpf " +
                        "JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id " +
                        "WHERE cp.cotacao_id = ? AND cpf.fornecedor_id = ?",
                Integer.class, cotacaoId, fornecedorId);
        assertEquals(0, count, "preview não deve ter persistido nada");
    }

    // ── 8c. Depois de confirmar, o texto pendente é limpo — textoPersistido passa a vir
    // da reconstrução a partir de cotacao_produto_fornecedor (fallback), não do rascunho
    // da rodada já confirmada. ──────────────────────────────────────────────────────

    @Test
    void textoPersistido_apos_confirmar_limpa_texto_pendente_e_usa_fallback_persistido() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Confirma Limpa Pendente");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);
        String textoOriginal = "Sazon Legumes 60g - R$ 2,89";
        comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, textoOriginal));

        confirmar(cotacaoId, fornecedorId, textoOriginal);

        CotacaoFornecedor cf = comoTenant(() -> cfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId).orElseThrow());
        assertNull(cf.getTextoRespostaPendente(), "confirmar deve limpar o texto pendente");
        String textoPersistido = comoTenant(() -> fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId));
        assertEquals(textoOriginal, textoPersistido, "deve vir do fallback (linha persistida), não do rascunho já confirmado");
    }

    // ── 10. 404 — cotação não existe ────────────────────────────────────────

    @Test
    void textoPersistido_para_cotacao_inexistente_lanca_not_found() {
        UUID cotacaoInexistente = UUID.randomUUID();
        UUID fornecedorId = criarFornecedor("Fornecedor Cotação Inexistente");

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                fornecedorRespostaService.textoPersistido(cotacaoInexistente, fornecedorId)));
    }

    // ── 11. 404 — fornecedor não existe ─────────────────────────────────────

    @Test
    void textoPersistido_para_fornecedor_inexistente_lanca_not_found() {
        UUID cotacaoId = criarCotacao();
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID fornecedorInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorInexistente)));
    }

    // ── 12. 404 — fornecedor existe mas nunca foi adicionado a esta cotação
    // (sem cotacao_fornecedor) ──────────────────────────────────────────────

    @Test
    void textoPersistido_para_fornecedor_nao_adicionado_a_cotacao_lanca_not_found() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Nunca Adicionado Texto Persistido");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        // Note: NÃO chama adicionarFornecedorAsCotacao — não existe cotacao_fornecedor.

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() ->
                fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId)));
    }

    // ── cancelarResposta — DELETE /cotacoes/{id}/fornecedores/{fornId}/resposta ──
    // "Cancelar Conferência" (achado do usuário, 2026-08-04): apaga a resposta já
    // persistida deste fornecedor e volta CotacaoFornecedor.status pra PENDENTE — sem
    // isso, "Conferir resposta do fornecedor" reconstruiria a mesma resposta cancelada
    // de textoPersistido no próximo clique.

    // ── 13. Happy path: apaga as linhas persistidas e volta o status pra PENDENTE ──

    @Test
    void cancelarResposta_apaga_linhas_persistidas_e_volta_status_para_pendente() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Cancelar Resposta");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 2);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);
        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89\nArroz Especial 1kg - R$ 18,90");
        assertEquals(CotacaoFornecedorStatus.CONFIRMADO, statusFornecedorNaCotacao(cotacaoId, fornecedorId));

        comoTenant(() -> {
            fornecedorRespostaService.cancelarResposta(cotacaoId, fornecedorId);
            return null;
        });

        assertEquals(CotacaoFornecedorStatus.PENDENTE, statusFornecedorNaCotacao(cotacaoId, fornecedorId));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cotacao_produto_fornecedor cpf " +
                        "JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id " +
                        "WHERE cp.cotacao_id = ? AND cpf.fornecedor_id = ?",
                Integer.class, cotacaoId, fornecedorId);
        assertEquals(0, count, "não deve sobrar nenhuma linha de resposta persistida");
    }

    // ── 14. Depois de cancelar, textoPersistido volta a ser string vazia — a mesma
    // resposta cancelada não reaparece no próximo "Conferir resposta do fornecedor" ──

    @Test
    void cancelarResposta_faz_textoPersistido_voltar_a_string_vazia() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Cancelar Nao Reaparece");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);
        confirmar(cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89");

        comoTenant(() -> {
            fornecedorRespostaService.cancelarResposta(cotacaoId, fornecedorId);
            return null;
        });

        String textoPersistido = comoTenant(() -> fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId));
        assertEquals("", textoPersistido);
    }

    // ── 14b. Cancelar um preview NUNCA confirmado (Prompt 15, V27) também limpa o texto
    // pendente — sem isso, textoPersistido devolveria o rascunho cancelado no próximo
    // "Conferir resposta do fornecedor", mesmo sem nenhuma linha persistida pra apagar. ──

    @Test
    void cancelarResposta_limpa_texto_pendente_de_preview_nunca_confirmado() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Cancela Preview Nunca Confirmado");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        adicionarFornecedorAsCotacao(cotacaoId, fornecedorId);
        comoTenant(() -> fornecedorRespostaService.gerarPreview(
                cotacaoId, fornecedorId, "Sazon Legumes 60g - R$ 2,89"));

        comoTenant(() -> {
            fornecedorRespostaService.cancelarResposta(cotacaoId, fornecedorId);
            return null;
        });

        String textoPersistido = comoTenant(() -> fornecedorRespostaService.textoPersistido(cotacaoId, fornecedorId));
        assertEquals("", textoPersistido);
    }

    // ── 15. 404 — cotação não existe ────────────────────────────────────────

    @Test
    void cancelarResposta_para_cotacao_inexistente_lanca_not_found() {
        UUID cotacaoInexistente = UUID.randomUUID();
        UUID fornecedorId = criarFornecedor("Fornecedor Cancelar Cotação Inexistente");

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() -> {
            fornecedorRespostaService.cancelarResposta(cotacaoInexistente, fornecedorId);
            return null;
        }));
    }

    // ── 16. 404 — fornecedor não existe ─────────────────────────────────────

    @Test
    void cancelarResposta_para_fornecedor_inexistente_lanca_not_found() {
        UUID cotacaoId = criarCotacao();
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        UUID fornecedorInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() -> {
            fornecedorRespostaService.cancelarResposta(cotacaoId, fornecedorInexistente);
            return null;
        }));
    }

    // ── 17. 404 — fornecedor existe mas nunca foi adicionado a esta cotação ─

    @Test
    void cancelarResposta_para_fornecedor_nao_adicionado_a_cotacao_lanca_not_found() {
        UUID cotacaoId = criarCotacao();
        UUID fornecedorId = criarFornecedor("Fornecedor Nunca Adicionado Cancelar");
        adicionarItemBase(cotacaoId, "Sazon Legumes 60g", 1);
        // Note: NÃO chama adicionarFornecedorAsCotacao — não existe cotacao_fornecedor.

        assertThrows(ResourceNotFoundException.class, () -> comoTenant(() -> {
            fornecedorRespostaService.cancelarResposta(cotacaoId, fornecedorId);
            return null;
        }));
    }
}
