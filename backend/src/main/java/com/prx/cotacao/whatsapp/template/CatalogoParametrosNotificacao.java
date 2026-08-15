package com.prx.cotacao.whatsapp.template;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Catálogo fixo de identificadores de parâmetro disponíveis por cenário (ação ×
 * resultado, ver {@code notificacao.acaocliente.entity.AcaoCliente}) —
 * constante do sistema, não editável em runtime pelo tenant. Escopado por cenário, não
 * um enum plano único, porque o conjunto de identificadores varia por combinação.
 *
 * <p>O catálogo específico de uma ação é ADITIVO ao genérico: um template de
 * INSERIR_PRODUTOS×SUCESSO pode usar tanto {@code totalItens}/{@code itensReconhecidos}/
 * {@code cotacaoTitulo} quanto {@code tipoMensagem}/{@code detalhe} do genérico.</p>
 *
 * <p>Cenários de ERRO (INSERIR_PRODUTOS×ERRO, REGISTRAR_RESPOSTA×ERRO) não têm catálogo
 * específico — decisão deliberada: não há dado de negócio confiável disponível antes da
 * falha de parsing, então só o genérico fica disponível para eles. NAO_IDENTIFICADO
 * (mensagem que nem chegou a ser classificada, resultado sempre null) também não tem
 * catálogo próprio.</p>
 */
public final class CatalogoParametrosNotificacao {

    public record ItemCatalogo(String identificador, String rotulo) {}

    private static final List<ItemCatalogo> GENERICO = List.of(
            new ItemCatalogo("tipoMensagem", "Tipo da mensagem"),
            new ItemCatalogo("detalhe", "Detalhe"));

    private static final Map<AcaoClienteEnum, List<ItemCatalogo>> ESPECIFICOS_SUCESSO = Map.of(
            AcaoClienteEnum.INSERIR_PRODUTOS, List.of(
                    new ItemCatalogo("totalItens", "Total de itens"),
                    new ItemCatalogo("itensReconhecidos", "Itens reconhecidos"),
                    new ItemCatalogo("cotacaoTitulo", "Título da cotação")),
            AcaoClienteEnum.REGISTRAR_RESPOSTA, List.of(
                    new ItemCatalogo("nomeFornecedor", "Nome do fornecedor"),
                    new ItemCatalogo("totalItens", "Total de itens"),
                    new ItemCatalogo("cotacaoTitulo", "Título da cotação")));

    private CatalogoParametrosNotificacao() {}

    /** Catálogo efetivo = específico(acao,resultado) ∪ genérico. resultado null => só genérico. */
    public static List<ItemCatalogo> getEfetivo(AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        List<ItemCatalogo> especifico = resultado == ResultadoAcaoCliente.SUCESSO
                ? ESPECIFICOS_SUCESSO.getOrDefault(acao, List.of())
                : List.of();
        return Stream.concat(especifico.stream(), GENERICO.stream()).toList();
    }
}
