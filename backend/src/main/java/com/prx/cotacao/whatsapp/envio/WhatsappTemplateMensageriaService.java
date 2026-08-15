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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementação WhatsApp de {@link MensageriaService} — único adaptador concreto hoje.
 * Resolve o template cadastrado por {@code (tenantId, acaoClienteId)} — tentando
 * primeiro o cenário específico de {@link ContextoNotificacao#acao()}, e caindo pro
 * cenário {@link AcaoClienteEnum#NAO_IDENTIFICADO} (fallback universal) se não houver um
 * template ativo pro específico — e monta os parâmetros posicionais na ORDEM cadastrada
 * em {@link TemplateMensagem#getParametrosOrdenados()}, resolvendo cada identificador
 * contra {@link ContextoNotificacao#parametros()} (ver
 * {@code NotificacaoParametrosFactory}, {@code CatalogoParametrosNotificacao}).
 *
 * <p>Nunca lança exceção: sem template ativo cadastrado (nem específico, nem fallback)
 * para o tenant, só loga e não envia nada.</p>
 */
@Service
public class WhatsappTemplateMensageriaService implements MensageriaService {

    private static final Logger log = LoggerFactory.getLogger(WhatsappTemplateMensageriaService.class);

    private final TemplateMensagemRepository templateRepository;
    private final AcaoClienteCenarioRepository cenarioRepository;
    private final WhatsappMessageSender messageSender;

    public WhatsappTemplateMensageriaService(TemplateMensagemRepository templateRepository,
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
        Optional<TemplateMensagem> template = buscarEspecifico(contexto.tenantId(), contexto.acao(), resultado)
                .or(() -> buscarFallback(contexto.tenantId()));

        if (template.isEmpty()) {
            log.warn("Nenhum template ativo configurado (acao={}, resultado={}): tenantId={} — recibo não enviado",
                    contexto.acao(), resultado, contexto.tenantId());
            return;
        }

        List<String> parametros = template.get().getParametrosOrdenados().stream()
                .map(id -> contexto.parametros().getOrDefault(id, ""))
                .toList();

        messageSender.enviarTemplate(contexto.destinatario(), template.get().getNomeTemplateMeta(),
                template.get().getIdioma(), parametros);
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
