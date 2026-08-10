package com.prx.cotacao.cotacao.respostafornecedor.entity;

import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cotacao_produto_fornecedor")
public class CotacaoProdutoFornecedor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cotacao_produto_id", nullable = false)
    private UUID cotacaoProdutoId;

    @Column(name = "fornecedor_id", nullable = false)
    private UUID fornecedorId;

    @Column(name = "texto_original", columnDefinition = "TEXT")
    private String textoOriginal;

    @Column(name = "preco_informado", nullable = false, precision = 12, scale = 2)
    private BigDecimal precoInformado;

    @Column(name = "preco_unitario_calculado", precision = 12, scale = 2)
    private BigDecimal precoUnitarioCalculado;

    // Snapshot imutável por linha de cotação — regra 2 do CLAUDE.md.
    // A imutabilidade é garantida por AvisoService.resolver() que lança exceção se o campo já não é nulo.
    // updatable=false aqui quebraria o SET inicial (CPF é criado sem o valor, que só é gravado no resolver).
    @Column(name = "embalagem_qtd_confirmada")
    private Integer embalagemQtdConfirmada;

    @Column(name = "tipo_embalagem_detectado")
    private String tipoEmbalagemDetectado;

    @Column(name = "sem_estoque", nullable = false)
    private boolean semEstoque = false;

    @Column(name = "confianca_match", precision = 3, scale = 2)
    private BigDecimal confiancaMatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusItem status = StatusItem.OK;

    @Column(name = "resolvido_por")
    private UUID resolvidoPor;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    // Lock otimista — protege o snapshot de embalagem (regra 2 do CLAUDE.md) contra
    // duas chamadas concorrentes de AvisoService.resolver() pro mesmo cpfId lendo
    // embalagemQtdConfirmada=null e ambas passarem no check-then-act. Ver
    // GlobalExceptionHandler.handleOptimisticLocking.
    @Version
    @Column(name = "versao", nullable = false)
    private Long versao;

    public UUID getId() { return id; }
    public UUID getCotacaoProdutoId() { return cotacaoProdutoId; }
    public void setCotacaoProdutoId(UUID cotacaoProdutoId) { this.cotacaoProdutoId = cotacaoProdutoId; }
    public UUID getFornecedorId() { return fornecedorId; }
    public void setFornecedorId(UUID fornecedorId) { this.fornecedorId = fornecedorId; }
    public String getTextoOriginal() { return textoOriginal; }
    public void setTextoOriginal(String textoOriginal) { this.textoOriginal = textoOriginal; }
    public BigDecimal getPrecoInformado() { return precoInformado; }
    public void setPrecoInformado(BigDecimal precoInformado) { this.precoInformado = precoInformado; }
    public BigDecimal getPrecoUnitarioCalculado() { return precoUnitarioCalculado; }
    public void setPrecoUnitarioCalculado(BigDecimal precoUnitarioCalculado) { this.precoUnitarioCalculado = precoUnitarioCalculado; }
    public Integer getEmbalagemQtdConfirmada() { return embalagemQtdConfirmada; }
    public void setEmbalagemQtdConfirmada(Integer embalagemQtdConfirmada) { this.embalagemQtdConfirmada = embalagemQtdConfirmada; }
    public String getTipoEmbalagemDetectado() { return tipoEmbalagemDetectado; }
    public void setTipoEmbalagemDetectado(String tipoEmbalagemDetectado) { this.tipoEmbalagemDetectado = tipoEmbalagemDetectado; }
    public boolean isSemEstoque() { return semEstoque; }
    public void setSemEstoque(boolean semEstoque) { this.semEstoque = semEstoque; }
    public BigDecimal getConfiancaMatch() { return confiancaMatch; }
    public void setConfiancaMatch(BigDecimal confiancaMatch) { this.confiancaMatch = confiancaMatch; }
    public StatusItem getStatus() { return status; }
    public void setStatus(StatusItem status) { this.status = status; }
    public UUID getResolvidoPor() { return resolvidoPor; }
    public void setResolvidoPor(UUID resolvidoPor) { this.resolvidoPor = resolvidoPor; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
    public Long getVersao() { return versao; }
}
