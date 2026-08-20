package com.prx.cotacao.catalogo.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "produto")
public class Produto extends TenantAuditEntity {

    // Busca por substring (ProdutoRepository.buscarPorNome) é servida por
    // idx_produto_nome_trgm (GIN, pg_trgm — V32), não pelo btree padrão de `nome`
    // (idx_produto_nome, V4, cobre só a listagem ordenada/prefixo). Ver comentário do
    // método no repository pra detalhe de por que precisa de ILIKE puro, sem LOWER().
    // Schema é 100% Flyway (sem ddl-auto) — não há @Table(indexes=...) aqui de
    // propósito, ficaria inerte e divergiria da fonte de verdade real.
    @Column(nullable = false)
    private String nome;

    @Column
    private String marca;

    @Column(name = "peso_volume_valor", precision = 10, scale = 3)
    private BigDecimal pesoVolumeValor;

    @Column(name = "peso_volume_unidade", length = 5)
    private String pesoVolumeUnidade;

    @Column(name = "unidade_padrao", length = 10)
    private String unidadePadrao;

    @Column(name = "embalagem_qtd_sugerida")
    private Integer embalagemQtdSugerida;

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getMarca() { return marca; }
    public void setMarca(String marca) { this.marca = marca; }
    public BigDecimal getPesoVolumeValor() { return pesoVolumeValor; }
    public void setPesoVolumeValor(BigDecimal v) { this.pesoVolumeValor = v; }
    public String getPesoVolumeUnidade() { return pesoVolumeUnidade; }
    public void setPesoVolumeUnidade(String v) { this.pesoVolumeUnidade = v; }
    public String getUnidadePadrao() { return unidadePadrao; }
    public void setUnidadePadrao(String v) { this.unidadePadrao = v; }
    public Integer getEmbalagemQtdSugerida() { return embalagemQtdSugerida; }
    public void setEmbalagemQtdSugerida(Integer v) { this.embalagemQtdSugerida = v; }
}
