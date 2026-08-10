package com.prx.cotacao.whatsapp.canal;

import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.FornecedorRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ConfirmarRespostaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ResolucaoItemRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.TipoResolucao;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ResolvedorFornecedorWhatsappStrategy;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.whatsapp.canal.service.WhatsappListaProdutosService;
import com.prx.cotacao.whatsapp.canal.service.WhatsappRespostaFornecedorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 15 (unificação Web/WhatsApp) — {@link WhatsappRespostaFornecedorService#processar}
 * NUNCA persiste nada em {@code cotacao_produto_fornecedor} nem grava
 * {@code CotacaoFornecedor.status = CONFIRMADO} sozinho: só resolve/cria o fornecedor
 * ({@link ResolvedorFornecedorWhatsappStrategy}) e delega o pipeline inteiro pro mesmo
 * {@code RespostaFornecedorCoreService} usado pelo canal Web, que sempre gera preview
 * ({@code CotacaoFornecedor.status = PROCESSADO}). Persistência de fato — para os dois
 * canais, sem exceção, mesmo sem nenhuma divergência — só acontece via
 * {@link ConfirmacaoRespostaService#confirmar}.
 *
 * <p>Chama {@link WhatsappListaProdutosService#processar} e
 * {@link WhatsappRespostaFornecedorService#processar} diretamente (não via
 * {@code POST /whatsapp/webhook}) — evita a complexidade de assinatura HMAC/telefone
 * autorizado do {@code WhatsappWebhookControllerIntegrationTest}, que já cobre a
 * coreografia OSIV/TenantContext ponta a ponta; aqui o interesse é só a lógica de
 * roteamento de canal, então basta reproduzir o mesmo protocolo do coordenador:
 * {@code TenantContext} resolvido para o tenant ANTES da primeira chamada JPA.</p>
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), mesma convenção dos demais
 * testes de integração deste pacote.
 */
@SpringBootTest
@ActiveProfiles("dev")
class WhatsappRespostaFornecedorServiceTest {

    @Autowired private WhatsappListaProdutosService listaService;
    @Autowired private WhatsappRespostaFornecedorService respostaService;
    @Autowired private FornecedorRespostaService fornecedorRespostaService;
    @Autowired private ConfirmacaoRespostaService confirmacaoRespostaService;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private CotacaoProdutoFornecedorRepository cpfRepository;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID usuarioId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant WhatsappRespostaFornecedor Test");
            t.setStatus(TenantStatus.TRIAL);
            tenantId = tenantRepository.save(t).getId();

            Usuario u = new Usuario();
            u.setTenantId(tenantId);
            u.setEmail("whatsapp-resposta-fornecedor-test-" + UUID.randomUUID() + "@prx-test.com");
            u.setSenhaHash("hash-nao-importa");
            u.setPapel(Papel.OPERADOR_CLIENTE);
            usuarioId = usuarioRepository.save(u).getId();
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("""
                    DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN
                    (SELECT id FROM cotacao_produto WHERE cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = ?))
                    """, tenantId);
            jdbc.update("DELETE FROM cotacao_fornecedor WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tenantId);
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

    private UUID processarLista(String texto) {
        return comoTenant(() -> listaService.processar(usuarioId, texto));
    }

    private UUID processarResposta(String texto) {
        return comoTenant(() -> respostaService.processar(usuarioId, texto));
    }

    private CotacaoFornecedor unicoCotacaoFornecedor(UUID cotacaoId) {
        return comoTenant(() -> {
            List<CotacaoFornecedor> registros = cfRepository.findByCotacaoIdOrderByOrdem(cotacaoId);
            assertEquals(1, registros.size(), "esperado exatamente 1 fornecedor associado à cotação");
            return registros.get(0);
        });
    }

    private List<CotacaoProdutoFornecedor> respostasDoFornecedor(UUID cotacaoId, UUID fornecedorId) {
        return comoTenant(() -> cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId));
    }

    private PreviewRespostaResponse gerarPreview(UUID cotacaoId, UUID fornecedorId, String texto) {
        return comoTenant(() -> fornecedorRespostaService.gerarPreview(cotacaoId, fornecedorId, texto));
    }

    private List<ItemRespostaResponse> confirmar(UUID cotacaoId, UUID fornecedorId, String texto,
                                                  List<ResolucaoItemRequest> resolucoes) {
        return comoTenant(() -> confirmacaoRespostaService.confirmar(
                cotacaoId, fornecedorId, new ConfirmarRespostaRequest(texto, resolucoes)));
    }

    // ── (a) Resposta sem nenhuma divergência: processar() SÓ gera preview
    // (PROCESSADO, nada persistido) — confirmação continua sendo sempre explícita,
    // mesmo sem nenhum item para revisar. ───────────────────────────────────────

    @Test
    void respostaSemDivergenciaGeraPreviewSemPersistirEAguardaConfirmacaoExplicita() {
        UUID cotacaoId = processarLista("Sazon Legumes 60g");
        String texto = "Fornecedor Sem Divergencia\nSazon Legumes 60g - R$ 2,89";

        processarResposta(texto);

        CotacaoFornecedor cf = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.PROCESSADO, cf.getStatus(),
                "processar() nunca confirma sozinho, mesmo sem nenhuma divergência — só gera preview");
        assertEquals(0, respostasDoFornecedor(cotacaoId, cf.getFornecedorId()).size(),
                "nada deve estar persistido em cotacao_produto_fornecedor antes de confirmar");

        PreviewRespostaResponse preview = gerarPreview(cotacaoId, cf.getFornecedorId(), texto);
        assertEquals(0, preview.contadores().revisar());
        assertEquals(1, preview.contadores().ok());

        // Confirmação sempre explícita: só agora, via POST .../confirmar, a resposta é
        // persistida e o fornecedor vira CONFIRMADO.
        confirmar(cotacaoId, cf.getFornecedorId(), texto, List.of());

        CotacaoFornecedor cfConfirmado = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.CONFIRMADO, cfConfirmado.getStatus());
        List<CotacaoProdutoFornecedor> respostas = respostasDoFornecedor(cotacaoId, cf.getFornecedorId());
        assertEquals(1, respostas.size());
        assertEquals(StatusItem.OK, respostas.get(0).getStatus());
    }

    // ── (b) Divergência de gramagem (WEIGHT_CHANGED) → preview REVISAR, PROCESSADO,
    // e continua sem persistir nada (nunca grava PENDENTE_CONFIRMACAO mais — esse
    // status deixou de ser gravável por qualquer caminho de produção). ────────────

    @Test
    void respostaComDivergenciaDeGramagemGeraPreviewRevisarSemPersistirNada() {
        UUID cotacaoId = processarLista("3kg arroz tipo 1");
        String texto = "Fornecedor Com Divergencia\nArroz Tipo 1 5kg - R$ 30,00";

        processarResposta(texto);

        CotacaoFornecedor cf = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.PROCESSADO, cf.getStatus());
        assertEquals(0, respostasDoFornecedor(cotacaoId, cf.getFornecedorId()).size(),
                "item REVISAR não bloqueia nem persiste — só fica pendente de confirmação explícita");

        PreviewRespostaResponse preview = gerarPreview(cotacaoId, cf.getFornecedorId(), texto);
        assertEquals(1, preview.contadores().revisar());
        assertEquals(StatusConferencia.REVISAR, preview.itens().get(0).status());
    }

    // ── (c) Item preservado entre rodadas só existe a partir de uma confirmação
    // explícita (preservação lê de cotacao_produto_fornecedor, que só existe pós
    // POST .../confirmar) — rodada 1 confirma, rodada 2 só reprocessa preview e não
    // toca a linha já confirmada do item não mencionado. ──────────────────────────

    @Test
    void itemPreservadoEntreRodadasSoExisteAposConfirmacaoExplicita() {
        UUID cotacaoId = processarLista("Feijao Preto 1kg\n3kg arroz tipo 1");
        String textoRodada1 = "Fornecedor Preservado\nFeijao Preto 1kg - R$ 7,50\nArroz Tipo 1 5kg - R$ 30,00";

        processarResposta(textoRodada1);
        CotacaoFornecedor cfAposPreview1 = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.PROCESSADO, cfAposPreview1.getStatus());
        assertEquals(0, respostasDoFornecedor(cotacaoId, cfAposPreview1.getFornecedorId()).size());

        // "Arroz Tipo 1 5kg" diverge (WEIGHT_CHANGED) — precisa de resolução pra
        // confirmar; só 1 candidato, então ACEITAR_SUGESTAO basta.
        PreviewRespostaResponse preview1 = gerarPreview(cotacaoId, cfAposPreview1.getFornecedorId(), textoRodada1);
        UUID itemArrozId = preview1.itens().stream()
                .filter(i -> i.status() == StatusConferencia.REVISAR)
                .findFirst().orElseThrow().itemBaseId();

        confirmar(cotacaoId, cfAposPreview1.getFornecedorId(), textoRodada1,
                List.of(new ResolucaoItemRequest(itemArrozId, null, TipoResolucao.ACEITAR_SUGESTAO,
                        null, null, null, null, null)));

        CotacaoFornecedor cfConfirmado = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.CONFIRMADO, cfConfirmado.getStatus());
        assertEquals(2, respostasDoFornecedor(cotacaoId, cfConfirmado.getFornecedorId()).size());

        // Rodada 2: só reenvia o item que já estava OK — "Arroz Tipo 1 5kg" não é
        // mencionado de novo, então fica "preservado" no preview (não removido, não
        // recalculado). processar() de novo só gera preview (reprocessar invalida a
        // confirmação anterior) — a linha do Arroz permanece intacta, sem tocar.
        processarResposta("Fornecedor Preservado\nFeijao Preto 1kg - R$ 7,60");

        CotacaoFornecedor cfAposRodada2 = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.PROCESSADO, cfAposRodada2.getStatus(),
                "reprocessar sempre invalida a confirmação anterior, mesmo que o item não tocado continue confirmado");

        List<CotacaoProdutoFornecedor> respostasRodada2 = respostasDoFornecedor(cotacaoId, cfAposRodada2.getFornecedorId());
        assertEquals(2, respostasRodada2.size(), "a linha preservada do Arroz continua intacta, não foi removida");
        assertTrue(respostasRodada2.stream().allMatch(r -> r.getStatus() == StatusItem.OK),
                "nenhum caminho de produção grava PENDENTE_CONFIRMACAO mais — tudo que está persistido veio de um /confirmar, sempre OK");
    }

    // ── (d) Prompt 12: item base soft-deletado não é candidato de matching — mesmo
    // caso de FornecedorRespostaServiceTest, agora sem persistência automática. ────

    @Test
    void respostaMencionandoItemBaseSoftDeletadoNaoTentaCasarComEleNoPreview() {
        UUID cotacaoId = processarLista("Feijao Preto 1kg\nArroz Tipo 1 5kg");

        UUID itemArrozId = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId)
                .stream().filter(cp -> cp.getTextoOriginal().contains("Arroz")).findFirst().orElseThrow().getId());
        UUID itemFeijaoId = comoTenant(() -> cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId)
                .stream().filter(cp -> cp.getTextoOriginal().contains("Feijao")).findFirst().orElseThrow().getId());

        comoTenant(() -> {
            CotacaoProduto cp = cotacaoProdutoRepository.findById(itemArrozId).orElseThrow();
            cp.setRemovidoEm(OffsetDateTime.now());
            return cotacaoProdutoRepository.save(cp);
        });

        String texto = "Fornecedor Item Base Removido\nFeijao Preto 1kg - R$ 7,50\nArroz Tipo 1 5kg - R$ 30,00";
        processarResposta(texto);

        CotacaoFornecedor cf = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(0, respostasDoFornecedor(cotacaoId, cf.getFornecedorId()).size(),
                "preview nunca persiste, independente do item base estar vivo ou soft-deletado");

        PreviewRespostaResponse preview = gerarPreview(cotacaoId, cf.getFornecedorId(), texto);
        assertTrue(preview.itens().stream().anyMatch(i -> itemFeijaoId.equals(i.itemBaseId())),
                "Feijao (item vivo) deve aparecer no preview");
        assertTrue(preview.itens().stream().noneMatch(i -> itemArrozId.equals(i.itemBaseId())),
                "Arroz (item soft-deletado) não deve aparecer casado a nenhum item base — vira item extra, sem itemBaseId");

        confirmar(cotacaoId, cf.getFornecedorId(), texto, List.of());

        List<CotacaoProdutoFornecedor> respostas = respostasDoFornecedor(cotacaoId, cf.getFornecedorId());
        assertEquals(1, respostas.size(),
                "Só o item vivo (Feijao Preto) deve ter resposta persistida após confirmar — Arroz é item extra, não persiste sem resolução");
        assertEquals(itemFeijaoId, respostas.get(0).getCotacaoProdutoId());
    }

    // ── (e) Regressão de auto-flush: fornecedor NOVO (sem match, precisa ser criado)
    // numa cotação NOVA (sem "em andamento" — processar() cria do zero) na MESMA
    // chamada/transação. Fica aqui (em vez de ResolvedorFornecedorWhatsappStrategyTest)
    // porque o cenário só existe end-to-end via
    // WhatsappRespostaFornecedorService#processar: é ele quem decide criar a Cotacao
    // nova E delega pro Strategy criar o Fornecedor/CotacaoFornecedor na mesma
    // transação, e é só nesse encadeamento completo que o auto-flush do Hibernate é
    // exercitado — reaproveita os helpers já estabelecidos desta classe
    // (comoTenant/processarResposta/unicoCotacaoFornecedor) em vez de duplicá-los.
    //
    // ResolvedorFornecedorWhatsappStrategy#resolver cria o CotacaoFornecedor via
    // orElseGet(...) e, na sequência, RespostaFornecedorCoreService#processar faz seu
    // PRÓPRIO findByCotacaoIdAndFornecedorId pra buscar essa MESMA entidade (SELECT
    // duplicado intencional — ver javadoc de RespostaFornecedorCoreService#processar).
    // Sem o auto-flush do Hibernate, a segunda query não enxergaria a linha recém
    // criada pela primeira (ainda não commitada/flushada) e lançaria
    // ResourceNotFoundException. ──────────────────────────────────────────────────

    @Test
    void processarResposta_comFornecedorNovoECotacaoNovaNaMesmaTransacao_naoLancaExcecaoDeAutoFlush() {
        String texto = "Fornecedor Totalmente Novo Auto Flush\nFeijao Preto 1kg - R$ 7,50";

        UUID cotacaoId = processarResposta(texto);

        CotacaoFornecedor cf = unicoCotacaoFornecedor(cotacaoId);
        assertEquals(CotacaoFornecedorStatus.PROCESSADO, cf.getStatus(),
                "preview deve ter sido gerado com sucesso, mesmo com fornecedor e cotação criados na mesma transação");
    }
}
