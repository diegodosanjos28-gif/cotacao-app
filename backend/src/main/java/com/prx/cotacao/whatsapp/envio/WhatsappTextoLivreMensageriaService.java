package com.prx.cotacao.whatsapp.envio;

import com.prx.cotacao.notificacao.ContextoNotificacao;
import com.prx.cotacao.notificacao.MensageriaService;
import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.repository.TemplateMensagemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementação WhatsApp de {@link MensageriaService} — único adaptador concreto hoje.
 * Envia Service Message em texto livre (Prompt 20, doc técnica seção 10.7) dentro da
 * Customer Service Window de 24h aberta pela mensagem que originou a notificação —
 * substitui o envio por Message Template do Prompt 18/19.
 *
 * <p>Resolve o template cadastrado por {@code (tenantId, acaoClienteId)} — tentando
 * primeiro o cenário específico de {@link ContextoNotificacao#acao()}, e caindo pro
 * cenário {@link AcaoClienteEnum#NAO_IDENTIFICADO} (fallback universal) se não houver um
 * template ativo pro específico — e substitui cada {@code {{identificador}}} presente em
 * {@link TemplateMensagem#getConteudo()} pelo valor real resolvido de
 * {@link ContextoNotificacao#parametros()} (ver {@code NotificacaoParametrosFactory},
 * {@code CatalogoParametrosNotificacao}). Sem posição/{{1}}/{{2}} — a Meta só exige isso
 * pra Message Template aprovado, não pra texto livre.</p>
 *
 * <p>Nunca lança exceção: sem template ativo cadastrado (nem específico, nem fallback)
 * para o tenant, sem conteúdo configurado, ou fora da janela de 24h, só loga e não envia
 * nada.</p>
 */
@Service
public class WhatsappTextoLivreMensageriaService implements MensageriaService {

    private static final Logger log = LoggerFactory.getLogger(WhatsappTextoLivreMensageriaService.class);

    private static final Duration JANELA_CUSTOMER_SERVICE = Duration.ofHours(24);

    // Mesmo padrão de TemplateMensagemAdminService.TOKEN_PATTERN — reconhece
    // {{identificador}} no texto.
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final TemplateMensagemRepository templateRepository;
    private final AcaoClienteCenarioRepository cenarioRepository;
    private final WhatsappMessageSender messageSender;

    public WhatsappTextoLivreMensageriaService(TemplateMensagemRepository templateRepository,
                                                AcaoClienteCenarioRepository cenarioRepository,
                                                WhatsappMessageSender messageSender) {
        this.templateRepository = templateRepository;
        this.cenarioRepository = cenarioRepository;
        this.messageSender = messageSender;
    }

    @Override
    public void enviarMensagemSucesso(ContextoNotificacao contexto) {
        enviar(contexto, ResultadoAcaoCliente.SUCESSO);
    }

    @Override
    public void enviarMensagemErro(ContextoNotificacao contexto) {
        enviar(contexto, ResultadoAcaoCliente.ERRO);
    }

    private void enviar(ContextoNotificacao contexto, ResultadoAcaoCliente resultado) {
        // Checagem defensiva antes de qualquer lookup/HTTP — no desenho síncrono atual
        // a janela está por construção quase sempre aberta (é a própria mensagem que
        // disparou este processamento), mas fila atrasada/reprocessamento manual podem
        // violar essa suposição. Redundante de propósito com a detecção do erro 131047
        // em MetaWhatsappGraphClient (defesa em profundidade).
        if (janelaFechada(contexto.mensagemRecebidaEm())) {
            log.warn("Janela de 24h de Customer Service Window fechada, recibo não enviado: "
                            + "tenantId={}, acao={}, resultado={}, mensagemRecebidaEm={}",
                    contexto.tenantId(), contexto.acao(), resultado, contexto.mensagemRecebidaEm());
            return;
        }

        Optional<TemplateMensagem> template = buscarEspecifico(contexto.tenantId(), contexto.acao(), resultado)
                .or(() -> buscarFallback(contexto.tenantId()));

        if (template.isEmpty() || template.get().getConteudo() == null || template.get().getConteudo().isBlank()) {
            log.warn("Nenhum template ativo com conteúdo configurado (acao={}, resultado={}): tenantId={} — "
                    + "recibo não enviado", contexto.acao(), resultado, contexto.tenantId());
            return;
        }

        String corpo = substituirParametros(template.get().getConteudo(), contexto.parametros());
        messageSender.enviarTexto(contexto.destinatario(), corpo);
    }

    private boolean janelaFechada(Instant mensagemRecebidaEm) {
        return Duration.between(mensagemRecebidaEm, Instant.now()).compareTo(JANELA_CUSTOMER_SERVICE) >= 0;
    }

    // Passagem ÚNICA sobre o conteúdo original via regex (não um encadeamento de
    // String.replace) — um valor de parâmetro (ex. nomeFornecedor, digitado livremente
    // pelo próprio cliente no WhatsApp) que por acaso contenha algo como
    // "{{cotacaoTitulo}}" nunca é reprocessado como um token real: cada match do
    // Matcher aponta pra uma posição do texto ORIGINAL, já consumida, então o valor
    // substituído não pode ser "escaneado de novo" por uma iteração seguinte.
    private String substituirParametros(String conteudo, Map<String, String> parametros) {
        Matcher matcher = TOKEN_PATTERN.matcher(conteudo);
        StringBuilder resultado = new StringBuilder();
        while (matcher.find()) {
            String valor = parametros.getOrDefault(matcher.group(1), matcher.group(0));
            matcher.appendReplacement(resultado, Matcher.quoteReplacement(valor));
        }
        matcher.appendTail(resultado);
        return resultado.toString();
    }

    private Optional<TemplateMensagem> buscarEspecifico(UUID tenantId, AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        if (acao == AcaoClienteEnum.NAO_IDENTIFICADO) {
            // NAO_IDENTIFICADO não tem vaga própria por resultado — ele É o fallback.
            return Optional.empty();
        }
        return cenarioRepository.findByAcaoAndResultado(acao, resultado)
                .flatMap(c -> templateRepository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(tenantId, c.getId()));
    }

    private Optional<TemplateMensagem> buscarFallback(UUID tenantId) {
        return cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO)
                .flatMap(c -> templateRepository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(tenantId, c.getId()));
    }
}
