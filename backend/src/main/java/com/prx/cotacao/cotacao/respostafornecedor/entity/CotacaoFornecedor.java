package com.prx.cotacao.cotacao.respostafornecedor.entity;

import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "cotacao_fornecedor")
public class CotacaoFornecedor extends TenantAuditEntity {

    @Column(name = "cotacao_id", nullable = false)
    private UUID cotacaoId;

    @Column(name = "fornecedor_id", nullable = false)
    private UUID fornecedorId;

    @Column(nullable = false)
    private Integer ordem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CotacaoFornecedorStatus status = CotacaoFornecedorStatus.PENDENTE;

    // Texto bruto da última resposta de fornecedor ainda não confirmada (Prompt 15) —
    // grava a cada preview gerado (RespostaFornecedorCoreService.processar), limpo ao
    // confirmar ou cancelar. Permite reabrir a Conferência (GET .../resposta-persistida)
    // sem depender de linhas já persistidas em cotacao_produto_fornecedor, que só
    // existem depois de POST .../confirmar.
    @Column(name = "texto_resposta_pendente", columnDefinition = "TEXT")
    private String textoRespostaPendente;

    public UUID getCotacaoId() { return cotacaoId; }
    public void setCotacaoId(UUID cotacaoId) { this.cotacaoId = cotacaoId; }
    public UUID getFornecedorId() { return fornecedorId; }
    public void setFornecedorId(UUID fornecedorId) { this.fornecedorId = fornecedorId; }
    public Integer getOrdem() { return ordem; }
    public void setOrdem(Integer ordem) { this.ordem = ordem; }
    public CotacaoFornecedorStatus getStatus() { return status; }
    public void setStatus(CotacaoFornecedorStatus status) { this.status = status; }
    public String getTextoRespostaPendente() { return textoRespostaPendente; }
    public void setTextoRespostaPendente(String textoRespostaPendente) { this.textoRespostaPendente = textoRespostaPendente; }
}
