package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado da conciliação de uma linha de resposta de fornecedor contra os itens base da
 * cotação — saída de {@link ConciliacaoRespostaService#conciliarEnhanced} e insumo de
 * {@link ConciliacaoRespostaService#agrupar}. Espelha o objeto espalhado (spread) que
 * conciliarEnhanced/_srAgrupar produzem no protótipo (item original + campos calculados).
 *
 * <p>É uma classe mutável (não record) de propósito: o algoritmo de redistribuição
 * {@code _srAgrupar} do protótipo reatribui {@code produtoId} em memória durante as
 * passagens de estabilização (até 5 rodadas) e ao demover candidatos divergentes de
 * volume para o pool "extra" — mutação é o comportamento original, não um desvio de design.</p>
 */
public final class ItemConciliado {

    /** Status possíveis — espelham os literais de string usados no protótipo. */
    public static final String STATUS_CONFIRMADO = "confirmado";
    public static final String STATUS_PROVAVEL = "provavel";
    public static final String STATUS_NAO_IDENTIFICADO = "nao_identificado";
    public static final String STATUS_SEM_ESTOQUE = "sem_estoque";

    /** Tipos de correspondência — espelham os literais de string usados no protótipo. */
    public static final String TIPO_EXATO = "exato";
    public static final String TIPO_SIMILAR = "similar";
    public static final String TIPO_MARCA_ALTERNATIVA = "marca_alternativa";
    public static final String TIPO_VOLUME_DIFERENTE = "volume_diferente";
    public static final String TIPO_PRECO_EMBALAGEM = "preco_embalagem";
    public static final String TIPO_PRECO_AMBIGUO = "preco_ambiguo";
    public static final String TIPO_SEM_ESTOQUE = "sem_estoque";
    public static final String TIPO_NAO_IDENTIFICADO = "nao_identificado";

    private final LinhaFornecedor linhaOriginal;
    private final String descricao;

    private UUID produtoId;
    private String produtoNome;
    private BigDecimal confianca;
    private String status;
    private String tipoCorrespondencia;
    private String marcaSolicitada;
    private DivergenciaVolume divergenciaVolume;

    public ItemConciliado(LinhaFornecedor linhaOriginal, String descricao) {
        this.linhaOriginal = linhaOriginal;
        this.descricao = descricao;
    }

    public LinhaFornecedor linhaOriginal() {
        return linhaOriginal;
    }

    /** Texto usado como base de comparação (equivalente a item.descricao no protótipo). */
    public String descricao() {
        return descricao;
    }

    public String textoOriginal() {
        return linhaOriginal.textoOriginal();
    }

    public BigDecimal preco() {
        return linhaOriginal.preco();
    }

    public boolean semEstoque() {
        return linhaOriginal.semEstoque();
    }

    public UUID produtoId() {
        return produtoId;
    }

    public void setProdutoId(UUID produtoId) {
        this.produtoId = produtoId;
    }

    public String produtoNome() {
        return produtoNome;
    }

    public void setProdutoNome(String produtoNome) {
        this.produtoNome = produtoNome;
    }

    public BigDecimal confianca() {
        return confianca;
    }

    public void setConfianca(BigDecimal confianca) {
        this.confianca = confianca;
    }

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String tipoCorrespondencia() {
        return tipoCorrespondencia;
    }

    public void setTipoCorrespondencia(String tipoCorrespondencia) {
        this.tipoCorrespondencia = tipoCorrespondencia;
    }

    public String marcaSolicitada() {
        return marcaSolicitada;
    }

    public void setMarcaSolicitada(String marcaSolicitada) {
        this.marcaSolicitada = marcaSolicitada;
    }

    public DivergenciaVolume divergenciaVolume() {
        return divergenciaVolume;
    }

    public void setDivergenciaVolume(DivergenciaVolume divergenciaVolume) {
        this.divergenciaVolume = divergenciaVolume;
    }
}
