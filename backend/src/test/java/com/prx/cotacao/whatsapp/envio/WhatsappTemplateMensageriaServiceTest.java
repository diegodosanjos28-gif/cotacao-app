package com.prx.cotacao.whatsapp.envio;

import com.prx.cotacao.notificacao.ContextoNotificacao;
import com.prx.cotacao.whatsapp.template.ResultadoNotificacao;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.repository.TemplateMensagemRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitários de {@link WhatsappTemplateMensageriaService} — {@link
 * TemplateMensagemRepository} e {@link WhatsappMessageSender} mockados (Mockito puro,
 * sem Spring). Foco: qual query resolve o template por resultado (SUCESSO/ERRO), o
 * comportamento silencioso quando não há template ativo cadastrado, e a ordem fixa dos
 * parâmetros posicionais montados a partir do mapa do {@link ContextoNotificacao}.
 */
class WhatsappTemplateMensageriaServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String DESTINATARIO = "5511999990000";

    private TemplateMensagem template(String nomeTemplateMeta, String idioma) {
        TemplateMensagem t = new TemplateMensagem();
        t.setNomeTemplateMeta(nomeTemplateMeta);
        t.setIdioma(idioma);
        return t;
    }

    @Test
    void enviarMensagemSucesso_resolveTemplatePorResultadoSucesso() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);
        when(repository.findByTenantIdAndResultadoAndAtivoTrue(TENANT_ID, ResultadoNotificacao.SUCESSO))
                .thenReturn(Optional.of(template("tpl_sucesso", "pt_BR")));
        WhatsappTemplateMensageriaService service = new WhatsappTemplateMensageriaService(repository, messageSender);

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO,
                Map.of("tipoMensagem", "Lista de produtos", "detalhe", "5 itens")));

        verify(repository).findByTenantIdAndResultadoAndAtivoTrue(TENANT_ID, ResultadoNotificacao.SUCESSO);
        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_sucesso", "pt_BR",
                List.of("Lista de produtos", "5 itens"));
    }

    @Test
    void enviarMensagemErro_resolveTemplatePorResultadoErro() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);
        when(repository.findByTenantIdAndResultadoAndAtivoTrue(TENANT_ID, ResultadoNotificacao.ERRO))
                .thenReturn(Optional.of(template("tpl_erro", "pt_BR")));
        WhatsappTemplateMensageriaService service = new WhatsappTemplateMensageriaService(repository, messageSender);

        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO,
                Map.of("tipoMensagem", "Resposta de fornecedor", "detalhe", "falhou")));

        verify(repository).findByTenantIdAndResultadoAndAtivoTrue(TENANT_ID, ResultadoNotificacao.ERRO);
        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_erro", "pt_BR",
                List.of("Resposta de fornecedor", "falhou"));
    }

    @Test
    void semTemplateCadastradoOuAtivo_naoChamaMessageSenderENaoLancaExcecao() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);
        // Optional.empty() cobre tanto "não cadastrado" quanto "cadastrado com ativo=false"
        // — mesma query (findByTenantIdAndResultadoAndAtivoTrue), mesmo efeito aqui.
        when(repository.findByTenantIdAndResultadoAndAtivoTrue(any(), any())).thenReturn(Optional.empty());
        WhatsappTemplateMensageriaService service = new WhatsappTemplateMensageriaService(repository, messageSender);

        ContextoNotificacao contexto = new ContextoNotificacao(TENANT_ID, DESTINATARIO,
                Map.of("tipoMensagem", "Lista de produtos", "detalhe", "5 itens"));

        assertDoesNotThrow(() -> service.enviarMensagemSucesso(contexto));

        verify(messageSender, never()).enviarTemplate(any(), any(), any(), any());
    }

    @Test
    void chaveAusenteNoMapaDeParametros_usaDefaultDoGetOrDefaultENaoQuebra() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);
        when(repository.findByTenantIdAndResultadoAndAtivoTrue(TENANT_ID, ResultadoNotificacao.SUCESSO))
                .thenReturn(Optional.of(template("tpl_sucesso", "pt_BR")));
        WhatsappTemplateMensageriaService service = new WhatsappTemplateMensageriaService(repository, messageSender);

        // Mapa vazio: nem "tipoMensagem" nem "detalhe" presentes.
        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, Map.of()));

        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_sucesso", "pt_BR", List.of("-", ""));
    }
}
