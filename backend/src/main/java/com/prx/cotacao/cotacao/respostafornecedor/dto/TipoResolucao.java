package com.prx.cotacao.cotacao.respostafornecedor.dto;

public enum TipoResolucao {
    ACEITAR_SUGESTAO,
    SELECIONAR_CANDIDATO,
    EDITAR_MANUAL,
    SEM_OFERTA,
    /** Item extra do fornecedor (sem correspondência na lista base) vira um {@code CotacaoProduto} novo. */
    ADICIONAR_A_LISTA,
    /** Item extra do fornecedor é associado manualmente a um item base já existente. */
    ASSOCIAR_A_ITEM,
    /**
     * Um candidato NÃO selecionado de um item com múltiplas opções (marcas diferentes
     * do mesmo produto) vira um {@code CotacaoProduto} novo, sem tocar no item
     * original. Usa {@code itemBaseId} (o item MULTIPLE_OPTIONS de origem) +
     * {@code textoOriginalExtra} (identifica QUAL candidato dentro de
     * {@code item.candidatos()} está sendo transformado em item novo) +
     * {@code textoOriginalSelecionado}/{@code precoInformado} (texto/preço finais,
     * editáveis pelo operador antes de confirmar — diferente de
     * {@code SELECIONAR_CANDIDATO}, que nunca aceita override de preço do cliente).
     */
    ADICIONAR_CANDIDATO_A_LISTA
}
