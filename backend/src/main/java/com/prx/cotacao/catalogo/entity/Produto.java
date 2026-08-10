package com.prx.cotacao.catalogo.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "produto")
public class Produto extends TenantAuditEntity {

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
