package com.prx.cotacao.whatsapp.template.resource;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.whatsapp.template.CatalogoParametrosNotificacao;
import com.prx.cotacao.whatsapp.template.service.TemplateMensagemAdminService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TemplateMensagemAdminController#parametrosDisponiveis}: o projeto não tem
 * convenção de {@code @WebMvcTest}/{@code MockMvc} para este pacote (nenhum
 * {@code *ControllerTest} usa esse padrão), então este teste chama o método do
 * controller diretamente, com {@link AcaoClienteCenarioRepository} mockado — sem
 * contexto Spring. Ele só delega para {@link CatalogoParametrosNotificacao#getEfetivo},
 * já coberto por {@code CatalogoParametrosNotificacaoTest}; aqui só confirma que o
 * endpoint devolve exatamente o que o catálogo devolve para os 5 cenários possíveis, e
 * que um {@code acaoClienteId} desconhecido dá 404.
 */
class TemplateMensagemAdminControllerTest {

    private static AcaoCliente cenario(AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        AcaoCliente c = new AcaoCliente();
        c.setAcao(acao);
        c.setResultado(resultado);
        c.setDescricao("descrição de teste");
        setId(c, UUID.randomUUID());
        return c;
    }

    private static void setId(AcaoCliente c, UUID id) {
        try {
            Field f = AcaoCliente.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stream<AcaoCliente> cenarios() {
        return Stream.of(
                cenario(AcaoClienteEnum.NAO_IDENTIFICADO, null),
                cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO),
                cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.ERRO),
                cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO),
                cenario(AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.ERRO));
    }

    @Test
    void parametrosDisponiveis_paraTodosOsCenarios_devolveOMesmoQueOCatalogo() {
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        TemplateMensagemAdminController controller =
                new TemplateMensagemAdminController(mock(TemplateMensagemAdminService.class), cenarioRepository);
        UUID tenantIdQualquer = UUID.randomUUID();

        cenarios().forEach(cenario -> {
            when(cenarioRepository.findById(cenario.getId())).thenReturn(Optional.of(cenario));

            List<CatalogoParametrosNotificacao.ItemCatalogo> esperado =
                    CatalogoParametrosNotificacao.getEfetivo(cenario.getAcao(), cenario.getResultado());
            List<CatalogoParametrosNotificacao.ItemCatalogo> obtido =
                    controller.parametrosDisponiveis(tenantIdQualquer, cenario.getId());

            assertEquals(esperado, obtido,
                    "endpoint deve devolver exatamente o catálogo efetivo para acao=" + cenario.getAcao()
                            + " resultado=" + cenario.getResultado());
        });
    }

    @Test
    void parametrosDisponiveis_naoDependeDoTenantId_pathVariableEhSoConsistenciaDeRota() {
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        TemplateMensagemAdminController controller =
                new TemplateMensagemAdminController(mock(TemplateMensagemAdminService.class), cenarioRepository);
        AcaoCliente cenario = cenario(AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO);
        when(cenarioRepository.findById(cenario.getId())).thenReturn(Optional.of(cenario));

        List<CatalogoParametrosNotificacao.ItemCatalogo> comTenantA =
                controller.parametrosDisponiveis(UUID.randomUUID(), cenario.getId());
        List<CatalogoParametrosNotificacao.ItemCatalogo> comTenantB =
                controller.parametrosDisponiveis(UUID.randomUUID(), cenario.getId());

        assertEquals(comTenantA, comTenantB);
        assertFalse(comTenantA.isEmpty());
    }

    @Test
    void parametrosDisponiveis_acaoClienteIdDesconhecido_lancaResourceNotFoundException() {
        AcaoClienteCenarioRepository cenarioRepository = mock(AcaoClienteCenarioRepository.class);
        TemplateMensagemAdminController controller =
                new TemplateMensagemAdminController(mock(TemplateMensagemAdminService.class), cenarioRepository);
        UUID idInexistente = UUID.randomUUID();
        when(cenarioRepository.findById(idInexistente)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> controller.parametrosDisponiveis(UUID.randomUUID(), idInexistente));
    }
}
