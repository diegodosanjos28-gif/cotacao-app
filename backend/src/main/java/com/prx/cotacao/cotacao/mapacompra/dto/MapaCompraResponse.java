package com.prx.cotacao.cotacao.mapacompra.dto;

import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MapaCompraResponse(
        CenarioSelecionado cenario,
        List<DistribuicaoFornecedor> distribuicoes,
        List<ProdutoSemFornecedor> produtosSemFornecedor,
        BigDecimal totalGeral,
        // Comparação contra o "pior cenário": para cada item, o maior preço unitário
        // ofertado entre os fornecedores válidos, somado. É o teto de gasto possível
        // pra essa lista, não o resultado de rodar os outros 2 cenários.
        BigDecimal economiaComparadaAoPior,
        // Itens removidos da ordem por ajuste manual (CotacaoAjusteManualService) —
        // diferente de produtosSemFornecedor: esses têm oferta válida, só foram
        // excluídos por escolha do operador. Sobrevive à troca de cenário.
        List<ItemRemovidoManualmente> itensRemovidosManualmente,
        // true quando existe pelo menos um ajuste manual ativo nesta cotação (move ou
        // remove) — liga o badge "Ordem ajustada manualmente" e o botão "Restaurar
        // Cenário" no frontend.
        boolean temAjustesManuais,
        // Fornecedores desta cotação com pelo menos uma oferta válida, mas
        // status == PENDENTE_DADOS (cadastro incompleto — seção 10.5 da doc
        // técnica). Sempre preenchido, independente do cenário pedido — é o que
        // permite o frontend explicar por que a Compra Equilibrada excluiu esses
        // fornecedores (achado do usuário, 09/08: "Compra Equilibrada" caindo pra
        // R$0,00 sem nenhuma explicação, quando na verdade os fornecedores tinham
        // resposta válida, só faltava completar o cadastro comercial).
        List<FornecedorPendente> fornecedoresComDadosPendentes
) {
    public record FornecedorPendente(UUID fornecedorId, String fornecedorNome) {}

    public record DistribuicaoFornecedor(
            UUID fornecedorId,
            String fornecedorNome,
            List<ItemDistribuicao> itens,
            BigDecimal totalFornecedor,
            BigDecimal pedidoMinimo,
            boolean abaixoDoPedidoMinimo,
            BigDecimal faltaParaMinimo,
            String prazoEntregaPadrao,
            String condicaoPagamentoPadrao,
            // Fornecedor.status == PENDENTE_DADOS (cadastro incompleto, tipicamente
            // criado automaticamente pelo WhatsApp — seção 10.5 da doc técnica). Só
            // pode vir true em MENOR_PRECO/MELHOR_PRAZO (que não filtram por status);
            // em EQUILIBRADA um fornecedor PENDENTE_DADOS nunca aparece aqui — os
            // dados comerciais incompletos tornariam a distribuição pouco confiável
            // (pedido mínimo/prazo podem estar ausentes ou desatualizados), por isso
            // o algoritmo continua excluindo-o dessa conta (ver `fornecedoresComDadosPendentes`
            // pra explicar essa ausência ao operador, em vez de devolver o cenário
            // vazio sem explicação — achado do usuário, 09/08).
            boolean dadosPendentes
    ) {}

    public record ItemDistribuicao(
            UUID cotacaoProdutoId,
            String produtoNome,
            BigDecimal quantidade,
            String unidade,
            BigDecimal precoUnitario,
            BigDecimal subtotal,
            // Preço unitário desse item, nesse fornecedor, é o menor entre todas as
            // ofertas válidas pra esse item (não só entre os fornecedores escalados) —
            // alimenta o badge "★ Menor" da tabela do Mapa de Compra.
            boolean menorPreco,
            // Este item está aqui por causa de um ajuste manual (mover), não porque o
            // algoritmo do cenário escolheu este fornecedor — alimenta o hint "✎".
            boolean ajustadoManualmente
    ) {}

    public record ProdutoSemFornecedor(UUID cotacaoProdutoId, String produtoNome,
                                        BigDecimal quantidade, String unidade) {}

    public record ItemRemovidoManualmente(UUID cotacaoProdutoId, String produtoNome,
                                           BigDecimal quantidade, String unidade) {}
}
