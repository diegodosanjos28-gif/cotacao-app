package com.prx.cotacao.cotacao.mapacompra.service;

import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.DistribuicaoFornecedor;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.ItemDistribuicao;
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
 * Testes do Mapa de Compra (Prompt 6 — seção 6.5 da doc técnica): os 3 cenários de
 * distribuição fornecedor→produto (MENOR_PRECO, MELHOR_PRAZO, EQUILIBRADA).
 *
 * Cada teste monta um cenário fixo (fornecedores, itens, ofertas) com resultado
 * esperado calculado à mão a partir da leitura de MapaCompraService, não de uma
 * suposição independente do "ótimo" — EQUILIBRADA é uma heurística gulosa
 * documentada, não um solver de otimização global.
 *
 * Pré-requisito: Postgres local acessível (mesmo perfil "dev" dos demais testes de
 * integração deste módulo — ver MultiTenantIsolationTest / EmbalagemSnapshotIsolationTest).
 */
@SpringBootTest
@ActiveProfiles("dev")
class MapaCompraServiceTest {

    @Autowired private MapaCompraService mapaCompraService;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("INSERT INTO tenant (id, nome_fantasia, status) VALUES (?, 'Tenant Mapa Compra Test', 'TRIAL')",
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
        c.setTitulo("Cotação Mapa Compra Test " + UUID.randomUUID());
        c.setStatus(CotacaoStatus.EM_ANDAMENTO);
        c.setCanalOrigem(CanalOrigem.WEB);
        return cotacaoRepository.save(c).getId();
    }

    private UUID criarFornecedor(String nome, String prazoTexto, BigDecimal pedidoMinimo, FornecedorStatus status) {
        Fornecedor f = new Fornecedor();
        f.setNome(nome);
        f.setPrazoEntregaPadrao(prazoTexto);
        f.setPedidoMinimoPadrao(pedidoMinimo);
        f.setStatus(status);
        return fornecedorRepository.save(f).getId();
    }

    private UUID criarFornecedorAtivo(String nome) {
        return criarFornecedor(nome, null, null, FornecedorStatus.ATIVO);
    }

