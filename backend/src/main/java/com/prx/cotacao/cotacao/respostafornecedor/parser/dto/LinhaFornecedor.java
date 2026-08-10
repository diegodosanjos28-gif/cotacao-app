package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;

import java.math.BigDecimal;

/**
 * Linha parseada de uma resposta de fornecedor.
 *
 * @param textoOriginal      texto bruto da (sub)linha, já desmembrada se a linha original
 *                           trazia mais de um produto separado por "|"/";"
 * @param nomeProduto        nome/descrição extraído da linha (null para linhas ignoradas)
 * @param preco              preço reconhecido, ou null se não extraível/sem estoque/sem preço
 * @param semEstoque         true se a linha sinaliza "sem estoque"/"não tenho"/etc.
 * @param ignorada           true para linhas de saudação/cabeçalho/observação — devem ser
 *                           filtradas pelo caller antes de processar
 * @param embalagemDetectada tipo de embalagem + qtd interna detectados por TEXTO na linha
 *                           inteira (referência interna — não altera a interpretação do
 *                           preço), via {@link MatchingProdutoService#detectarEmbalagemPorTexto}
 * @param marcaOferecida     marca extraída da descrição, via
 *                           {@link MatchingProdutoService#extrairMarca}
 * @param precoBase          "unidade" (padrão) ou "sem_preco" (linha do tipo
 *                           "consultar"/"a combinar"/etc — ver precoPendente)
 * @param precoPendente      true quando a linha é do tipo "consultar/a combinar/sob
 *                           consulta/ver preço/sob demanda" — preço ainda não informado
 * @param qtdInformada       quantidade informada pelo fornecedor no INÍCIO da linha, antes
 *                           da descrição (ex.: "3 cx Sazon..." → 3), ou null
 * @param unInformada        unidade informada junto com qtdInformada (ex.: "cx"), ou null
 */
public record LinhaFornecedor(
        String textoOriginal,
        String nomeProduto,
        BigDecimal preco,
        boolean semEstoque,
        boolean ignorada,
        EmbalagemDetectada embalagemDetectada,
        String marcaOferecida,
        String precoBase,
        boolean precoPendente,
        Integer qtdInformada,
        String unInformada
) {}
