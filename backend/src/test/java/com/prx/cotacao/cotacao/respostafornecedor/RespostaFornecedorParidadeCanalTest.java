package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemConferenciaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.CotacaoFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.service.FornecedorRespostaService;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.whatsapp.canal.service.WhatsappRespostaFornecedorService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Prompt 15 (unificação Web/WhatsApp) — o teste mais importante do refactor: blinda
 * contra a regressão que motivou a unificação inteira (Conferência se comportando
 * diferente por canal). Roda o MESMO texto bruto de resposta de fornecedor pelos dois
 * canais (Web via {@link FornecedorRespostaService#gerarPreview} direto, WhatsApp via
 * {@link WhatsappRespostaFornecedorService#processar}) e compara o preview resultante
 * item a item — ambos devem produzir exatamente a mesma classificação, já que os dois
 * canais delegam para o mesmo {@code RespostaFornecedorCoreService} a partir do Prompt
 * 15. Comparação por {@code nomeItemBase} (texto normalizado), não por
 * {@code itemBaseId} — os IDs são UUIDs distintos entre as duas cotações (itens base
 * independentes, um por cotação).
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), mesma convenção dos demais
 * testes de integração deste pacote.
 */
@SpringBootTest
@ActiveProfiles("dev")
class RespostaFornecedorParidadeCanalTest {

    @Autowired private FornecedorRespostaService fornecedorRespostaService;
    @Autowired private WhatsappRespostaFornecedorService whatsappRespostaFornecedorService;
    @Autowired private CotacaoFornecedorService cotacaoFornecedorService;
    @Autowired private CotacaoFornecedorRepository cfRepository;
    @Autowired private CotacaoRepository cotacaoRepository;
    @Autowired private CotacaoProdutoRepository cotacaoProdutoRepository;
    @Autowired private FornecedorRepository fornecedorRepository;
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
            t.setNomeFantasia("Tenant Paridade Canal Test");
            t.setStatus(TenantStatus.TRIAL);
            tenantId = tenantRepository.save(t).getId();

            Usuario u = new Usuario();
            u.setTenantId(tenantId);
            u.setEmail("paridade-canal-test-" + UUID.randomUUID() + "@prx-test.com");
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

    private UUID criarCotacao(CanalOrigem canal, CotacaoStatus status, UUID criadoPor, OffsetDateTime ultimaAtividadeEm) {
        return comoTenant(() -> {
            Cotacao c = new Cotacao();
            c.setTitulo("Cotação Paridade Canal Test - " + canal);
            c.setStatus(status);
            c.setCanalOrigem(canal);
            c.setCriadoPor(criadoPor);
            c.setUltimaAtividadeEm(ultimaAtividadeEm);
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

    // ── Paridade ponta a ponta: mesmo texto de resposta pelos dois canais produz o
    // mesmo preview (contadores + itens, comparados por nomeItemBase). ─────────────

    @Test
    void mesmoTextoDeRespostaPelosDoisCanaisProduzOMesmoPreview() {
        // 1. Seed comum: fornecedor pré-existente (importante pro WhatsApp CASAR por
        // nome em vez de CRIAR um novo) e dois CotacaoProduto idênticos em cotações
        // diferentes, uma por canal.
        UUID fornecedorId = criarFornecedor("Padaria Central");

        UUID cotacaoWeb = criarCotacao(CanalOrigem.WEB, CotacaoStatus.EM_ANDAMENTO, null, null);
        adicionarItemBase(cotacaoWeb, "Pão Francês 1kg", 1);
        adicionarItemBase(cotacaoWeb, "Queijo Minas 500g", 2);

        // "Em andamento" pro MESMO usuarioId, dentro da janela de 48h que
        // WhatsappRespostaFornecedorService#buscarCotacaoEmAndamento usa — replica
        // exatamente as condições da query findFirstByCriadoPorAndCanalOrigemAndStatus
        // AndUltimaAtividadeEmGreaterThanOrderByUltimaAtividadeEmDesc.
        UUID cotacaoWhats = criarCotacao(CanalOrigem.WHATSAPP, CotacaoStatus.EM_ANDAMENTO, usuarioId, OffsetDateTime.now());
        adicionarItemBase(cotacaoWhats, "Pão Francês 1kg", 1);
        adicionarItemBase(cotacaoWhats, "Queijo Minas 500g", 2);

        // 2. Mesmo texto bruto pros dois canais — a 1ª linha ("Padaria Central") é
        // usada pelo parser nos dois canais, mas só o WhatsApp de fato a usa pra
        // resolver o fornecedor.
        String texto = "Padaria Central\nPão Francês 1kg - R$ 8,90\nQueijo Minas 500g - R$ 12,50";

        // 3. Canal Web.
        comoTenant(() -> cotacaoFornecedorService.adicionar(
                cotacaoWeb, new AdicionarFornecedorCotacaoRequest(fornecedorId, null)));
        PreviewRespostaResponse previewWeb = comoTenant(() ->
                fornecedorRespostaService.gerarPreview(cotacaoWeb, fornecedorId, texto));

        // 4. Canal WhatsApp — deve reaproveitar a cotação em andamento (não criar uma
        // nova) e resolver o fornecedor pré-existente por matching de nome (não
        // duplicar fornecedor).
        UUID cotacaoRetornadaPeloWhatsapp = comoTenant(() -> whatsappRespostaFornecedorService.processar(usuarioId, texto)).cotacaoId();
        assertEquals(cotacaoWhats, cotacaoRetornadaPeloWhatsapp,
                "WhatsApp deve reaproveitar a cotação em andamento existente, não criar uma nova");

        CotacaoFornecedor cfWhats = comoTenant(() -> {
            List<CotacaoFornecedor> registros = cfRepository.findByCotacaoIdOrderByOrdem(cotacaoWhats);
            assertEquals(1, registros.size());
            return registros.get(0);
        });
        assertEquals(fornecedorId, cfWhats.getFornecedorId(),
                "matching por nome deve resolver pro fornecedor pré-existente, não criar um novo");

        // Reprocessa o mesmo texto (mesma técnica que o botão "Conferir resposta do
        // fornecedor" já usa em produção) pra obter o preview do canal WhatsApp.
        PreviewRespostaResponse previewWhats = comoTenant(() ->
                fornecedorRespostaService.gerarPreview(cotacaoWhats, fornecedorId, texto));

        // 5. Paridade: mesmos contadores...
        assertEquals(previewWeb.contadores(), previewWhats.contadores());

        // ...e mesmos itens, item a item, comparados por nomeItemBase (não por
        // itemBaseId — são UUIDs distintos entre as duas cotações).
        assertEquals(previewWeb.itens().size(), previewWhats.itens().size());
        Map<String, ItemConferenciaResponse> itensWebPorNome = previewWeb.itens().stream()
                .collect(Collectors.toMap(ItemConferenciaResponse::nomeItemBase, i -> i));
        Map<String, ItemConferenciaResponse> itensWhatsPorNome = previewWhats.itens().stream()
                .collect(Collectors.toMap(ItemConferenciaResponse::nomeItemBase, i -> i));
        assertEquals(itensWebPorNome.keySet(), itensWhatsPorNome.keySet());

        for (String nomeItemBase : itensWebPorNome.keySet()) {
            ItemConferenciaResponse itemWeb = itensWebPorNome.get(nomeItemBase);
            ItemConferenciaResponse itemWhats = itensWhatsPorNome.get(nomeItemBase);

            assertEquals(itemWeb.status(), itemWhats.status(), "status diverge para " + nomeItemBase);
            assertEquals(itemWeb.motivos(), itemWhats.motivos(), "motivos divergem para " + nomeItemBase);
            assertEquals(itemWeb.preservado(), itemWhats.preservado(), "preservado diverge para " + nomeItemBase);
            assertEquals(itemWeb.precoAnteriorConfirmado(), itemWhats.precoAnteriorConfirmado(),
                    "precoAnteriorConfirmado diverge para " + nomeItemBase);

            assertEquals(itemWeb.candidatos().size(), itemWhats.candidatos().size(),
                    "quantidade de candidatos diverge para " + nomeItemBase);
            var candidatosWeb = itemWeb.candidatos().stream()
                    .sorted(Comparator.comparing(c -> c.textoOriginal())).toList();
            var candidatosWhats = itemWhats.candidatos().stream()
                    .sorted(Comparator.comparing(c -> c.textoOriginal())).toList();
            for (int i = 0; i < candidatosWeb.size(); i++) {
                var candWeb = candidatosWeb.get(i);
                var candWhats = candidatosWhats.get(i);
                assertEquals(candWeb.textoOriginal(), candWhats.textoOriginal(), "textoOriginal diverge para " + nomeItemBase);
                assertEquals(candWeb.precoInformado(), candWhats.precoInformado(), "precoInformado diverge para " + nomeItemBase);
                assertEquals(candWeb.marcaOferecida(), candWhats.marcaOferecida(), "marcaOferecida diverge para " + nomeItemBase);
                assertEquals(candWeb.confiancaMatch(), candWhats.confiancaMatch(), "confiancaMatch diverge para " + nomeItemBase);
                assertEquals(candWeb.semEstoque(), candWhats.semEstoque(), "semEstoque diverge para " + nomeItemBase);
            }
        }

        // Sanidade adicional: nenhum fornecedor duplicado foi criado pelo canal WhatsApp.
        // (jdbc puro exige TenantContext resolvido — RLS filtra tudo fora dele, mesmo
        // com WHERE tenant_id = ? explícito.)
        long totalFornecedores = comoTenant(() -> fornecedorRepository.count());
        assertEquals(1L, totalFornecedores);
        assertNotEquals(cotacaoWeb, cotacaoWhats);
    }
}
