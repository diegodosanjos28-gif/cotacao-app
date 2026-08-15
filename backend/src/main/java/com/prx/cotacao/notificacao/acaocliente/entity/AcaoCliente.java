package com.prx.cotacao.notificacao.acaocliente.entity;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Linha do catálogo global {@code acao_cliente} (ação × resultado). Nome da classe
 * diverge do nome da tabela — única exceção no projeto (toda outra entidade tem o
 * mesmo simple name da tabela) — para não colidir com o enum {@link AcaoClienteEnum}, que é
 * o tipo pervasivo usado fora deste pacote ({@code ContextoNotificacao},
 * {@code WhatsappWebhookService}, {@code CatalogoParametrosNotificacao}...).
 *
 * <p>Não estende {@code TenantAuditEntity}: não há {@code tenant_id}, é catálogo global
 * compartilhado por todos os tenants (mesmo espírito do padrão "marca" citado no
 * comentário de V28), isolamento é só RLS com leitura sempre liberada.</p>
 */
@Entity
@Table(name = "acao_cliente")
public class AcaoCliente {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AcaoClienteEnum acao;

    // nullable — sempre null quando acao == NAO_IDENTIFICADO (1 único registro, sem
    // distinção de sucesso/erro).
    @Enumerated(EnumType.STRING)
    @Column
    private ResultadoAcaoCliente resultado;

    @Column(nullable = false)
    private String descricao;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    public UUID getId() { return id; }

    public AcaoClienteEnum getAcao() { return acao; }
    public void setAcao(AcaoClienteEnum acao) { this.acao = acao; }

    public ResultadoAcaoCliente getResultado() { return resultado; }
    public void setResultado(ResultadoAcaoCliente resultado) { this.resultado = resultado; }

    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }

    public OffsetDateTime getCriadoEm() { return criadoEm; }
}
