package com.prx.cotacao.whatsapp.webhook;

import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import com.prx.cotacao.identidade.repository.UsuarioTelefoneAutorizadoRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.whatsapp.envio.WhatsappMessageSender;
import com.prx.cotacao.whatsapp.webhook.service.WhatsappAssinaturaValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checklist de verificação da Fase 3 (webhook + classificador + roteamento) — cobre os
 * 9 itens da seção de verificação do plano, ponta a ponta via {@code POST
 * /whatsapp/webhook} real (MockMvc exercita a cadeia de filtros de verdade, incluindo
 * SecurityConfig/TenantFilter/OSIV — não chama os services diretamente).
 *
 * <p>Usa MockMvc (não TestRestTemplate), mesmo padrão/motivo de
 * {@link com.prx.cotacao.identidade.auth.AuthIntegrationTest}: exercita a cadeia real
 * de filtros do Spring Security/Boot sem depender de socket HTTP real. É
 * deliberadamente um teste HTTP de ponta a ponta, e não testes unitários de cada
 * service isoladamente — a única forma de pegar bugs de coreografia
 * TenantContext/conexão JDBC sob open-in-view (ver javadoc de
 * IdentificacaoWhatsappService), que só se manifestam através do ciclo real de
 * requisição HTTP.</p>
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), mesma convenção dos demais
 * testes de integração deste pacote.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WhatsappWebhookControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private WhatsappAssinaturaValidator assinaturaValidator;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioTelefoneAutorizadoRepository telefoneRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private WhatsappMessageSender messageSender;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private UUID tenantId;
    private UUID usuarioAId;
    private UUID usuarioBId;
    private String numeroA;
    private String numeroB;

    // Segundo tenant — só para o teste de batching cross-tenant (ver
    // duasMensagensDeTenantsDiferentesNoMesmoPayloadNaoSeCruzam).
    private UUID tenantId2;
    private UUID usuarioCId;
    private String numeroC;

    @BeforeEach
    void seed() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant t = new Tenant();
            t.setNomeFantasia("Tenant Webhook Test");
            t.setStatus(TenantStatus.TRIAL);
            tenantId = tenantRepository.save(t).getId();

            Tenant t2 = new Tenant();
            t2.setNomeFantasia("Tenant Webhook Test 2");
            t2.setStatus(TenantStatus.TRIAL);
            tenantId2 = tenantRepository.save(t2).getId();

            usuarioAId = usuarioRepository.save(novoUsuario(tenantId)).getId();
            usuarioBId = usuarioRepository.save(novoUsuario(tenantId)).getId();
            usuarioCId = usuarioRepository.save(novoUsuario(tenantId2)).getId();

            numeroA = numeroUnico();
            numeroB = numeroUnico();
            numeroC = numeroUnico();
            telefoneRepository.save(novoTelefone(usuarioAId, tenantId, numeroA));
            telefoneRepository.save(novoTelefone(usuarioBId, tenantId, numeroB));
            telefoneRepository.save(novoTelefone(usuarioCId, tenantId2, numeroC));
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            for (UUID tid : new UUID[]{tenantId, tenantId2}) {
                jdbc.update("""
                        DELETE FROM cotacao_produto_fornecedor WHERE cotacao_produto_id IN
                        (SELECT id FROM cotacao_produto WHERE cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = ?))
                        """, tid);
                jdbc.update("DELETE FROM cotacao_fornecedor WHERE tenant_id = ?", tid);
                jdbc.update("DELETE FROM cotacao_produto WHERE cotacao_id IN (SELECT id FROM cotacao WHERE tenant_id = ?)", tid);
                jdbc.update("DELETE FROM cotacao WHERE tenant_id = ?", tid);
                jdbc.update("DELETE FROM fornecedor WHERE tenant_id = ?", tid);
                jdbc.update("DELETE FROM produto WHERE tenant_id = ?", tid);
                jdbc.update("DELETE FROM usuario_telefone_autorizado WHERE tenant_id = ?", tid);
                jdbc.update("DELETE FROM usuario WHERE tenant_id = ?", tid);
                jdbc.update("DELETE FROM tenant WHERE id = ?", tid);
            }
            jdbc.update("DELETE FROM whatsapp_mensagem_processada WHERE numero_origem IN (?, ?, ?)", numeroA, numeroB, numeroC);
            return null;
        });
        TenantContext.clear();
    }

    // ── Checklist #8 (metade): assinatura inválida rejeitada ──────────────────

    @Test
    void assinaturaInvalidaRejeitadaSemSideEffect() throws Exception {
        String corpo = payloadTexto(numeroA, "wamid-assinatura-invalida", "LISTA_PRODUTOS\n1 un Teste");

        mockMvc.perform(post("/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                        .content(corpo))
                .andExpect(status().isForbidden());

        assertEquals(0, contarCotacoesDoTenant());
    }

    // ── Checklist #6: número não cadastrado — descartada, logada, sem side-effect ──

    @Test
    void numeroNaoCadastradoDescartaSemSideEffect() throws Exception {
        String numeroDesconhecido = "+5599" + String.format("%09d", SEQ.incrementAndGet());
        String corpo = payloadTexto(numeroDesconhecido, "wamid-numero-desconhecido", "LISTA_PRODUTOS\n1 un Teste");

        postAssinado(corpo).andExpect(status().isOk());

        assertEquals(0, contarCotacoesDoTenant());
    }

    // ── Formato não reconhecido: sem header LISTA_PRODUTOS/RESPOSTA_FORNECEDOR
    // reconhecido na 1ª linha → rejeitada, sem side-effect, com recibo de erro ────

    @Test
    void formatoNaoReconhecidoNaoCriaCotacaoNemFornecedorEEnviaRecibo() throws Exception {
        String corpo = payloadTexto(numeroA, "wamid-formato-invalido", "oi tudo bem, segue a lista");

        postAssinado(corpo).andExpect(status().isOk());

        assertEquals(0, contarCotacoesDoTenant());
        Integer totalFornecedores = comoAdmin(() -> jdbc.queryForObject(
                "SELECT count(*) FROM fornecedor WHERE tenant_id = ?", Integer.class, tenantId));
        assertEquals(0, totalFornecedores);

        // O "from" da Meta chega sem o "+" (ver payloadTexto) — é esse formato que
        // acaba em mensagem.numeroOrigem() e, por sua vez, no destino do envio.
        verify(messageSender).enviar(eq(numeroA.substring(1)), contains("consegui identificar o formato"));
    }

    // ── Checklist #2: lista sem cotação em andamento → cria nova ───────────────

    @Test
    void listaSemCotacaoEmAndamentoCriaNova() throws Exception {
        String corpo = payloadTexto(numeroA, "wamid-lista-nova", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g");

        postAssinado(corpo).andExpect(status().isOk());

        var cotacoes = comoAdmin(() -> jdbc.queryForList(
                "SELECT id, status, canal_origem, lista_revisada, criado_por FROM cotacao WHERE tenant_id = ?", tenantId));
        assertEquals(1, cotacoes.size());
        assertEquals("EM_ANDAMENTO", cotacoes.get(0).get("status"));
        assertEquals("WHATSAPP", cotacoes.get(0).get("canal_origem"));
        assertEquals(false, cotacoes.get(0).get("lista_revisada"));
        assertEquals(usuarioAId, cotacoes.get(0).get("criado_por"));
    }

    // ── Checklist #1: lista com cotação em andamento do usuário → anexa ────────

    @Test
    void listaComCotacaoEmAndamentoAnexa() throws Exception {
        postAssinado(payloadTexto(numeroA, "wamid-anexa-1", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g")).andExpect(status().isOk());
        UUID primeiraCotacaoId = (UUID) comoAdmin(() -> jdbc.queryForList(
                "SELECT id FROM cotacao WHERE tenant_id = ?", tenantId)).get(0).get("id");

        postAssinado(payloadTexto(numeroA, "wamid-anexa-2", "LISTA_PRODUTOS\n2 un Detergente Ype 500ml")).andExpect(status().isOk());

        var cotacoes = comoAdmin(() -> jdbc.queryForList("SELECT id FROM cotacao WHERE tenant_id = ?", tenantId));
        assertEquals(1, cotacoes.size(), "deve continuar sendo UMA única cotação (anexou, não criou nova)");
        assertEquals(primeiraCotacaoId, cotacoes.get(0).get("id"));

        Integer totalItens = comoAdmin(() -> jdbc.queryForObject(
                "SELECT count(*) FROM cotacao_produto WHERE cotacao_id = ?", Integer.class, primeiraCotacaoId));
        assertEquals(2, totalItens);
    }

    // ── Checklist #3: dois usuários do mesmo tenant não se cruzam ──────────────

    @Test
    void doisUsuariosMesmoTenantNaoCruzam() throws Exception {
        postAssinado(payloadTexto(numeroA, "wamid-usuarioA", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g")).andExpect(status().isOk());
        postAssinado(payloadTexto(numeroB, "wamid-usuarioB", "LISTA_PRODUTOS\n1 un Feijao Carioca 1kg")).andExpect(status().isOk());

        var cotacoes = comoAdmin(() -> jdbc.queryForList(
                "SELECT criado_por FROM cotacao WHERE tenant_id = ? ORDER BY criado_em", tenantId));
        assertEquals(2, cotacoes.size(), "cada usuário deve ter sua própria cotação, sem se cruzar");
        assertEquals(usuarioAId, cotacoes.get(0).get("criado_por"));
        assertEquals(usuarioBId, cotacoes.get(1).get("criado_por"));
    }

    // ── Checklist #7: cotação expirada (>48h) → inicia nova ────────────────────

    @Test
    void cotacaoExpiradaIniciaNovaEmVezDeAnexar() throws Exception {
        postAssinado(payloadTexto(numeroA, "wamid-expira-1", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g")).andExpect(status().isOk());
        UUID primeiraCotacaoId = (UUID) comoAdmin(() -> jdbc.queryForList(
                "SELECT id FROM cotacao WHERE tenant_id = ?", tenantId)).get(0).get("id");

        comoAdmin(() -> jdbc.update(
                "UPDATE cotacao SET ultima_atividade_em = NOW() - INTERVAL '49 hours' WHERE id = ?", primeiraCotacaoId));

        postAssinado(payloadTexto(numeroA, "wamid-expira-2", "LISTA_PRODUTOS\n1 un Feijao Carioca 1kg")).andExpect(status().isOk());

        var cotacoes = comoAdmin(() -> jdbc.queryForList("SELECT id FROM cotacao WHERE tenant_id = ? ORDER BY criado_em", tenantId));
        assertEquals(2, cotacoes.size(), "cotação expirada não pode absorver a mensagem — precisa criar uma nova");
        assertNotEquals(primeiraCotacaoId, cotacoes.get(1).get("id"));
    }

    // ── Checklist #4/#5: resposta de fornecedor conhecido → atribui; novo → PENDENTE_DADOS/WHATSAPP_AUTO ──

    @Test
    void respostaDeFornecedorConhecidoPorSimilaridadeAtribuiSemCriarNovo() throws Exception {
        UUID fornecedorId = comoAdmin(() -> tx.execute(status -> jdbc.queryForObject(
                "INSERT INTO fornecedor (id, tenant_id, nome, status, origem_cadastro, criado_em) " +
                        "VALUES (gen_random_uuid(), ?, 'Distribuidora Silva Ltda', 'ATIVO', 'MANUAL', NOW()) RETURNING id",
                UUID.class, tenantId)));

        postAssinado(payloadTexto(numeroA, "wamid-forn-lista", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g")).andExpect(status().isOk());
        postAssinado(payloadTexto(numeroA, "wamid-forn-resposta",
                "RESPOSTA_FORNECEDOR\nDistribuidora Silva\n3 cx Sazon Legumes 60g R$ 45,00")).andExpect(status().isOk());

        Integer totalFornecedores = comoAdmin(() -> jdbc.queryForObject(
                "SELECT count(*) FROM fornecedor WHERE tenant_id = ?", Integer.class, tenantId));
        assertEquals(1, totalFornecedores, "não deveria criar um fornecedor novo — devia casar por similaridade com o existente");

        // Prompt 15 (unificação Web/WhatsApp): processar() nunca persiste em
        // cotacao_produto_fornecedor por si só — só gera preview
        // (CotacaoFornecedor.status = PROCESSADO). Persistência de fato exige
        // POST .../confirmar, mesmo sem nenhuma divergência.
        Integer totalRespostas = comoAdmin(() -> jdbc.queryForObject(
                "SELECT count(*) FROM cotacao_produto_fornecedor WHERE fornecedor_id = ?",
                Integer.class, fornecedorId));
        assertEquals(0, totalRespostas, "webhook não deve persistir nada sem confirmação explícita do operador");

        String statusCotacaoFornecedor = comoAdmin(() -> jdbc.queryForObject(
                "SELECT status FROM cotacao_fornecedor WHERE fornecedor_id = ?", String.class, fornecedorId));
        assertEquals("PROCESSADO", statusCotacaoFornecedor,
                "fornecedor casado por similaridade fica PROCESSADO (preview gerado), aguardando confirmação explícita");
    }

    @Test
    void respostaDeFornecedorNovoCriaComPendenteDadosEWhatsappAuto() throws Exception {
        postAssinado(payloadTexto(numeroA, "wamid-forn-novo-lista", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g")).andExpect(status().isOk());
        postAssinado(payloadTexto(numeroA, "wamid-forn-novo-resposta",
                "RESPOSTA_FORNECEDOR\nFornecedor Nunca Visto Ltda\n3 cx Sazon Legumes 60g R$ 45,00")).andExpect(status().isOk());

        var fornecedores = comoAdmin(() -> jdbc.queryForList(
                "SELECT status, origem_cadastro FROM fornecedor WHERE tenant_id = ?", tenantId));
        assertEquals(1, fornecedores.size());
        assertEquals("PENDENTE_DADOS", fornecedores.get(0).get("status"));
        assertEquals("WHATSAPP_AUTO", fornecedores.get(0).get("origem_cadastro"));
    }

    // ── Checklist #8 (metade): message_id duplicado é idempotente ──────────────

    @Test
    void messageIdDuplicadoNaoReprocessa() throws Exception {
        String corpo = payloadTexto(numeroA, "wamid-duplicado-fixo", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g");
        String assinatura = assinaturaValidator.assinar(corpo);

        mockMvc.perform(post("/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", assinatura)
                        .content(corpo))
                .andExpect(status().isOk());
        mockMvc.perform(post("/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", assinatura)
                        .content(corpo))
                .andExpect(status().isOk());

        Integer totalCotacoes = comoAdmin(() -> jdbc.queryForObject(
                "SELECT count(*) FROM cotacao WHERE tenant_id = ?", Integer.class, tenantId));
        assertEquals(1, totalCotacoes, "o mesmo message_id processado duas vezes não pode duplicar side-effect");
    }

    // ── Achado do security-reviewer: a Meta pode enviar VÁRIAS mensagens num único
    // POST (batching de entry[].changes[].value.messages[]), plausivelmente de
    // operadores de TENANTS diferentes (o número WhatsApp Business é compartilhado
    // pela plataforma, não por tenant). Regressão direta do bug de OSIV/TenantContext
    // corrigido nesta fase — ver javadoc de WhatsappWebhookService.processar. ──────

    @Test
    void duasMensagensDeTenantsDiferentesNoMesmoPayloadNaoSeCruzam() throws Exception {
        String corpo = payloadDuasMensagens(
                numeroA, "wamid-batch-tenant1", "LISTA_PRODUTOS\n3 cx Sazon Legumes 60g",
                numeroC, "wamid-batch-tenant2", "LISTA_PRODUTOS\n1 un Feijao Carioca 1kg");

        postAssinado(corpo).andExpect(status().isOk());

        var cotacoesTenant1 = comoAdmin(() -> jdbc.queryForList(
                "SELECT criado_por FROM cotacao WHERE tenant_id = ?", tenantId));
        var cotacoesTenant2 = comoAdmin(() -> jdbc.queryForList(
                "SELECT criado_por FROM cotacao WHERE tenant_id = ?", tenantId2));

        assertEquals(1, cotacoesTenant1.size(), "tenant 1 deveria ter processado sua mensagem corretamente");
        assertEquals(usuarioAId, cotacoesTenant1.get(0).get("criado_por"));
        assertEquals(1, cotacoesTenant2.size(), "tenant 2 deveria ter processado sua mensagem corretamente, não falhar nem vazar pro tenant 1");
        assertEquals(usuarioCId, cotacoesTenant2.get(0).get("criado_por"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions postAssinado(String corpo) throws Exception {
        return mockMvc.perform(post("/whatsapp/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", assinaturaValidator.assinar(corpo))
                .content(corpo));
    }

    private String payloadTexto(String numeroOrigem, String messageId, String texto) {
        // Formato mínimo do Cloud API da Meta que WhatsappWebhookPayload.extrairMensagens() consome.
        String numeroSemMais = numeroOrigem.startsWith("+") ? numeroOrigem.substring(1) : numeroOrigem;
        String textoEscapado = texto.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"e1","changes":[{"field":"messages","value":{"messages":[
                {"from":"%s","id":"%s","timestamp":"1234567890","type":"text","text":{"body":"%s"}}
                ]}}]}]}
                """.formatted(numeroSemMais, messageId, textoEscapado);
    }

    // Duas mensagens no MESMO entry/change — espelha o batching real da Meta (várias
    // mensagens em value.messages[] de um único POST), usado para o teste de
    // regressão do bug de OSIV/TenantContext entre mensagens de tenants diferentes.
    private String payloadDuasMensagens(String numero1, String messageId1, String texto1,
                                         String numero2, String messageId2, String texto2) {
        String num1 = numero1.startsWith("+") ? numero1.substring(1) : numero1;
        String num2 = numero2.startsWith("+") ? numero2.substring(1) : numero2;
        String texto1Escapado = texto1.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String texto2Escapado = texto2.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"e1","changes":[{"field":"messages","value":{"messages":[
                {"from":"%s","id":"%s","timestamp":"1234567890","type":"text","text":{"body":"%s"}},
                {"from":"%s","id":"%s","timestamp":"1234567891","type":"text","text":{"body":"%s"}}
                ]}}]}]}
                """.formatted(num1, messageId1, texto1Escapado, num2, messageId2, texto2Escapado);
    }

    private int contarCotacoesDoTenant() {
        Integer count = comoAdmin(() -> jdbc.queryForObject("SELECT count(*) FROM cotacao WHERE tenant_id = ?", Integer.class, tenantId));
        return count == null ? 0 : count;
    }

    // Asserções rodam FORA de qualquer requisição HTTP — sem isso, TenantContext está
    // limpo (WhatsappWebhookService.processar limpa no finally ao fim de cada
    // mensagem) e o RLS bloquearia a visibilidade das linhas mesmo que elas existam
    // (is_admin_request() OR tenant_id = current_tenant_id(), ambos falsos com
    // contexto vazio) — mesma classe de bug corrigida no código de produção desta
    // fase, aqui no lado do teste.
    private <T> T comoAdmin(java.util.function.Supplier<T> fn) {
        TenantContext.setAdmin(true);
        try {
            return fn.get();
        } finally {
            TenantContext.clear();
        }
    }

    private Usuario novoUsuario(UUID tenantId) {
        Usuario u = new Usuario();
        u.setTenantId(tenantId);
        u.setEmail("whatsapp-webhook-test-" + UUID.randomUUID() + "@prx-test.com");
        u.setSenhaHash("hash-nao-importa");
        u.setPapel(Papel.OPERADOR_CLIENTE);
        return u;
    }

    private UsuarioTelefoneAutorizado novoTelefone(UUID usuarioId, UUID tenantId, String numero) {
        UsuarioTelefoneAutorizado t = new UsuarioTelefoneAutorizado();
        t.setUsuarioId(usuarioId);
        t.setTenantId(tenantId);
        t.setNumeroWhatsapp(numero);
        t.setAtivo(true);
        return t;
    }

    private String numeroUnico() {
        return "+5511" + String.format("%09d", SEQ.incrementAndGet());
    }
}
