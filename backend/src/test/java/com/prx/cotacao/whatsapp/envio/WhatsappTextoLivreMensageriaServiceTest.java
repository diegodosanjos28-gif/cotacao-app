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
import java.time.Duration;
import java.time.Instant;
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
 * Testes unitários de {@link WhatsappTextoLivreMensageriaService} (Prompt 20) — {@link
 * TemplateMensagemRepository}, {@link AcaoClienteCenarioRepository} e
 * {@link WhatsappMessageSender} mockados (Mockito puro, sem Spring). Foco: lookup
 * específico(acao,resultado)->fallback (NAO_IDENTIFICADO), comportamento silencioso
 * quando não há template ativo/conteúdo cadastrado, substituição de
 * {@code {{identificador}}} por valor real, e a checagem defensiva da janela de 24h.
 */
class WhatsappTextoLivreMensageriaServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String DESTINATARIO = "5511999990000";

    private TemplateMensagem template(String conteudo) {
        TemplateMensagem t = new TemplateMensagem();
        t.setConteudo(conteudo);
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
                .thenReturn(Optional.of(template("Resposta do fornecedor {{nomeFornecedor}} recebida, {{totalItens}} itens.")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.REGISTRAR_RESPOSTA,
                Instant.now(), Map.of("nomeFornecedor", "Fornecedor X", "totalItens", "3")));

        verify(messageSender).enviarTexto(DESTINATARIO, "Resposta do fornecedor Fornecedor X recebida, 3 itens.");
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
                .thenReturn(Optional.of(template("{{tipoMensagem}}: {{detalhe}}")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now(), Map.of("tipoMensagem", "Desconhecido", "detalhe", "formato não reconhecido")));

        verify(messageSender).enviarTexto(DESTINATARIO, "Desconhecido: formato não reconhecido");
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
                .thenReturn(Optional.of(template("{{tipoMensagem}}: {{detalhe}}")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.INSERIR_PRODUTOS,
                Instant.now(), Map.of("tipoMensagem", "Lista de produtos", "detalhe", "5 itens")));

        verify(messageSender).enviarTexto(DESTINATARIO, "Lista de produtos: 5 itens");
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

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        ContextoNotificacao contexto = new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now(), Map.of("tipoMensagem", "Desconhecido", "detalhe", "x"));

        assertDoesNotThrow(() -> service.enviarMensagemErro(contexto));

        verify(messageSender, never()).enviarTexto(any(), any());
    }

    @Test
    void conteudoNuloOuVazio_tratadoComoSemTemplate_naoEnvia() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, naoIdentificado.getId()))
                .thenReturn(Optional.of(template("   ")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now(), Map.of("tipoMensagem", "Desconhecido", "detalhe", "x")));

        verify(messageSender, never()).enviarTexto(any(), any());
    }

    @Test
    void valorDeParametroContendoTokenLiteral_naoEReprocessadoComoSubstituicao() {
        // Achado de security-reviewer: substituição em passagem única sobre o texto
        // ORIGINAL — um valor de parâmetro (ex. nome de fornecedor digitado livremente
        // pelo cliente) contendo "{{outroIdentificador}}" não pode ser reescaneado como
        // se fosse um token real de um encadeamento de String.replace.
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente respostaSucesso = cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO);
        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO))
                .thenReturn(Optional.of(respostaSucesso));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, respostaSucesso.getId()))
                .thenReturn(Optional.of(template("Fornecedor {{nomeFornecedor}}: {{totalItens}} itens.")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.REGISTRAR_RESPOSTA,
                Instant.now(), Map.of("nomeFornecedor", "Distribuidora {{totalItens}}", "totalItens", "7")));

        verify(messageSender).enviarTexto(DESTINATARIO, "Fornecedor Distribuidora {{totalItens}}: 7 itens.");
    }

    @Test
    void chaveAusenteNoMapaDeParametros_deixaTokenLiteralENaoQuebra() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, naoIdentificado.getId()))
                .thenReturn(Optional.of(template("{{tipoMensagem}}: {{detalhe}}")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        // Mapa vazio: nem "tipoMensagem" nem "detalhe" presentes — substituição por nome
        // não tem posição pra preencher com vazio, o token só permanece literal.
        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now(), Map.of()));

        verify(messageSender).enviarTexto(DESTINATARIO, "{{tipoMensagem}}: {{detalhe}}");
    }

    @Test
    void janelaFechada_25horasAtras_nuncaChamaMessageSender() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        ContextoNotificacao contexto = new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now().minus(Duration.ofHours(25)), Map.of("tipoMensagem", "Desconhecido", "detalhe", "x"));

        assertDoesNotThrow(() -> service.enviarMensagemErro(contexto));

        verify(messageSender, never()).enviarTexto(any(), any());
        // Janela fechada é checada ANTES do lookup — nenhum repository é consultado.
        verify(cenarioRepository, never()).findByAcaoAndResultadoIsNull(any());
    }

    @Test
    void janelaAberta_pertoDoLimite_aindaEnvia() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente naoIdentificado = cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null);
        when(cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO))
                .thenReturn(Optional.of(naoIdentificado));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, naoIdentificado.getId()))
                .thenReturn(Optional.of(template("{{tipoMensagem}}")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        ContextoNotificacao contexto = new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now().minus(Duration.ofHours(23).plusMinutes(59)), Map.of("tipoMensagem", "Desconhecido"));

        service.enviarMensagemErro(contexto);

        verify(messageSender).enviarTexto(DESTINATARIO, "Desconhecido");
    }

    @Test
    void janelaFechada_exatamente24h_naoEnvia() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);

        ContextoNotificacao contexto = new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.NAO_IDENTIFICADO,
                Instant.now().minus(Duration.ofHours(24)), Map.of("tipoMensagem", "Desconhecido"));

        assertDoesNotThrow(() -> service.enviarMensagemErro(contexto));

        verify(messageSender, never()).enviarTexto(any(), any());
    }

    @Test
    void quatroCenarios_insercaoProdutosERegistrarResposta_sucessoEErro_substituicaoPorNome() {
        TemplateMensagemRepository repository = mock(TemplateMensagemRepository.class);
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        WhatsappMessageSender messageSender = mock(WhatsappMessageSender.class);

        AcaoCliente insercaoSucesso = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);
        AcaoCliente insercaoErro = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.ERRO);
        AcaoCliente respostaSucesso = cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO);
        AcaoCliente respostaErro = cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.ERRO);

        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO))
                .thenReturn(Optional.of(insercaoSucesso));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, insercaoSucesso.getId()))
                .thenReturn(Optional.of(template("{{totalItens}} itens, {{itensReconhecidos}} reconhecidos.")));

        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.ERRO))
                .thenReturn(Optional.of(insercaoErro));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, insercaoErro.getId()))
                .thenReturn(Optional.of(template("Erro: {{detalhe}}")));

        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO))
                .thenReturn(Optional.of(respostaSucesso));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, respostaSucesso.getId()))
                .thenReturn(Optional.of(template("Fornecedor {{nomeFornecedor}}: {{totalItens}} itens.")));

        when(cenarioRepository.findByAcaoAndResultado(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.ERRO))
                .thenReturn(Optional.of(respostaErro));
        when(repository.findByTenantIdAndAcaoClienteIdAndAtivoTrue(TENANT_ID, respostaErro.getId()))
                .thenReturn(Optional.of(template("Erro: {{detalhe}}")));

        WhatsappTextoLivreMensageriaService service =
                new WhatsappTextoLivreMensageriaService(repository, cenarioRepository, messageSender);
        Instant agora = Instant.now();

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.INSERIR_PRODUTOS,
                agora, Map.of("totalItens", "5", "itensReconhecidos", "3")));
        verify(messageSender).enviarTexto(DESTINATARIO, "5 itens, 3 reconhecidos.");

        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.INSERIR_PRODUTOS,
                agora, Map.of("detalhe", "não foi possível processar")));
        verify(messageSender).enviarTexto(DESTINATARIO, "Erro: não foi possível processar");

        service.enviarMensagemSucesso(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.REGISTRAR_RESPOSTA,
                agora, Map.of("nomeFornecedor", "Atacadão", "totalItens", "12")));
        verify(messageSender).enviarTexto(DESTINATARIO, "Fornecedor Atacadão: 12 itens.");

        service.enviarMensagemErro(new ContextoNotificacao(TENANT_ID, DESTINATARIO, AcaoClienteEnum.REGISTRAR_RESPOSTA,
                agora, Map.of("detalhe", "resposta inválida")));
        verify(messageSender).enviarTexto(DESTINATARIO, "Erro: resposta inválida");
    }
}
