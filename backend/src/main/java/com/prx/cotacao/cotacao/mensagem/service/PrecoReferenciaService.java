package com.prx.cotacao.cotacao.mensagem.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;

/**
 * Cálculo de "preço de referência" (mediana) entre fornecedores para um mesmo item —
 * usado tanto pelo Comparativo (badge agregado entre TODOS os fornecedores já
 * confirmados de uma cotação) quanto pela Conferência (gatilho de suspeita de preço de
 * fardo/caixa dentro da revisão de UM fornecedor, comparado contra os demais já
 * confirmados nesta cotação). Extraído de {@code ComparativoService} (era privado ali)
 * para eliminar duplicação entre os dois usos — pacote de ajustes pós-call, B.3.
 */
@Service
public class PrecoReferenciaService {

    // Preço de fardo/caixa lançado como unitário sempre resulta em preço ALTO, nunca
    // baixo — a comparação é direcional, só dispara para o lado caro (preço muito
    // abaixo da referência normalmente é promoção legítima, não erro de lançamento).
    // Dois gatilhos em OR, compartilhados entre Comparativo e Conferência: relativo
    // (≥1.5x a referência — pega item barato com diferença proporcional grande) OU
    // absoluto (diferença ≥R$10,00 — pega item caro com diferença proporcional
    // pequena mas ainda assim suspeita, ex. R$100 vs R$111 não bate 1.5x mas bate
    // R$10). O critério de R$10 absolutos existia sozinho antes do pacote de ajustes
    // pós-call de 2026-07-29 (que o substituiu por 1.5x); o pedido seguinte do
    // cliente foi reintroduzi-lo como critério adicional, não trocar de volta.
    private static final BigDecimal LIMIAR_DIVERGENCIA_RELATIVA = new BigDecimal("1.5");
    private static final BigDecimal LIMIAR_DIVERGENCIA_ABSOLUTA = new BigDecimal("10.00");

    public BigDecimal calcularMediana(List<BigDecimal> valores) {
        List<BigDecimal> ordenados = new ArrayList<>(valores);
        ordenados.sort(BigDecimal::compareTo);
        int n = ordenados.size();
        if (n % 2 == 1) {
            return ordenados.get(n / 2);
        }
        BigDecimal soma = ordenados.get(n / 2 - 1).add(ordenados.get(n / 2));
        return soma.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    /**
     * Mediana dos preços dos fornecedores != fornecedorIdExcluido para o mesmo item
     * (cotacaoProdutoId), excluindo semEstoque (não representa oferta real). Retorna
     * null se não houver nenhuma referência disponível (ex.: só este fornecedor
     * confirmado até agora, ou todos os demais sem estoque).
     *
     * <p>Usa {@code precoUnitarioCalculado}, não {@code precoInformado}: um fornecedor
     * de referência que já teve sua suspeita de preço de fardo/caixa resolvida (
     * "Unid./embalagem" informado) tem {@code precoInformado} preservado como o
     * snapshot bruto do texto original (ex.: R$13,00 pela caixa), mas
     * {@code precoUnitarioCalculado} é o valor já corrigido (R$13,00 ÷ 12). Usar o
     * bruto como referência inflaria a mediana e mascararia divergências reais de
     * fornecedores seguintes — o mesmo bug corrigido em
     * {@code ComparativoService.temDivergenciaComparativa}.</p>
     */
    public BigDecimal referenciaParaItem(UUID cotacaoProdutoId, UUID fornecedorIdExcluido,
                                          List<CotacaoProdutoFornecedor> todasRespostasDaCotacao) {
        List<BigDecimal> outros = todasRespostasDaCotacao.stream()
                .filter(r -> r.getCotacaoProdutoId().equals(cotacaoProdutoId))
                .filter(r -> !r.getFornecedorId().equals(fornecedorIdExcluido))
                .filter(r -> !r.isSemEstoque())
                .map(CotacaoProdutoFornecedor::getPrecoUnitarioCalculado)
                .toList();
        return outros.isEmpty() ? null : calcularMediana(outros);
    }

    /**
     * Preço ≥1.5x a referência (mediana) OU ≥R$10,00 acima dela — direcional, só
     * dispara para o lado caro.
     */
    public boolean divergeDaReferencia(BigDecimal preco, BigDecimal referencia) {
        if (preco == null || referencia == null) {
            return false;
        }
        boolean divergeRelativo = preco.compareTo(referencia.multiply(LIMIAR_DIVERGENCIA_RELATIVA)) >= 0;
        boolean divergeAbsoluto = preco.subtract(referencia).compareTo(LIMIAR_DIVERGENCIA_ABSOLUTA) >= 0;
        return divergeRelativo || divergeAbsoluto;
    }
}
