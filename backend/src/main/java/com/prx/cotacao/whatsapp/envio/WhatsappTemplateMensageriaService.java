package com.prx.cotacao.whatsapp.envio;

import com.prx.cotacao.notificacao.ContextoNotificacao;
import com.prx.cotacao.notificacao.MensageriaService;
import com.prx.cotacao.whatsapp.template.ResultadoNotificacao;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.repository.TemplateMensagemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Implementação WhatsApp de {@link MensageriaService} — único adaptador concreto hoje.
 * Resolve o template cadastrado por {@code (tenantId, resultado)} e monta os parâmetros
 * posicionais sempre na ordem {@code [tipoMensagem, detalhe]} (chaves preenchidas pelo
 * chamador em {@link ContextoNotificacao#parametros()} — ver
 * {@code NotificacaoParametrosFactory}).
 *
 * <p>Nunca lança exceção: sem template ativo cadastrado para o tenant, só loga e não
 * envia nada (mesmo efeito de "não configurado" ou "desativado" — mesma query cobre os
 * dois casos).</p>
 */
@Service
public class WhatsappTemplateMensageriaService implements MensageriaService {

    private static final Logger log = LoggerFactory.getLogger(WhatsappTemplateMensageriaService.class);

    private final TemplateMensagemRepository templateRepository;
    private final WhatsappMessageSender messageSender;

    public WhatsappTemplateMensageriaService(TemplateMensagemRepository templateRepository,
                                              WhatsappMessageSender messageSender) {
        this.templateRepository = templateRepository;
        this.messageSender = messageSender;
    }

    @Override
    public void enviarMensagemSucesso(ContextoNotificacao contexto) {
        enviar(contexto, ResultadoNotificacao.SUCESSO);
    }

    @Override
    public void enviarMensagemErro(ContextoNotificacao contexto) {
        enviar(contexto, ResultadoNotificacao.ERRO);
    }

    private void enviar(ContextoNotificacao contexto, ResultadoNotificacao resultado) {
        Optional<TemplateMensagem> template = templateRepository
                .findByTenantIdAndResultadoAndAtivoTrue(contexto.tenantId(), resultado);
        if (template.isEmpty()) {
            log.warn("Nenhum template {} ativo configurado: tenantId={} — recibo não enviado",
                    resultado, contexto.tenantId());
            return;
        }

        List<String> parametros = List.of(
                contexto.parametros().getOrDefault("tipoMensagem", "-"),
                contexto.parametros().getOrDefault("detalhe", ""));

        messageSender.enviarTemplate(contexto.destinatario(), template.get().getNomeTemplateMeta(),
                template.get().getIdioma(), parametros);
    }
}
