package com.prx.cotacao.cotacao.core.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cotacao_produto")
public class CotacaoProduto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cotacao_id", nullable = false)
    private UUID cotacaoId;

    @Column(name = "produto_id")
    private UUID produtoId;

    @Column(name = "texto_original", nullable = false)
    private String textoOriginal;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantidade;

    @Column(nullable = false, length = 10)
    private String unidade;

    @Column(nullable = false)
    private Integer ordem;

    // Soft delete (Prompt 12) — nunca apagado fisicamente. IS NULL/IS NOT NULL faz o
    // papel do flag; espelha o padrão já usado em Cotacao.finalizadaEm.
    @Column(name = "removido_em")
    private OffsetDateTime removidoEm;

    @Column(name = "removido_por")
    private UUID removidoPor;

    public UUID getId() { return id; }
    public UUID getCotacaoId() { return cotacaoId; }
    public void setCotacaoId(UUID cotacaoId) { this.cotacaoId = cotacaoId; }
    public UUID getProdutoId() { return produtoId; }
    public void setProdutoId(UUID produtoId) { this.produtoId = produtoId; }
    public String getTextoOriginal() { return textoOriginal; }
    public void setTextoOriginal(String textoOriginal) { this.textoOriginal = textoOriginal; }
    public BigDecimal getQuantidade() { return quantidade; }
    public void setQuantidade(BigDecimal quantidade) { this.quantidade = quantidade; }
    public String getUnidade() { return unidade; }
    public void setUnidade(String unidade) { this.unidade = unidade; }
    public Integer getOrdem() { return ordem; }
    public void setOrdem(Integer ordem) { this.ordem = ordem; }
    public OffsetDateTime getRemovidoEm() { return removidoEm; }
    public void setRemovidoEm(OffsetDateTime removidoEm) { this.removidoEm = removidoEm; }
    public UUID getRemovidoPor() { return removidoPor; }
    public void setRemovidoPor(UUID removidoPor) { this.removidoPor = removidoPor; }
}
