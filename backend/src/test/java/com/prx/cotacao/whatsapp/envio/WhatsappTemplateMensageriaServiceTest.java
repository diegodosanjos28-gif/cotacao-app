package com.prx.cotacao.whatsapp.envio;

import com.prx.cotacao.notificacao.ContextoNotificacao;
import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.repository.TemplateMensagemRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
 * TemplateMensagemRepository}, {@link AcaoClienteCenarioRepository} e
 * {@link WhatsappMessageSender} mockados (Mockito puro, sem Spring). Foco: lookup
 * específico(acao,resultado)->fallback (NAO_IDENTIFICADO), comportamento silencioso
 * quando não há template ativo cadastrado, e a montagem dos parâmetros posicionais a
 * partir da ordem cadastrada em {@link TemplateMensagem#getParametrosOrdenados()}.
 */
class WhatsappTemplateMensageriaServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String DESTINATARIO = "5511999990000";

    private TemplateMensagem template(String nomeTemplateMeta, String idioma, List<String> parametrosOrdenados) {
        TemplateMensagem t = new TemplateMensagem();
        t.setNomeTemplateMeta(nomeTemplateMeta);
        t.setIdioma(idioma);
        t.setParametrosOrdenados(parametrosOrdenados);
        return t;
    }

    private AcaoCliente cenario(AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        AcaoCliente c = new AcaoCliente();
        c.setAcao(acao);
        c.setResultado(resultado);
        c.setDescricao("descrição de teste");
        setId(c, UUID.randomUUID());
        return c;
    }

    // AcaoCliente.id é @GeneratedValue, sem setter público — reflection só pra
    // dar um id determinístico e DISTINTO por instância (repository é mockado, nunca
    // toca banco de verdade; testes que criam 2 cenarios no mesmo teste precisam de ids
    // diferentes pra desambiguar os 2 stubs de templateRepository).
    private void setId(AcaoCliente c, UUID id) {
        try {
            Field f = AcaoCliente.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void enviarMensagemSucesso_comAcaoEspecificaUsaTemplateDaAcao() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente cenario = cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO);
        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO))
                .thenReturn(Optional.of(cenario));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, cenario.getId()))
                .thenReturn(Optional.of(template("tpl_resposta_sucesso", "pt_BR", List.of("nomeFornecedor", "totalItens"))));

        WhatsappTemplateMensageriaService service =
                new WhatsappTemplateMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.REGISTRAR_RESPOSTA,
                Map.of("nomeFornecedor", "Fornecedor X", "totalItens", "3")));

        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_resposta_sucesso", "pt_BR",
                List.of("Fornecedor X", "3"));
    }

    @Test
    void enviarMensagemErro_acaoNaoIdentificada_usaTemplateDoFallbackDiretamente() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, naoIdentificado.getId()))
                .thenReturn(Optional.of(template("tpl_fallback", "pt_BR", List.of("tipoMensagem", "detalhe"))));

        WhatsappTemplateMensageriaService service =
                new WhatsappTemplateMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Map.of("tipoMensagem", "Desconhecido", "detalhe", "formato não reconhecido")));

        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_fallback", "pt_BR",
                List.of("Desconhecido", "formato não reconhecido"));
    }

    @Test
    void enviarMensagemSucesso_acaoEspecificaSemTemplateCaiParaFallback() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente especifico = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);
        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO))
                .thenReturn(Optional.of(especifico));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, especifico.getId()))
                .thenReturn(Optional.empty());
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, naoIdentificado.getId()))
                .thenReturn(Optional.of(template("tpl_fallback", "pt_BR", List.of("tipoMensagem", "detalhe"))));

        WhatsappTemplateMensageriaService service =
                new WhatsappTemplateMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.INSERIR_PRODUTOS,
                Map.of("tipoMensagem", "Lista de produtos", "detalhe", "5 itens")));

        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_fallback", "pt_BR",
                List.of("Lista de produtos", "5 itens"));
    }

    @Test
    void semTemplateCadastradoOuAtivo_nemEspecificoNemFallback_naoChamaMessageSenderENaoLancaExcecao() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(any(), any())).thenReturn(Optional.empty());

        WhatsappTemplateMensageriaService service =
                new WhatsappTemplateMensageriaService(repository, cenarioRepository, messageSender);

        ContextoNotificacao contexto = new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Map.of("tipoMensagem", "Desconhecido", "detalhe", "x"));

        assertDoesNotThrow(() -> service.enviarMensagemErro(contexto));

        verify(messageSender, never()).enviarTemplate(any(), any(), any(), any());
    }

    @Test
    void chaveAusenteNoMapaDeParametros_usaStringVaziaENaoQuebra() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, naoIdentificado.getId()))
                .thenReturn(Optional.of(template("tpl_fallback", "pt_BR", List.of("tipoMensagem", "detalhe"))));

        WhatsappTemplateMensageriaService service =
                new WhatsappTemplateMensageriaService(repository, cenarioRepository, messageSender);

        // Mapa vazio: nem "tipoMensagem" nem "detalhe" presentes.
        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO, Map.of()));

        verify(messageSender).enviarTemplate(DESTINATARIO, "tpl_fallback", "pt_BR", List.of("", ""));
    }
}
