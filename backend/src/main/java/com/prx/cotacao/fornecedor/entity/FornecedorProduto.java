package com.prx.cotacao.fornecedor.entity;

import com.prx.cotacao.fornecedor.enums.FornecedorProdutoOrigem;
import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fornecedor_produto")
public class FornecedorProduto extends TenantAuditEntity {

    @Column(name = "fornecedor_id", nullable = false)
    private UUID fornecedorId;

    @Column(name = "produto_id", nullable = false)
    private UUID produtoId;

    @Column(name = "preco_referencia", precision = 12, scale = 2)
    private BigDecimal precoReferencia;

    @Column(name = "embalagem_qtd")
    private Integer embalagemQtd;

    @Enumerated(EnumType.STRING)
    @Column(name = "origem", nullable = false)
    private FornecedorProdutoOrigem origem = FornecedorProdutoOrigem.MANUAL;

    public UUID getFornecedorId() { return fornecedorId; }
    public void setFornecedorId(UUID v) { this.fornecedorId = v; }
    public UUID getProdutoId() { return produtoId; }
    public void setProdutoId(UUID v) { this.produtoId = v; }
    public BigDecimal getPrecoReferencia() { return precoReferencia; }
    public void setPrecoReferencia(BigDecimal v) { this.precoReferencia = v; }
    public Integer getEmbalagemQtd() { return embalagemQtd; }
    public void setEmbalagemQtd(Integer v) { this.embalagemQtd = v; }
    public FornecedorProdutoOrigem getOrigem() { return origem; }
    public void setOrigem(FornecedorProdutoOrigem v) { this.origem = v; }
}