    private UUID criarItem(UUID cotacaoId, String texto, String qtd, int ordem) {
        return criarItem(cotacaoId, texto, qtd, "un", ordem);
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

    private UUID criarFornecedorCompleto(String nome, String prazoTexto, String condicaoPagamento,
                                          BigDecimal pedidoMinimo, FornecedorStatus status) {
        Fornecedor f = new Fornecedor();
        f.setNome(nome);
        f.setPrazoEntregaPadrao(prazoTexto);
        f.setCondicaoPagamentoPadrao(condicaoPagamento);
        f.setPedidoMinimoPadrao(pedidoMinimo);
        f.setStatus(status);
        return fornecedorRepository.save(f).getId();
    }

    private void criarOferta(UUID itemId, UUID fornecedorId, String precoUnitario, StatusItem status, boolean semEstoque) {
        CotacaoProdutoFornecedor cpf = new CotacaoProdutoFornecedor();
        cpf.setCotacaoProdutoId(itemId);
        cpf.setFornecedorId(fornecedorId);
        BigDecimal preco = new BigDecimal(precoUnitario);
        cpf.setPrecoInformado(preco);
        cpf.setPrecoUnitarioCalculado(preco);
        cpf.setStatus(status);
        cpf.setSemEstoque(semEstoque);
        cpfRepository.save(cpf);
    }

    private void criarOferta(UUID itemId, UUID fornecedorId, String precoUnitario) {
        criarOferta(itemId, fornecedorId, precoUnitario, StatusItem.OK, false);
    }

    // ── Helpers de asserção ──────────────────────────────────────────────────────

    private void assertMoneyEquals(String esperado, BigDecimal atual) {
        assertNotNull(atual, "Valor esperado " + esperado + " mas foi null");
        assertEquals(0, new BigDecimal(esperado).compareTo(atual),
                () -> "Esperado " + esperado + " mas foi " + atual);
    }

    private DistribuicaoFornecedor distDe(MapaCompraResponse resp, UUID fornecedorId) {
        return resp.distribuicoes().stream()
                .filter(d -> d.fornecedorId().equals(fornecedorId))
                .findFirst().orElse(null);
    }

    private ItemDistribuicao itemDe(DistribuicaoFornecedor dist, UUID itemId) {
        if (dist == null) return null;
        return dist.itens().stream().filter(i -> i.cotacaoProdutoId().equals(itemId)).findFirst().orElse(null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MENOR_PRECO
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void menorPreco_escolhe_por_item_independente_do_restante() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "3", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "2", 2));

        comoTenant(() -> {
            criarOferta(item1, fA, "10.00"); // vence item1
            criarOferta(item1, fB, "12.00");
            criarOferta(item2, fA, "20.00");
            criarOferta(item2, fB, "15.00"); // vence item2
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        DistribuicaoFornecedor distA = distDe(resp, fA);
        DistribuicaoFornecedor distB = distDe(resp, fB);
        assertNotNull(distA);
        assertNotNull(distB);

        ItemDistribuicao item1EmA = itemDe(distA, item1);
        assertNotNull(item1EmA, "Item 1 deve ser atribuído ao Fornecedor A (mais barato)");
        assertMoneyEquals("30.00", item1EmA.subtotal()); // 3 * 10.00

        ItemDistribuicao item2EmB = itemDe(distB, item2);
        assertNotNull(item2EmB, "Item 2 deve ser atribuído ao Fornecedor B (mais barato)");
        assertMoneyEquals("30.00", item2EmB.subtotal()); // 2 * 15.00

        assertNull(itemDe(distA, item2), "Item 2 não deve estar em A");
        assertNull(itemDe(distB, item1), "Item 1 não deve estar em B");

        assertMoneyEquals("60.00", resp.totalGeral());
    }

    @Test
    void menorPreco_ignora_oferta_com_status_invalido_e_sem_estoque_mesmo_sendo_mais_barata() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fPendente = comoTenant(() -> criarFornecedorAtivo("Fornecedor Pendente Confirmação"));
        UUID fSemEstoque = comoTenant(() -> criarFornecedorAtivo("Fornecedor Sem Estoque"));
        UUID fValido = comoTenant(() -> criarFornecedorAtivo("Fornecedor Válido"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, fPendente, "1.00", StatusItem.PENDENTE_CONFIRMACAO, false); // mais barata, mas status inválido
            criarOferta(item, fSemEstoque, "0.50", StatusItem.OK, true); // mais barata ainda, mas sem estoque
            criarOferta(item, fValido, "5.00", StatusItem.OK, false); // única elegível
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertTrue(resp.produtosSemFornecedor().isEmpty());
        DistribuicaoFornecedor distValido = distDe(resp, fValido);
        assertNotNull(distValido);
        ItemDistribuicao atribuido = itemDe(distValido, item);
        assertNotNull(atribuido);
        assertMoneyEquals("5.00", atribuido.subtotal());

        assertNull(distDe(resp, fPendente));
        assertNull(distDe(resp, fSemEstoque));
    }

    @Test
    void menorPreco_item_sem_oferta_valida_cai_em_produtos_sem_fornecedor() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Único"));
        UUID itemComOferta = comoTenant(() -> criarItem(cotacaoId, "Item com oferta", "1", 1));
        UUID itemSemOferta = comoTenant(() -> criarItem(cotacaoId, "Item sem oferta", "1", 2));

        comoTenant(() -> {
            criarOferta(itemComOferta, f, "9.00");
            // itemSemOferta não recebe nenhum CPF
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertEquals(1, resp.produtosSemFornecedor().size());
        assertEquals(itemSemOferta, resp.produtosSemFornecedor().get(0).cotacaoProdutoId());
        assertEquals("Item sem oferta", resp.produtosSemFornecedor().get(0).produtoNome());

        DistribuicaoFornecedor dist = distDe(resp, f);
        assertNotNull(dist);
        assertNotNull(itemDe(dist, itemComOferta));
        assertMoneyEquals("9.00", resp.totalGeral());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MELHOR_PRAZO
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void melhorPrazo_prazo_parseavel_vence_mesmo_sendo_mais_caro_que_prazo_desconhecido() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fRapidoParseavel = comoTenant(() -> criarFornecedor("Fornecedor 3 dias", "3 dias úteis", null, FornecedorStatus.ATIVO));
        UUID fPrazoDesconhecido = comoTenant(() -> criarFornecedor("Fornecedor sem prazo", null, null, FornecedorStatus.ATIVO));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, fRapidoParseavel, "10.00"); // mais caro
            criarOferta(item, fPrazoDesconhecido, "1.00"); // muito mais barato, mas prazo não parseável
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MELHOR_PRAZO));

        DistribuicaoFornecedor dist = distDe(resp, fRapidoParseavel);
        assertNotNull(dist, "Fornecedor com prazo parseável (3 dias) deve vencer, mesmo mais caro");
        assertNotNull(itemDe(dist, item));
        assertNull(distDe(resp, fPrazoDesconhecido));
        assertMoneyEquals("10.00", resp.totalGeral());
    }

