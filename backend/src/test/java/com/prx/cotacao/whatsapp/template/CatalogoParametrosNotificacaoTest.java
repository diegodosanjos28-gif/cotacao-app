package com.prx.cotacao.whatsapp.template;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CatalogoParametrosNotificacao}: POJO puro, sem Spring/mocks — testa a regra de
 * composição do catálogo efetivo.
 *
 * <p>Cobre: catálogo específico de SUCESSO é aditivo ao genérico (nunca substitui);
 * cenários de ERRO (INSERIR_PRODUTOS/REGISTRAR_RESPOSTA) não têm catálogo específico por
 * decisão de produto; e o teste mais importante — nenhum identificador exclusivo de uma
 * ação vaza para o catálogo da outra.</p>
 */
class CatalogoParametrosNotificacaoTest {

    private static List<String> ids(List<CatalogoParametrosNotificacao.ItemCatalogo> catalogo) {
        return catalogo.stream().map(CatalogoParametrosNotificacao.ItemCatalogo::identificador).toList();
    }

    @Test
    void getEfetivo_insercaoProdutosSucesso_retornaEspecificoMaisGenerico() {
        List<String> ids = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO));

        assertEquals(Set.of("totalItens", "itensReconhecidos", "cotacaoTitulo", "tipoMensagem", "detalhe"),
                Set.copyOf(ids));
    }

    @Test
    void getEfetivo_registrarRespostaSucesso_retornaEspecificoMaisGenerico() {
        List<String> ids = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO));

        assertEquals(Set.of("nomeFornecedor", "totalItens", "cotacaoTitulo", "tipoMensagem", "detalhe"),
                Set.copyOf(ids));
    }

    @Test
    void getEfetivo_insercaoProdutosErro_retornaSoOGenerico() {
        List<String> ids = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.ERRO));

        assertEquals(Set.of("tipoMensagem", "detalhe"), Set.copyOf(ids),
                "sem dado de negócio confiável antes da falha de parsing — só o genérico fica disponível");
    }

    @Test
    void getEfetivo_registrarRespostaErro_retornaSoOGenerico() {
        List<String> ids = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.ERRO));

        assertEquals(Set.of("tipoMensagem", "detalhe"), Set.copyOf(ids),
                "sem dado de negócio confiável antes da falha de parsing — só o genérico fica disponível");
    }

    @Test
    void getEfetivo_naoIdentificado_resultadoNull_retornaSoOGenerico() {
        List<String> ids = ids(CatalogoParametrosNotificacao.getEfetivo(AcaoClienteEnum.NAO_IDENTIFICADO, null));

        assertEquals(Set.of("tipoMensagem", "detalhe"), Set.copyOf(ids));
    }

    @Test
    void getEfetivo_identificadorExclusivoDeRegistrarResposta_naoVazaParaInserirProdutos() {
        List<String> idsInserirProdutos = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO));

        assertFalse(idsInserirProdutos.contains("nomeFornecedor"),
                "nomeFornecedor é exclusivo de REGISTRAR_RESPOSTA e não deveria aparecer no catálogo de INSERIR_PRODUTOS");
    }

    @Test
    void getEfetivo_identificadorExclusivoDeInserirProdutos_naoVazaParaRegistrarResposta() {
        List<String> idsRegistrarResposta = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO));

        assertFalse(idsRegistrarResposta.contains("itensReconhecidos"),
                "itensReconhecidos é exclusivo de INSERIR_PRODUTOS e não deveria aparecer no catálogo de REGISTRAR_RESPOSTA");
    }

    @Test
    void getEfetivo_cotacaoTitulo_ehCompartilhadoEntreAsDuasAcoesPorDesign() {
        List<String> idsInserirProdutos = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.INSERIR_PRODUTOS, ResultadoAcaoCliente.SUCESSO));
        List<String> idsRegistrarResposta = ids(CatalogoParametrosNotificacao.getEfetivo(
                AcaoClienteEnum.REGISTRAR_RESPOSTA, ResultadoAcaoCliente.SUCESSO));

        assertTrue(idsInserirProdutos.contains("cotacaoTitulo") && idsRegistrarResposta.contains("cotacaoTitulo"),
                "cotacaoTitulo aparece nos dois catálogos por design — não é o vazamento que este teste busca detectar");
    }
}
