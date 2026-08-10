package com.prx.cotacao.cotacao.mensagem.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

/**
 * Testes de MensagemService.gerarMensagem: o texto do "Pedido para X" agora é
 * filtrado pela distribuição do Mapa de Compra no cenário selecionado — só lista os
 * itens que esse fornecedor de fato recebeu naquele cenário, não a cotação inteira.
 *
 * Pré-requisito: Postgres local acessível (mesmo perfil "dev" de MapaCompraServiceTest).
 */
@SpringBootTest
@ActiveProfiles("dev")
class MensagemServiceTest {

    @Autowired private MensagemService mensagemService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private ProdutoRepository produtoRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Mensagem Test', 'TRIAL')",
                    tenantId);
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
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers de montagem de cenário ──────────────────────────────────────────

    private <T> T comoTenant(Supplier<T> fn) {
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private UUID criarCotacao() {
        Cotacao c = new Cotacao();
        c.setTitulo("Cotação Mensagem Test " + UUID.randomUUID());
        c.setStatus(CotacaoStatus.EM_ANDAMENTO);
        c.setCanalOrigem(CanalOrigem.WEB);
        return cotacaoRepository.save(c).getId();
    }

    private UUID criarFornecedor(String nome, String prazoTexto, String condicaoPagamento, FornecedorStatus status) {
        Fornecedor f = new Fornecedor();
        f.setNome(nome);
        f.setPrazoEntregaPadrao(prazoTexto);
        f.setCondicaoPagamentoPadrao(condicaoPagamento);
        f.setStatus(status);
        return fornecedorRepository.save(f).getId();
    }

    private UUID criarFornecedorAtivo(String nome) {
        return criarFornecedor(nome, null, null, FornecedorStatus.ATIVO);
    }

    private UUID criarItem(UUID cotacaoId, String texto, String qtd, String unidade, int ordem) {
        CotacaoProduto cp = new CotacaoProduto();
        cp.setCotacaoId(cotacaoId);
        cp.setTextoOriginal(texto);
        cp.setQuantidade(new BigDecimal(qtd));
        cp.setUnidade(unidade);
        cp.setOrdem(ordem);
        return cotacaoProdutoRepository.save(cp).getId();
    }

    private UUID criarItemComProduto(UUID cotacaoId, UUID produtoId, String texto, String qtd, String unidade, int ordem) {
        CotacaoProduto cp = new CotacaoProduto();
        cp.setCotacaoId(cotacaoId);
        cp.setProdutoId(produtoId);
        cp.setTextoOriginal(texto);
        cp.setQuantidade(new BigDecimal(qtd));
        cp.setUnidade(unidade);
        cp.setOrdem(ordem);
        return cotacaoProdutoRepository.save(cp).getId();
    }

    private UUID criarProduto(String nome) {
        Produto p = new Produto();
        p.setNome(nome);
        return produtoRepository.save(p).getId();
    }

    private void criarOferta(UUID itemId, UUID fornecedorId, String precoUnitario) {
        CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
        cpf.setCotacaoProdutoId(itemId);
        cpf.setFornecedorId(fornecedorId);
        BigDecimal preco = new BigDecimal(precoUnitario);
        cpf.setPrecoInformado(preco);
        cpf.setPrecoUnitarioCalculado(preco);
        cpf.setStatus(StatusItem.OK);
        cpfRepository.save(cpf);
    }

    private void criarOfertaComEmbalagem(UUID itemId, UUID fornecedorId, String precoUnitario, int embalagemQtdConfirmada) {
        CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
        cpf.setCotacaoProdutoId(itemId);
        cpf.setFornecedorId(fornecedorId);
        BigDecimal preco = new BigDecimal(precoUnitario);
        cpf.setPrecoInformado(preco);
        cpf.setPrecoUnitarioCalculado(preco);
        cpf.setStatus(StatusItem.OK);
        cpf.setEmbalagemQtdConfirmada(embalagemQtdConfirmada);
        cpfRepository.save(cpf);
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mensagem_lista_apenas_itens_atribuidos_ao_fornecedor_no_cenario() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));

        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "10un Item 1", "10", "un", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "5un Item 2", "5", "un", 2));
        UUID item3 = comoTenant(() -> criarItem(cotacaoId, "3un Item 3", "3", "un", 3));
        UUID item4 = comoTenant(() -> criarItem(cotacaoId, "2un Item 4", "2", "un", 4));
        UUID item5 = comoTenant(() -> criarItem(cotacaoId, "1un Item 5", "1", "un", 5));

        comoTenant(() -> {
            // A vence item1 e item2; B vence item3, item4 e item5.
            criarOferta(item1, fA, "1.00");
            criarOferta(item1, fB, "2.00");
            criarOferta(item2, fA, "1.00");
            criarOferta(item2, fB, "2.00");
            criarOferta(item3, fA, "9.00");
            criarOferta(item3, fB, "1.00");
            criarOferta(item4, fA, "9.00");
            criarOferta(item4, fB, "1.00");
            criarOferta(item5, fA, "9.00");
            criarOferta(item5, fB, "1.00");
            return null;
        });

        String mensagem = comoTenant(() ->
                mensagemService.gerarMensagem(cotacaoId, fA, CenarioSelecionado.MENOR_PRECO));

        assertTrue(mensagem.contains("10un Item 1"), "Item 1 (vencido por A) deve aparecer");
        assertTrue(mensagem.contains("5un Item 2"), "Item 2 (vencido por A) deve aparecer");
        assertFalse(mensagem.contains("3un Item 3"), "Item 3 (vencido por B) NÃO deve aparecer na mensagem de A");
        assertFalse(mensagem.contains("2un Item 4"), "Item 4 (vencido por B) NÃO deve aparecer na mensagem de A");
        assertFalse(mensagem.contains("1un Item 5"), "Item 5 (vencido por B) NÃO deve aparecer na mensagem de A");
    }

    @Test
    void trocar_cenario_muda_quais_itens_aparecem_na_mensagem() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        // Fornecedor rápido e parseável, mas mais caro; fornecedor sem prazo conhecido,
        // mais barato — mesmo par de fatos usado em MapaCompraServiceTest para MELHOR_PRAZO.
        UUID fRapido = comoTenant(() -> criarFornecedor("Fornecedor 3 dias", "3 dias úteis", null, FornecedorStatus.ATIVO));
        UUID fBarato = comoTenant(() -> criarFornecedor("Fornecedor sem prazo", null, null, FornecedorStatus.ATIVO));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "1un Item Único", "1", "un", 1));

        comoTenant(() -> {
            criarOferta(item, fRapido, "10.00"); // mais caro, mas prazo parseável -> vence MELHOR_PRAZO
            criarOferta(item, fBarato, "1.00");  // mais barato -> vence MENOR_PRECO
            return null;
        });

        String mensagemFRapidoMenorPreco = comoTenant(() ->
                mensagemService.gerarMensagem(cotacaoId, fRapido, CenarioSelecionado.MENOR_PRECO));
        assertFalse(mensagemFRapidoMenorPreco.contains("1un Item Único"),
                "Em MENOR_PRECO, o item não é atribuído a fRapido — não deve aparecer na mensagem dele");

        String mensagemFRapidoMelhorPrazo = comoTenant(() ->
                mensagemService.gerarMensagem(cotacaoId, fRapido, CenarioSelecionado.MELHOR_PRAZO));
        assertTrue(mensagemFRapidoMelhorPrazo.contains("1un Item Único"),
                "Em MELHOR_PRAZO, o item é atribuído a fRapido — deve aparecer na mensagem dele");
    }

    @Test
    void fornecedor_sem_item_atribuido_no_cenario_gera_mensagem_so_com_cabecalho_e_rodape() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fVencedor = comoTenant(() -> criarFornecedorAtivo("Fornecedor Vencedor"));
        UUID fSemItens = comoTenant(() -> criarFornecedor(
                "Fornecedor Sem Itens", "5 dias", "30 dias", FornecedorStatus.ATIVO));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "1un Item Único", "1", "un", 1));

        comoTenant(() -> {
            // fSemItens não tem nenhuma oferta nessa cotação — não pode vencer nada.
            criarOferta(item, fVencedor, "5.00");
            return null;
        });

        String mensagem = comoTenant(() ->
                mensagemService.gerarMensagem(cotacaoId, fSemItens, CenarioSelecionado.MENOR_PRECO));

        assertEquals("Pedido para Fornecedor Sem Itens:\n\n\nPagamento: 30 dias\nEntrega: 5 dias", mensagem,
                "Sem itens atribuídos, a mensagem deve trazer só cabeçalho + rodapé de pagamento/entrega, sem erro");
        assertFalse(mensagem.contains("Item Único"));
    }

    @Test
    void conversao_para_caixa_com_embalagem_confirmada_continua_funcionando_apos_o_filtro() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fornecedor = comoTenant(() -> criarFornecedorAtivo("Fornecedor Caixa"));
        UUID produtoId = comoTenant(() -> criarProduto("Sazon Legumes 60g"));
        UUID item = comoTenant(() -> criarItemComProduto(cotacaoId, produtoId, "10un sazon legumes 60g", "10", "un", 1));

        comoTenant(() -> {
            // Único fornecedor -> vence o item em qualquer cenário; embalagem confirmada
            // em caixas de 6 -> 10 unidades arredonda para 2 caixas (ceiling).
            criarOfertaComEmbalagem(item, fornecedor, "1.00", 6);
            return null;
        });

        String mensagem = comoTenant(() ->
                mensagemService.gerarMensagem(cotacaoId, fornecedor, CenarioSelecionado.MENOR_PRECO));

        assertTrue(mensagem.contains("2 cx Sazon Legumes 60g (10 un / caixa com 6)"),
                "Conversão para caixa deve continuar funcionando para itens que sobrevivem ao filtro do Mapa de Compra");
    }

    // ── Prompt 12: item soft-deletado nunca aparece na mensagem gerada ──────────

    @Test
    void item_soft_deletado_nao_aparece_na_mensagem_gerada_pro_fornecedor() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Item Removido Mensagem"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "5un Item Removido Da Mensagem", "5", "un", 1));

        comoTenant(() -> {
            criarOferta(item, f, "3.00");
            return null;
        });

        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(item).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });

        String mensagem = comoTenant(() ->
                mensagemService.gerarMensagem(cotacaoId, f, CenarioSelecionado.MENOR_PRECO));

        assertFalse(mensagem.contains("Item Removido Da Mensagem"),
                "Item soft-deletado não deve aparecer na mensagem do fornecedor, mesmo com oferta vinculada");
    }
}
