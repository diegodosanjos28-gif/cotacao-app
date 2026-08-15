package com.prx.cotacao.whatsapp.webhook.service;

import com.prx.cotacao.notificacao.ContextoNotificacao;
import com.prx.cotacao.notificacao.MensageriaService;
import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.shared.tenant.TenantContext;
import com.prx.cotacao.whatsapp.canal.enums.EventoWhatsApp;
import com.prx.cotacao.whatsapp.canal.service.ClassificadorMensagemWhatsapp;
import com.prx.cotacao.whatsapp.canal.service.IdentificacaoWhatsappService;
import com.prx.cotacao.whatsapp.canal.dto.MensagemNormalizada;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoClassificacao;
import com.prx.cotacao.whatsapp.canal.dto.TelefoneAutorizado;
import com.prx.cotacao.whatsapp.canal.dto.TelefoneLogUtils;
import com.prx.cotacao.whatsapp.canal.service.WhatsappListaProdutosService;
import com.prx.cotacao.whatsapp.canal.service.WhatsappRespostaFornecedorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.prx.cotacao.whatsapp.canal.enums.EventoWhatsApp.LISTA_PRODUTOS;
import static com.prx.cotacao.whatsapp.canal.enums.EventoWhatsApp.RESPOSTA_FORNECEDOR;

/**
 * Coordenador do webhook — deliberadamente SEM {@code @Transactional} na classe/métodos:
 * ele troca {@code TenantContext} fora de transação e delega a beans transacionais
 * distintos, nunca abre uma transação própria.
 *
 * <p>Ordem importa aqui, e é diferente do que pareceria natural (idempotência antes de
 * identificar o remetente): a identificação roda PRIMEIRO, via JDBC puro
 * ({@link IdentificacaoWhatsappService}, que não passa pelo EntityManager/JPA), e só
 * DEPOIS o {@code TenantContext} é setado para o tenant resolvido — ANTES de qualquer
 * chamada JPA (idempotência, lista, resposta de fornecedor). Motivo: com
 * {@code spring.jpa.open-in-view=true} (default do projeto), o EntityManager fica
 * vinculado à requisição HTTP inteira pelo OSIV, e sua conexão física só é obtida (e
 * {@code TenantAwareDataSource} só reaplica app.is_admin/app.current_tenant_id) na
 * PRIMEIRA consulta JPA da requisição — nem trocar o TenantContext no meio, nem usar
 * {@code Propagation.REQUIRES_NEW}, força uma conexão nova depois disso (Spring reusa o
 * EntityManager já vinculado à thread independente da propagação declarada,
 * comportamento confirmado testando esta fase manualmente).</p>
 *
 * <p><b>Achado do security-reviewer:</b> um único POST da Meta pode conter mensagens de
 * VÁRIOS remetentes num só payload (batching de {@code entry[].changes[].value.messages[]}),
 * plausivelmente de operadores de TENANTS diferentes (o número WhatsApp Business é
 * compartilhado pela plataforma inteira, não por tenant). Isso significa que a
 * ordenação "identificação antes da 1ª query JPA" só resolve o problema para a
 * PRIMEIRA mensagem do payload — a 2ª em diante reaproveitaria o MESMO EntityManager/
 * conexão (e, mais grave ainda, o MESMO Hibernate Filter de tenant, que só é habilitado
 * uma vez por Session e nunca reaplicado — ver {@code TenantAwareTransactionManager.
 * doBegin}) já vinculado ao tenant da 1ª mensagem. Por isso {@link #processar} roda
 * CADA mensagem em uma thread virtual própria (join síncrono, uma de cada vez — nunca
 * em paralelo, para não introduzir corrida em "buscar cotação em andamento" quando duas
 * mensagens do MESMO usuário vêm no mesmo lote): o binding do OSIV/
 * TransactionSynchronizationManager é por ThreadLocal, então uma thread nova nunca
 * herda o EntityManager da anterior — cada mensagem começa com uma conexão genuinamente
 * nova, igual ao início de uma requisição HTTP nova. {@link
 * com.prx.cotacao.shared.tenant.TenantContext} também é ThreadLocal, então também é
 * isolado automaticamente por mensagem dessa forma.</p>
 */
