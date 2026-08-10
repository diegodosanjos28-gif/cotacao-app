package com.prx.cotacao.shared.tenant;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Superclasse de todas as entidades com isolamento por tenant.
 *
 * - @FilterDef registra o filtro Hibernate no SessionFactory.
 * - @Filter adiciona "tenant_id = :tenantId" em todas as queries das subclasses.
 * - @EntityListeners(TenantAuditEntityListener) seta tenantId/timestamps automaticamente.
 *
 * Entidades SEM tenant_id (Tenant, RefreshToken, CotacaoProduto, etc.) não estendem esta classe.
 */
@MappedSuperclass
@FilterDef(
    name = "tenantFilter",
    parameters = @ParamDef(name = "tenantId", type = UUID.class)
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@EntityListeners(TenantAuditEntityListener.class)
public abstract class TenantAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm;

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public OffsetDateTime getCriadoEm() { return criadoEm; }
    public void setCriadoEm(OffsetDateTime criadoEm) { this.criadoEm = criadoEm; }

    public OffsetDateTime getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(OffsetDateTime atualizadoEm) { this.atualizadoEm = atualizadoEm; }
}
