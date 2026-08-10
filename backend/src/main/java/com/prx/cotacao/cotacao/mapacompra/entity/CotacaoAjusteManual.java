package com.prx.cotacao.cotacao.mapacompra.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "cotacao_ajuste_manual")
public class CotacaoAjusteManual extends TenantAuditEntity {

    @Column(name = "cotacao_id", nullable = false)
    private UUID cotacaoId;

    @Column(name = "cotacao_produto_id", nullable = false)
    private UUID cotacaoProdutoId;

    // Nulo = item removido da ordem (não vai pra nenhum fornecedor).
    @Column(name = "fornecedor_id_override")
    private UUID fornecedorIdOverride;

    @Column(name = "criado_por", nullable = false)
    private UUID criadoPor;

    public UUID getCotacaoId() { return cotacaoId; }
    public void setCotacaoId(UUID cotacaoId) { this.cotacaoId = cotacaoId; }
    public UUID getCotacaoProdutoId() { return cotacaoProdutoId; }
    public void setCotacaoProdutoId(UUID cotacaoProdutoId) { this.cotacaoProdutoId = cotacaoProdutoId; }
    public UUID getFornecedorIdOverride() { return fornecedorIdOverride; }
    public void setFornecedorIdOverride(UUID fornecedorIdOverride) { this.fornecedorIdOverride = fornecedorIdOverride; }
    public UUID getCriadoPor() { return criadoPor; }
    public void setCriadoPor(UUID criadoPor) { this.criadoPor = criadoPor; }
}