@Service
public class WhatsappWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsappWebhookService.class);

    private final IdentificacaoWhatsappService identificacaoService;
    private final IdempotenciaWhatsappService idempotenciaService;
    private final ClassificadorMensagemWhatsapp classificador;
    private final WhatsappListaProdutosService listaService;
    private final WhatsappRespostaFornecedorService respostaFornecedorService;
    private final MensageriaService mensageriaService;
    private final NotificacaoParametrosFactory parametrosFactory;

    public WhatsappWebhookService(IdentificacaoWhatsappService identificacaoService,
                                   IdempotenciaWhatsappService idempotenciaService,
                                   ClassificadorMensagemWhatsapp classificador,
                                   WhatsappListaProdutosService listaService,
                                   WhatsappRespostaFornecedorService respostaFornecedorService,
                                   MensageriaService mensageriaService,
                                   NotificacaoParametrosFactory parametrosFactory) {
        this.identificacaoService = identificacaoService;
        this.idempotenciaService = idempotenciaService;
        this.classificador = classificador;
        this.listaService = listaService;
        this.respostaFornecedorService = respostaFornecedorService;
        this.mensageriaService = mensageriaService;
        this.parametrosFactory = parametrosFactory;
    }

    public void processar(WhatsappWebhookPayload payload) {
        for (MensagemNormalizada mensagem : payload.extrairMensagens()) {
            processarEmThreadIsolada(mensagem);
        }
    }

    // Thread virtual dedicada por mensagem (ver javadoc da classe) — quebra o binding
    // do OSIV/TenantContext por ThreadLocal entre mensagens de tenants diferentes no
    // mesmo payload. join() bloqueia até terminar: mensagens processam uma de cada vez,
    // nunca em paralelo (evita corrida em "cotação em andamento" para o mesmo usuário).
    private void processarEmThreadIsolada(MensagemNormalizada mensagem) {
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                processarUmaMensagem(mensagem);
            } finally {
                // Defesa em profundidade: TenantFilter também limpa ao final da
                // requisição HTTP, mas essa limpeza roda na thread ORIGINAL do
                // request — cada thread virtual precisa limpar o próprio TenantContext.
                TenantContext.clear();
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Processamento de mensagem WhatsApp interrompido: messageId={}", mensagem.messageId());
        }
    }

    private void processarUmaMensagem(MensagemNormalizada mensagem) {
        // JDBC puro (ver javadoc da classe) — roda ANTES de qualquer JPA tocar a
        // conexão da requisição.
        TenantContext.setAdmin(true);
        Optional<TelefoneAutorizado> telefone;
        try {
            telefone = identificacaoService.buscarPorNumero(mensagem.numeroOrigem());
        } finally {
            TenantContext.clear();
        }

        if (telefone.isEmpty()) {
            log.info("Mensagem WhatsApp de número não cadastrado, descartada: numero={}",
                    TelefoneLogUtils.mascarar(mensagem.numeroOrigem()));
            return;
        }

        UUID usuarioId = telefone.get().usuarioId();
        UUID tenantId = telefone.get().tenantId();

        // Setado ANTES de qualquer chamada @Transactional — é o que garante que a
        // PRIMEIRA consulta JPA da requisição (idempotência, logo abaixo) já vincule o
        // EntityManager do OSIV à conexão com o tenant correto pelo resto da requisição.
        TenantContext.set(tenantId.toString());
        TenantContext.setAdmin(false);

        if (idempotenciaService.jaProcessadaOuRegistrar(mensagem.messageId(), mensagem.numeroOrigem())) {
            log.info("Mensagem WhatsApp já processada, ignorando: messageId={}", mensagem.messageId());
            return;
        }

        Optional<ResultadoClassificacao> classificacao = classificador.classificar(mensagem.texto());
        if (classificacao.isEmpty()) {
            log.info("Mensagem WhatsApp com formato não reconhecido: messageId={}", mensagem.messageId());
            mensageriaService.enviarMensagemErro(new ContextoNotificacao(
                    tenantId, mensagem.numeroOrigem(), AcaoClienteEnum.NAO_IDENTIFICADO, parametrosFactory.paraFormatoDesconhecido()));
            return;
        }

        EventoWhatsApp tipo = classificacao.get().tipo();
        AcaoClienteEnum acao = paraAcaoCliente(tipo);
        String corpo = classificacao.get().corpo();
        try {
            Map<String, String> parametros = switch (tipo) {
                case LISTA_PRODUTOS -> parametrosFactory.paraListaSucesso(listaService.processar(usuarioId, corpo));
                case RESPOSTA_FORNECEDOR ->
                        parametrosFactory.paraRespostaSucesso(respostaFornecedorService.processar(usuarioId, corpo));
            };
            mensageriaService.enviarMensagemSucesso(new ContextoNotificacao(tenantId, mensagem.numeroOrigem(), acao, parametros));
        } catch (RuntimeException e) {
            log.warn("Falha ao processar mensagem WhatsApp: messageId={}, tipo={}", mensagem.messageId(), tipo, e);
            Map<String, String> parametros = switch (tipo) {
                case LISTA_PRODUTOS -> parametrosFactory.paraListaErro();
                case RESPOSTA_FORNECEDOR -> parametrosFactory.paraRespostaErro();
            };
            mensageriaService.enviarMensagemErro(new ContextoNotificacao(tenantId, mensagem.numeroOrigem(), acao, parametros));
        }
    }

    // EventoWhatsApp (classificação de texto, canal-específica) -> AcaoClienteEnum (ação de
    // negócio, genérica/canal-agnóstica, ver pacote notificacao.acaocliente).
    private static AcaoClienteEnum paraAcaoCliente(EventoWhatsApp evento) {
        return switch (evento) {
            case LISTA_PRODUTOS -> AcaoClienteEnum.INSERIR_PRODUTOS;
            case RESPOSTA_FORNECEDOR -> AcaoClienteEnum.REGISTRAR_RESPOSTA;
        };
    }
}
