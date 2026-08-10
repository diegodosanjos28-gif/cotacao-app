package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
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
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

/**
 * {@link ComparativoService#temDivergenciaComparativa} sinaliza
 * {@code divergenciaComparativa=true} quando o preço de um fornecedor é ≥1,5x (50%+) a
 * MEDIANA dos preços dos OUTROS fornecedores confirmados para o mesmo item, OU quando a
 * diferença absoluta é ≥R$10,00 (os dois critérios em OR, não um substituindo o outro) —
 * direcional (só o mais caro diverge) e exige pelo menos um "outro" preço válido
 * (fornecedor {@code semEstoque} não conta nem como candidato nem como referência).
 * Critério unificado com {@link ClassificacaoConferenciaService} via
 * {@link PrecoReferenciaService}. Histórico: R$10,00 absolutos era o único critério até
 * o pacote de ajustes pós-call de 2026-07-29 (substituído por 1,5x); um pedido seguinte
 * do cliente reintroduziu R$10,00 como critério adicional em OR, não como substituto.
 *
 * <p>Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555. Padrão de
 * setup espelha {@link ConfirmacaoRespostaServiceTest}, mas aqui as linhas de
 * {@code cotacao_produto_fornecedor} são persistidas diretamente via repositório (não
 * via {@code confirmar()}) para controlar precisamente preço/semEstoque de cada
 * fornecedor por item, sem depender do parser de texto.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
class ComparativoServiceTest {

    @Autowired private ComparativoService comparativoService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
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
            t.setNomeFantasia("Tenant Comparativo Test");
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

    // ── Helpers (mesmo padrão de ConfirmacaoRespostaServiceTest) ────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Comparativo Test");
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

    private void criarOferta(UUID itemBaseId, UUID fornecedorId, String preco, boolean semEstoque) {
        comoTenant(() -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemBaseId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta de teste");
            cpf.setPrecoInformado(new BigDecimal(preco));
            cpf.setPrecoUnitarioCalculado(new BigDecimal(preco));
            cpf.setSemEstoque(semEstoque);
            cpf.setStatus(StatusItem.OK);
            return cpfRepository.save(cpf);
        });
    }

    // Simula um item cuja suspeita de preço de fardo/caixa já foi resolvida na
    // Conferência: precoInformado é o bruto original (o que o fornecedor mandou),
    // precoUnitarioCalculado é o valor já corrigido (precoInformado ÷ embalagemQtd).
    private void criarOfertaComEmbalagemResolvida(UUID itemBaseId, UUID fornecedorId,
                                                   String precoInformadoBruto, int embalagemQtd) {
        comoTenant(() -> {
            CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
            cpf.setCotacaoProdutoId(itemBaseId);
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal("Oferta de teste");
            BigDecimal bruto = new BigDecimal(precoInformadoBruto);
            cpf.setPrecoInformado(bruto);
            cpf.setPrecoUnitarioCalculado(bruto.divide(BigDecimal.valueOf(embalagemQtd), 4, java.math.RoundingMode.HALF_UP));
            cpf.setEmbalagemQtdConfirmada(embalagemQtd);
            cpf.setSemEstoque(false);
            cpf.setStatus(StatusItem.OK);
            return cpfRepository.save(cpf);
        });
    }

    private List<ComparativoItemResponse> comparativo(UUID cotacaoId) {
        return comoTenant(() -> comparativoService.comparativo(cotacaoId));
    }

    private boolean divergencia(List<ComparativoItemResponse> resultado, UUID itemBaseId, UUID fornecedorId) {
        return resultado.stream()
                .filter(r -> r.cotacaoProdutoId().equals(itemBaseId))
                .findFirst().orElseThrow()
                .precosPorFornecedor().stream()
                .filter(p -> p.fornecedorId().equals(fornecedorId))
                .findFirst().orElseThrow()
                .divergenciaComparativa();
    }

    // ── 1. Caso principal da spec: só o mais caro diverge ───────────────────────

    @Test
    void fornecedor_mais_caro_diverge_da_mediana_mais_barato_nao() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID fBarato = criarFornecedor("Fornecedor Barato");
        UUID fCaro = criarFornecedor("Fornecedor Caro");

        criarOferta(itemId, fBarato, "6.50", false);
        criarOferta(itemId, fCaro, "72.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, fBarato),
                "Preço mais barato não deve ser marcado como divergente");
        assertTrue(divergencia(resultado, itemId, fCaro),
                "Preço R$65,50 acima da mediana dos outros deve ser marcado como divergente");
    }

    // ── 2. Um único fornecedor confirmado: sem "outros", sem divergência ───────

    @Test
    void unico_fornecedor_confirmado_nao_computa_divergencia() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Solo");

        criarOferta(itemId, fornecedorId, "999.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, fornecedorId),
                "Sem outros preços para comparar, não há divergência computável");
    }

    // ── 3. Preço abaixo de 1.5x a mediana não dispara ───────────────────────────

    @Test
    void preco_abaixo_de_1_5x_a_mediana_nao_dispara_divergencia() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");

        // Mediana (só f1) = 20.00; limiar = 20.00*1.5 = 30.00. 29.99 fica logo abaixo.
        criarOferta(itemId, f1, "20.00", false);
        criarOferta(itemId, f2, "29.99", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, f1));
        assertFalse(divergencia(resultado, itemId, f2),
                "29.99 fica abaixo de 1.5x a mediana (30.00) — não deve divergir");
    }

    // ── 4. Preço igual a 1.5x a mediana dispara (>=) ────────────────────────────

    @Test
    void preco_igual_a_1_5x_a_mediana_dispara_divergencia() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");

        // Mediana (só f1) = 20.00; limiar = 20.00*1.5 = 30.00 exatos.
        criarOferta(itemId, f1, "20.00", false);
        criarOferta(itemId, f2, "30.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, f1));
        assertTrue(divergencia(resultado, itemId, f2),
                "30.00 é exatamente 1.5x a mediana (20.00) — deve disparar (limiar é >=, não >)");
    }

    // ── 4b. Critério combinado (OR): relativo (≥1.5x) OU absoluto (≥R$10,00) ────
    // O critério de R$10,00 absolutos existia sozinho antes do pacote de ajustes de
    // 2026-07-29 (substituído por 1.5x); um pedido seguinte do cliente reintroduziu
    // R$10 como critério ADICIONAL (em OR com 1.5x), não como substituto — item caro
    // com diferença proporcional pequena mas ainda >=R$10 também deve divergir.

    @Test
    void diferenca_abaixo_de_1_5x_e_abaixo_de_10_reais_nao_diverge() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");

        // Mediana = 100.00; limiar relativo = 150.00, limiar absoluto = 110.00.
        // 109.99 fica abaixo dos dois — não diverge.
        criarOferta(itemId, f1, "100.00", false);
        criarOferta(itemId, f2, "109.99", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, f2),
                "109.99 fica abaixo de 1.5x a mediana (150.00) E abaixo de R$10 de diferença absoluta (110.00) — não deve divergir");
    }

    @Test
    void diferenca_absoluta_de_10_reais_diverge_mesmo_abaixo_de_1_5x() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");

        // Mediana = 100.00; limiar relativo = 150.00 (110.00 fica bem abaixo), mas a
        // diferença absoluta de exatos R$10,00 já dispara pelo critério em OR.
        criarOferta(itemId, f1, "100.00", false);
        criarOferta(itemId, f2, "110.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertTrue(divergencia(resultado, itemId, f2),
                "110.00 fica bem abaixo de 1.5x a mediana (150.00), mas a diferença absoluta de R$10 dispara pelo critério em OR");
    }

    // ── 4c. Prova de migração: diferença absoluta pequena mas ≥ 1.5x diverge ────
    // (o antigo critério de R$10,00 absolutos NÃO teria disparado aqui — só R$3 de
    // diferença — mas o novo critério relativo pega itens de preço baixo onde uma
    // diferença pequena em reais já é proporcionalmente enorme).

    @Test
    void diferenca_absoluta_pequena_mas_igual_ou_acima_de_1_5x_diverge() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Sal Grosso 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");

        // Mediana = 4.00; limiar = 6.00. Diferença absoluta de só R$3,00 (abaixo do
        // antigo limiar de R$10,00), mas 7.00 >= 6.00 — diverge no critério novo.
        criarOferta(itemId, f1, "4.00", false);
        criarOferta(itemId, f2, "7.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertTrue(divergencia(resultado, itemId, f2),
                "Só R$3 de diferença absoluta, mas 7.00 >= 1.5x a mediana (6.00) — deve divergir");
    }

    // ── 5. Fornecedor sem estoque não conta como alvo nem como referência ──────

    @Test
    void fornecedor_sem_estoque_nao_conta_como_alvo_nem_como_referencia() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID fSemEstoque = criarFornecedor("Fornecedor Sem Estoque");
        UUID f2 = criarFornecedor("Fornecedor 2");
        UUID f3 = criarFornecedor("Fornecedor 3");

        // Preço de fSemEstoque é bem mais baixo — se contasse como referência para os
        // outros dois, a mediana cairia o suficiente para marcar f2/f3 como divergentes
        // (30.00/1.00=30x e 31.00/1.00=31x, ambos >=1.5x). Excluído corretamente, a
        // única referência de cada um é o outro fornecedor não-semEstoque (30 vs 31,
        // diferença mínima — bem abaixo de 1.5x em qualquer direção).
        criarOferta(itemId, fSemEstoque, "1.00", true);
        criarOferta(itemId, f2, "30.00", false);
        criarOferta(itemId, f3, "31.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, fSemEstoque),
                "Fornecedor sem estoque nunca é marcado como divergente (short-circuit)");
        assertFalse(divergencia(resultado, itemId, f2),
                "f2 só tem f3 (31.00) como referência válida — diferença de 1.00 não diverge");
        assertFalse(divergencia(resultado, itemId, f3),
                "f3 só tem f2 (30.00) como referência válida — diferença de 1.00 não diverge");
    }

    // ── 6. Mediana com número ímpar de "outros" usa o valor do meio, não a média ─

    @Test
    void mediana_impar_de_tres_outros_usa_valor_do_meio_nao_a_media() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");
        UUID f3 = criarFornecedor("Fornecedor 3");
        UUID fAlvo = criarFornecedor("Fornecedor Alvo");

        // Outros de fAlvo: [10.00, 12.00, 50.00] -> mediana real = 12.00 (valor do
        // meio), limiar = 18.00. Se o cálculo usasse a média (24.00, limiar 36.00) em
        // vez da mediana, 25.00 ficaria abaixo do limiar e este teste pegaria a regressão.
        criarOferta(itemId, f1, "10.00", false);
        criarOferta(itemId, f2, "12.00", false);
        criarOferta(itemId, f3, "50.00", false);
        criarOferta(itemId, fAlvo, "25.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertTrue(divergencia(resultado, itemId, fAlvo),
                "25.00 >= 1.5x mediana(12.00) = 18.00 deveria divergir");
    }

    // ── 7. Mediana com número par de "outros" usa a média dos dois centrais ────

    @Test
    void mediana_par_de_dois_outros_usa_media_dos_dois_valores_centrais() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");
        UUID fAlvo = criarFornecedor("Fornecedor Alvo");

        // Outros de fAlvo: [10.00, 20.00] -> mediana = média = 15.00, limiar = 22.50.
        // 35.00 >= 22.50 -> diverge.
        criarOferta(itemId, f1, "10.00", false);
        criarOferta(itemId, f2, "20.00", false);
        criarOferta(itemId, fAlvo, "35.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertTrue(divergencia(resultado, itemId, fAlvo),
                "35.00 >= 1.5x mediana((10.00+20.00)/2=15.00) = 22.50 deveria divergir");
    }

    // ── 8. Isolamento: divergência de um item não vaza para outro item ─────────

    @Test
    void isolamento_entre_itens_diferentes_da_mesma_cotacao() {
        UUID cotacaoId = criarCotacao();
        UUID item1 = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID item2 = adicionarItemBase(cotacaoId, "Feijao Preto 1kg", 2);
        UUID f1 = criarFornecedor("Fornecedor 1");
        UUID f2 = criarFornecedor("Fornecedor 2");

        // Item 1: preços próximos, sem divergência.
        criarOferta(item1, f1, "10.00", false);
        criarOferta(item1, f2, "11.00", false);
        // Item 2: mesmos fornecedores, mas com divergência clara.
        criarOferta(item2, f1, "5.00", false);
        criarOferta(item2, f2, "100.00", false);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, item1, f1));
        assertFalse(divergencia(resultado, item1, f2),
                "Divergência do item2 não deve vazar para o item1 mesmo com os mesmos fornecedores");
        assertFalse(divergencia(resultado, item2, f1));
        assertTrue(divergencia(resultado, item2, f2));
    }

    // ── 9. Achado do cliente: badge não some depois de corrigir a embalagem ────

    @Test
    void badge_nao_diverge_mais_depois_de_embalagem_qtd_confirmada_corrigir_o_preco_unitario() {
        // Reproduz o bug relatado: fornecedor mandou R$96,00 (preço da caixa inteira,
        // 12 unidades) e o operador já resolveu isso na Conferência informando
        // "Unid./embalagem" = 12 — precoUnitarioCalculado vira 96/12 = 8,00, igual à
        // referência. ANTES da correção, temDivergenciaComparativa comparava
        // precoInformado (96,00, o bruto da caixa) contra a mediana, continuando a
        // acusar divergência mesmo depois de corrigido.
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Sal Grosso 1kg", 1);
        UUID fReferencia = criarFornecedor("Fornecedor Referência");
        UUID fCorrigido = criarFornecedor("Fornecedor Corrigido");

        criarOferta(itemId, fReferencia, "8.00", false);
        criarOfertaComEmbalagemResolvida(itemId, fCorrigido, "96.00", 12);

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertFalse(divergencia(resultado, itemId, fCorrigido),
                "Preço unitário já corrigido (96,00 ÷ 12 = 8,00) é igual à referência — não deve mais divergir, "
                        + "mesmo com precoInformado bruto (96,00) ainda parecendo muito mais caro");
    }

    // ── 10. Prompt 12: item soft-deletado não aparece mais no comparativo ──────

    @Test
    void item_soft_deletado_nao_aparece_no_comparativo_mesmo_com_oferta_vinculada() {
        UUID cotacaoId = criarCotacao();
        UUID itemId = adicionarItemBase(cotacaoId, "Arroz Especial 1kg", 1);
        UUID fornecedorId = criarFornecedor("Fornecedor Item Removido");
        criarOferta(itemId, fornecedorId, "10.00", false);

        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(itemId).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });

        List<ComparativoItemResponse> resultado = comparativo(cotacaoId);

        assertTrue(resultado.isEmpty(),
                "Item soft-deletado não deve aparecer no comparativo, mesmo com oferta de fornecedor ainda vinculada a ele");
    }
}