    @Test
    void melhorPrazo_empate_de_prazo_desempata_por_preco() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fCaro = comoTenant(() -> criarFornecedor("Fornecedor Caro", "3 dias", null, FornecedorStatus.ATIVO));
        UUID fBarato = comoTenant(() -> criarFornecedor("Fornecedor Barato", "3 dias úteis", null, FornecedorStatus.ATIVO));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, fCaro, "8.00");
            criarOferta(item, fBarato, "6.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MELHOR_PRAZO));

        DistribuicaoFornecedor dist = distDe(resp, fBarato);
        assertNotNull(dist, "Com prazos empatados (3 dias ambos), deve vencer o mais barato");
        assertNotNull(itemDe(dist, item));
        assertNull(distDe(resp, fCaro));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EQUILIBRADA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void equilibrada_resgata_fornecedor_abaixo_do_minimo_movendo_o_item_correto() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        // F1 tem pedido mínimo de 30,00; F2 não tem mínimo.
        UUID f1 = comoTenant(() -> criarFornecedor("F1", null, new BigDecimal("30.00"), FornecedorStatus.ATIVO));
        UUID f2 = comoTenant(() -> criarFornecedor("F2", null, null, FornecedorStatus.ATIVO));

        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "10", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "5", 2));
        UUID item3 = comoTenant(() -> criarItem(cotacaoId, "Item 3", "5", 3));

        comoTenant(() -> {
            // Menor Preço isolado assignaria: item1->F1 (10x1,00=10,00), item2->F2 (5x1,00=5,00), item3->F1 (5x2,00=10,00)
            // F1 fica com 20,00 < mínimo 30,00 -> precisa resgate.
            criarOferta(item1, f1, "1.00");
            criarOferta(item1, f2, "2.00");
            criarOferta(item2, f1, "3.00"); // oferta de F1 pro item2 existe -> item2 é "movível" para F1
            criarOferta(item2, f2, "1.00");
            criarOferta(item3, f1, "2.00");
            criarOferta(item3, f2, "5.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.EQUILIBRADA));

        DistribuicaoFornecedor distF1 = distDe(resp, f1);
        assertNotNull(distF1, "F1 deve permanecer na distribuição após o resgate");
        assertNotNull(itemDe(distF1, item1), "Item 1 continua com F1");
        assertNotNull(itemDe(distF1, item3), "Item 3 continua com F1");
        assertNotNull(itemDe(distF1, item2), "Item 2 deve ter sido movido para F1 no resgate");

        // F1: item1(10*1=10) + item3(5*2=10) + item2 movido (5*3=15) = 35,00 >= mínimo 30,00
        assertMoneyEquals("35.00", distF1.totalFornecedor());
        assertFalse(distF1.abaixoDoPedidoMinimo(), "Após o resgate, F1 não deve mais estar abaixo do mínimo");
        assertNull(distF1.faltaParaMinimo());

        assertNull(distDe(resp, f2), "F2 não deve sobrar com nenhum item após o item2 ser movido para F1");
    }

    @Test
    void equilibrada_fornecedor_nao_resgatavel_e_removido_e_itens_realocados() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        // F1 tem mínimo inatingível (1000,00); todos os itens que ele oferta já estão
        // com ele desde o Menor Preço, então não há nada movível para resgatá-lo.
        UUID f1 = comoTenant(() -> criarFornecedor("F1 Inatingível", null, new BigDecimal("1000.00"), FornecedorStatus.ATIVO));
        UUID f2 = comoTenant(() -> criarFornecedor("F2", null, null, FornecedorStatus.ATIVO));

        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "10", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "5", 2));

        comoTenant(() -> {
            criarOferta(item1, f1, "1.00"); // mais barato -> Menor Preço assignaria a F1
            criarOferta(item1, f2, "5.00");
            criarOferta(item2, f1, "2.00"); // mais barato -> Menor Preço assignaria a F1
            criarOferta(item2, f2, "8.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.EQUILIBRADA));

        assertNull(distDe(resp, f1), "F1 não deve aparecer na distribuição final — não pôde ser resgatado e foi removido");

        DistribuicaoFornecedor distF2 = distDe(resp, f2);
        assertNotNull(distF2, "Os itens de F1 devem ter sido realocados para F2");
        assertNotNull(itemDe(distF2, item1));
        assertNotNull(itemDe(distF2, item2));
        // item1: 10*5,00=50,00 ; item2: 5*8,00=40,00
        assertMoneyEquals("90.00", distF2.totalFornecedor());
        assertTrue(resp.produtosSemFornecedor().isEmpty());
    }

    @Test
    void equilibrada_exclui_fornecedor_pendente_dados_mas_reporta_o_motivo() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fPendenteDados = comoTenant(() -> criarFornecedor("Fornecedor Pendente Dados", null, null, FornecedorStatus.PENDENTE_DADOS));
        UUID fAtivo = comoTenant(() -> criarFornecedorAtivo("Fornecedor Ativo"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, fPendenteDados, "1.00"); // mais barato
            criarOferta(item, fAtivo, "5.00");
            return null;
        });

        MapaCompraResponse respEquilibrada = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.EQUILIBRADA));
        DistribuicaoFornecedor distAtivoEq = distDe(respEquilibrada, fAtivo);
        assertNotNull(distAtivoEq, "Em EQUILIBRADA, apenas o fornecedor ATIVO é elegível");
        assertNotNull(itemDe(distAtivoEq, item));
        assertNull(distDe(respEquilibrada, fPendenteDados), "PENDENTE_DADOS nunca deve ser selecionado em EQUILIBRADA");

        // Achado do usuário (09/08): a exclusão sozinha, sem explicação, fazia o
        // cenário parecer "quebrado" (0 fornecedores, R$0,00) sem nenhum motivo
        // visível — fornecedoresComDadosPendentes é o que permite o frontend
        // explicar a ausência em vez de devolver silêncio.
        assertEquals(1, respEquilibrada.fornecedoresComDadosPendentes().size());
        assertEquals(fPendenteDados, respEquilibrada.fornecedoresComDadosPendentes().get(0).fornecedorId());

        // Contraste: em MENOR_PRECO (que não filtra por status do fornecedor), o mais
        // barato vence, e a distribuição resultante sinaliza dadosPendentes=true.
        MapaCompraResponse respMenorPreco = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));
        DistribuicaoFornecedor distPendenteMenorPreco = distDe(respMenorPreco, fPendenteDados);
        assertNotNull(distPendenteMenorPreco, "Em MENOR_PRECO, PENDENTE_DADOS pode vencer por ser mais barato");
        assertTrue(distPendenteMenorPreco.dadosPendentes());
        // fornecedoresComDadosPendentes é reportado independente do cenário pedido.
        assertEquals(1, respMenorPreco.fornecedoresComDadosPendentes().size());

        // Em EQUILIBRADA, quem vence o item é o ATIVO (o único elegível) — confirma
        // que a distribuição dele não carrega o flag.
        assertFalse(distAtivoEq.dadosPendentes(), "fornecedor ATIVO não deve carregar o flag");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Forma da resposta: totais, economia e consistência de pedido mínimo
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void resposta_totalGeral_economia_e_flags_de_pedido_minimo_sao_consistentes() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        // MENOR_PRECO ignora pedido mínimo por completo — A fica com tudo, abaixo do mínimo.
        UUID fA = comoTenant(() -> criarFornecedor("Fornecedor A", null, new BigDecimal("100.00"), FornecedorStatus.ATIVO));
        UUID fB = comoTenant(() -> criarFornecedor("Fornecedor B", null, null, FornecedorStatus.ATIVO));

        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "2", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "1", 2));

        comoTenant(() -> {
            criarOferta(item1, fA, "5.00");  // vence: 2*5=10
            criarOferta(item1, fB, "8.00");  // pior preço do item1
            criarOferta(item2, fA, "3.00");  // vence: 1*3=3
            criarOferta(item2, fB, "50.00"); // pior preço do item2
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        // totalGeral = soma de todos os subtotais de todas as distribuições
        BigDecimal somaSubtotais = resp.distribuicoes().stream()
                .flatMap(d -> d.itens().stream())
                .map(ItemDistribuicao::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, somaSubtotais.compareTo(resp.totalGeral()),
                "totalGeral deve ser exatamente a soma dos subtotais");
        assertMoneyEquals("13.00", resp.totalGeral()); // 10 + 3

        // economiaComparadaAoPior = soma(pior preço válido por item * qtd) - totalGeral
        // item1: pior = max(5,8)=8 * 2 = 16 ; item2: pior = max(3,50)=50 * 1 = 50 ; totalPior = 66
        // economia = 66 - 13 = 53
        assertMoneyEquals("53.00", resp.economiaComparadaAoPior());

        // Flags de pedido mínimo: A ficou com tudo (13,00) abaixo do mínimo (100,00)
        DistribuicaoFornecedor distA = distDe(resp, fA);
        assertNotNull(distA);
        assertTrue(distA.abaixoDoPedidoMinimo());
        assertMoneyEquals("100.00", distA.pedidoMinimo());
        assertMoneyEquals("13.00", distA.totalFornecedor());
        assertMoneyEquals("87.00", distA.faltaParaMinimo()); // 100 - 13

        // B não recebeu nenhum item (perdeu em ambos) -> não aparece na distribuição
        assertNull(distDe(resp, fB));
    }

    @Test
    void resposta_fornecedor_sem_pedido_minimo_nunca_fica_abaixo_do_minimo() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedor("Fornecedor Sem Mínimo", null, null, FornecedorStatus.ATIVO));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, f, "1.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        DistribuicaoFornecedor dist = distDe(resp, f);
        assertNotNull(dist);
        assertFalse(dist.abaixoDoPedidoMinimo());
        assertNull(dist.pedidoMinimo());
        assertNull(dist.faltaParaMinimo());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Campo ItemDistribuicao.menorPreco
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void menorPreco_marca_true_apenas_para_a_oferta_mais_barata_entre_tres() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fA = comoTenant(() -> criarFornecedorAtivo("Fornecedor A"));
        UUID fB = comoTenant(() -> criarFornecedorAtivo("Fornecedor B"));
        UUID fC = comoTenant(() -> criarFornecedorAtivo("Fornecedor C"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, fA, "12.00");
            criarOferta(item, fB, "8.00"); // mais barato
            criarOferta(item, fC, "10.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        DistribuicaoFornecedor distB = distDe(resp, fB);
        assertNotNull(distB);
        ItemDistribuicao itemEmB = itemDe(distB, item);
        assertNotNull(itemEmB);
        assertTrue(itemEmB.menorPreco(), "Fornecedor B (8,00) é o mais barato entre as três ofertas — deve ser true");

        assertNull(distDe(resp, fA));
        assertNull(distDe(resp, fC));
    }

    @Test
    void menorPreco_true_no_fornecedor_escalado_quando_ha_empate_de_preco() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fX = comoTenant(() -> criarFornecedorAtivo("Fornecedor X"));
        UUID fY = comoTenant(() -> criarFornecedorAtivo("Fornecedor Y"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item único", "1", 1));

        comoTenant(() -> {
            criarOferta(item, fX, "7.50");
            criarOferta(item, fY, "7.50"); // empate exato
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        // Exatamente um dos dois é escalado (desempate determinístico por UUID); o campo
        // menorPreco do que FOI escalado deve ser true, pois seu preço (7,50) é igual ao
        // menor preço entre todas as ofertas válidas do item (empate conta como menor).
        DistribuicaoFornecedor distX = distDe(resp, fX);
        DistribuicaoFornecedor distY = distDe(resp, fY);
        ItemDistribuicao escalado = (distX != null) ? itemDe(distX, item) : itemDe(distY, item);
        assertNotNull(escalado, "Um dos dois fornecedores empatados deve ter sido escalado");
        assertTrue(escalado.menorPreco(), "Preço empatado com o menor entre as ofertas — deve ser true");
    }

    @Test
    void menorPreco_false_quando_fornecedor_escalado_nao_e_o_mais_barato_entre_todas_as_ofertas() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        // Mesmo cenário de resgate de EQUILIBRADA usado em
        // equilibrada_resgata_fornecedor_abaixo_do_minimo_movendo_o_item_correto: item2 é
        // resgatado para F1 (3,00) mesmo F2 ofertando mais barato (1,00) para esse item —
        // o campo menorPreco de item2 em F1 deve refletir isso como false, comparando
        // contra TODAS as ofertas válidas do item, não só contra os fornecedores escalados.
        UUID f1 = comoTenant(() -> criarFornecedor("F1", null, new BigDecimal("30.00"), FornecedorStatus.ATIVO));
        UUID f2 = comoTenant(() -> criarFornecedor("F2", null, null, FornecedorStatus.ATIVO));

        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "10", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "5", 2));
        UUID item3 = comoTenant(() -> criarItem(cotacaoId, "Item 3", "5", 3));

        comoTenant(() -> {
            criarOferta(item1, f1, "1.00"); // F1 é o mais barato pro item1
            criarOferta(item1, f2, "2.00");
            criarOferta(item2, f1, "3.00"); // F1 NÃO é o mais barato pro item2 (F2 é)
            criarOferta(item2, f2, "1.00"); // mais barato, mas item2 é resgatado para F1
            criarOferta(item3, f1, "2.00"); // F1 é o mais barato pro item3
            criarOferta(item3, f2, "5.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.EQUILIBRADA));

        DistribuicaoFornecedor distF1 = distDe(resp, f1);
        assertNotNull(distF1);

        ItemDistribuicao item1EmF1 = itemDe(distF1, item1);
        assertNotNull(item1EmF1);
        assertTrue(item1EmF1.menorPreco(), "F1 é de fato o mais barato pro item1");

        ItemDistribuicao item2EmF1 = itemDe(distF1, item2);
        assertNotNull(item2EmF1, "Item 2 deve ter sido resgatado para F1");
        assertFalse(item2EmF1.menorPreco(),
                "F1 (3,00) não é o mais barato pro item2 — F2 (1,00) é, mesmo não tendo sido escalado");

        ItemDistribuicao item3EmF1 = itemDe(distF1, item3);
        assertNotNull(item3EmF1);
        assertTrue(item3EmF1.menorPreco(), "F1 é de fato o mais barato pro item3");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ProdutoSemFornecedor.quantidade / unidade
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void produtoSemFornecedor_preenche_quantidade_e_unidade_do_item() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID itemSemOferta = comoTenant(() -> criarItem(cotacaoId, "Item Sem Oferta", "2.5", "kg", 1));

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertEquals(1, resp.produtosSemFornecedor().size());
        var semForn = resp.produtosSemFornecedor().get(0);
        assertEquals(itemSemOferta, semForn.cotacaoProdutoId());
        assertEquals("Item Sem Oferta", semForn.produtoNome());
        assertEquals(0, new BigDecimal("2.5").compareTo(semForn.quantidade()),
                "quantidade deve vir do CotacaoProduto, não ficar null");
        assertEquals("kg", semForn.unidade(), "unidade deve vir do CotacaoProduto, não ficar null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DistribuicaoFornecedor.prazoEntregaPadrao / condicaoPagamentoPadrao
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void distribuicao_repassa_prazoEntregaPadrao_e_condicaoPagamentoPadrao_do_fornecedor() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID fComDados = comoTenant(() -> criarFornecedorCompleto(
                "Fornecedor Completo", "5 dias úteis", "30 dias", null, FornecedorStatus.ATIVO));
        UUID fSemDados = comoTenant(() -> criarFornecedorCompleto(
                "Fornecedor Sem Dados", null, null, null, FornecedorStatus.ATIVO));
        UUID item1 = comoTenant(() -> criarItem(cotacaoId, "Item 1", "1", 1));
        UUID item2 = comoTenant(() -> criarItem(cotacaoId, "Item 2", "1", 2));

        comoTenant(() -> {
            criarOferta(item1, fComDados, "5.00");
            criarOferta(item2, fSemDados, "5.00");
            return null;
        });

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        DistribuicaoFornecedor distCompleto = distDe(resp, fComDados);
        assertNotNull(distCompleto);
        assertEquals("5 dias úteis", distCompleto.prazoEntregaPadrao());
        assertEquals("30 dias", distCompleto.condicaoPagamentoPadrao());

        DistribuicaoFornecedor distSemDados = distDe(resp, fSemDados);
        assertNotNull(distSemDados);
        assertNull(distSemDados.prazoEntregaPadrao(), "Fornecedor sem prazo cadastrado deve repassar null, não quebrar");
        assertNull(distSemDados.condicaoPagamentoPadrao(), "Fornecedor sem condição cadastrada deve repassar null, não quebrar");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Casos vazios / de borda
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void cotacao_sem_itens_retorna_resposta_vazia_sem_erro() {
        UUID cotacaoId = comoTenant(this::criarCotacao);

        MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, CenarioSelecionado.MENOR_PRECO));

        assertTrue(resp.distribuicoes().isEmpty());
        assertTrue(resp.produtosSemFornecedor().isEmpty());
        assertMoneyEquals("0", resp.totalGeral());
        assertMoneyEquals("0", resp.economiaComparadaAoPior());
    }

    @Test
    void cotacao_inexistente_lanca_resource_not_found() {
        UUID idInexistente = UUID.randomUUID();
        assertThrows(com.prx.cotacao.shared.error.ResourceNotFoundException.class,
                () -> comoTenant(() -> mapaCompraService.gerar(idInexistente, CenarioSelecionado.MENOR_PRECO)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Prompt 12: item soft-deletado nunca aparece em nenhum cenário do Mapa
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void item_soft_deletado_nao_aparece_em_nenhum_cenario_do_mapa() {
        UUID cotacaoId = comoTenant(this::criarCotacao);
        UUID f = comoTenant(() -> criarFornecedorAtivo("Fornecedor Item Removido"));
        UUID item = comoTenant(() -> criarItem(cotacaoId, "Item Removido", "1", 1));

        comoTenant(() -> {
            criarOferta(item, f, "9.00");
            return null;
        });

        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(item).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });

        for (CenarioSelecionado cenario : CenarioSelecionado.values()) {
            MapaCompraResponse resp = comoTenant(() -> mapaCompraService.gerar(cotacaoId, cenario));
            assertTrue(resp.distribuicoes().isEmpty(), "Cenário " + cenario + " não deve distribuir um item soft-deletado a nenhum fornecedor");
            assertTrue(resp.produtosSemFornecedor().isEmpty(), "Item soft-deletado não deve aparecer nem como 'produto sem fornecedor'");
        }
    }
}
