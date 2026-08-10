package com.prx.cotacao.fornecedor.entity;

import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.enums.OrigemCadastro;
import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "fornecedor")
public class Fornecedor extends TenantAuditEntity {

    @Column(nullable = false)
    private String nome;

    @Column(name = "prazo_entrega_padrao")
    private String prazoEntregaPadrao;

    @Column(name = "condicao_pagamento_padrao")
    private String condicaoPagamentoPadrao;

    @Column(name = "pedido_minimo_padrao", precision = 12, scale = 2)
    private BigDecimal pedidoMinimoPadrao;

    @Column(name = "observacoes_padrao", columnDefinition = "TEXT")
    private String observacoesPadrao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FornecedorStatus status = FornecedorStatus.ATIVO;

    @Enumerated(EnumType.STRING)
    @Column(name = "origem_cadastro", nullable = false)
    private OrigemCadastro origemCadastro = OrigemCadastro.MANUAL;

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getPrazoEntregaPadrao() { return prazoEntregaPadrao; }
    public void setPrazoEntregaPadrao(String v) { this.prazoEntregaPadrao = v; }
    public String getCondicaoPagamentoPadrao() { return condicaoPagamentoPadrao; }
    public void setCondicaoPagamentoPadrao(String v) { this.condicaoPagamentoPadrao = v; }
    public BigDecimal getPedidoMinimoPadrao() { return pedidoMinimoPadrao; }
    public void setPedidoMinimoPadrao(BigDecimal v) { this.pedidoMinimoPadrao = v; }
    public String getObservacoesPadrao() { return observacoesPadrao; }
    public void setObservacoesPadrao(String v) { this.observacoesPadrao = v; }
    public FornecedorStatus getStatus() { return status; }
    public void setStatus(FornecedorStatus status) { this.status = status; }
    public OrigemCadastro getOrigemCadastro() { return origemCadastro; }
    public void setOrigemCadastro(OrigemCadastro v) { this.origemCadastro = v; }
}
