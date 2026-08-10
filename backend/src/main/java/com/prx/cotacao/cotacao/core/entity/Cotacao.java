package com.prx.cotacao.cotacao.core.entity;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cotacao")
public class Cotacao extends TenantAuditEntity {

    @Column(name = "criado_por")
    private UUID criadoPor;

    @Column(nullable = false)
    private String titulo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CotacaoStatus status = CotacaoStatus.RASCUNHO;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal_origem", nullable = false)
    private CanalOrigem canalOrigem = CanalOrigem.WEB;

    @Column(name = "ultima_atividade_em")
    private OffsetDateTime ultimaAtividadeEm;

    // Sinaliza se o usuário já revisou a lista recebida via WhatsApp (tela "Ajuste
    // de Lista", Fase 3) — cotações web nunca passam por essa tela, nascem TRUE.
    @Column(name = "lista_revisada", nullable = false)
    private boolean listaRevisada = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "cenario_selecionado")
    private CenarioSelecionado cenarioSelecionado;

    @Column(name = "finalizada_em")
    private OffsetDateTime finalizadaEm;

    // Snapshot JSON (MapaCompraResponse serializado) do Mapa de Compra no momento da
    // finalização — ver V18. Null pra cotações finalizadas antes dessa migration ou
    // ainda em andamento.
    @Column(name = "mapa_final_snapshot", columnDefinition = "TEXT")
    private String mapaFinalSnapshot;

    public UUID getCriadoPor() { return criadoPor; }
    public void setCriadoPor(UUID criadoPor) { this.criadoPor = criadoPor; }
    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public CotacaoStatus getStatus() { return status; }
    public void setStatus(CotacaoStatus status) { this.status = status; }
    public CanalOrigem getCanalOrigem() { return canalOrigem; }
    public void setCanalOrigem(CanalOrigem canalOrigem) { this.canalOrigem = canalOrigem; }
    public OffsetDateTime getUltimaAtividadeEm() { return ultimaAtividadeEm; }
    public void setUltimaAtividadeEm(OffsetDateTime v) { this.ultimaAtividadeEm = v; }
    public boolean isListaRevisada() { return listaRevisada; }
    public void setListaRevisada(boolean listaRevisada) { this.listaRevisada = listaRevisada; }
    public CenarioSelecionado getCenarioSelecionado() { return cenarioSelecionado; }
    public void setCenarioSelecionado(CenarioSelecionado v) { this.cenarioSelecionado = v; }
    public OffsetDateTime getFinalizadaEm() { return finalizadaEm; }
    public void setFinalizadaEm(OffsetDateTime v) { this.finalizadaEm = v; }
    public String getMapaFinalSnapshot() { return mapaFinalSnapshot; }
    public void setMapaFinalSnapshot(String v) { this.mapaFinalSnapshot = v; }
}
