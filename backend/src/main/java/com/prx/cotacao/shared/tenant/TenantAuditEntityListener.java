package com.prx.cotacao.shared.tenant;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Seta automaticamente tenantId (do TenantContext), criadoEm e atualizadoEm.
 * Dispensa setar tenant manualmente em cada service.
 */
public class TenantAuditEntityListener {

    @PrePersist
    void prePersist(TenantAuditEntity entity) {
        if (entity.getTenantId() == null) {
            String tenantId = TenantContext.get();
            if (tenantId != null && !tenantId.isBlank()) {
                entity.setTenantId(UUID.fromString(tenantId));
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (entity.getCriadoEm() == null) {
            entity.setCriadoEm(now);
        }
        entity.setAtualizadoEm(now);
    }

    @PreUpdate
    void preUpdate(TenantAuditEntity entity) {
        entity.setAtualizadoEm(OffsetDateTime.now());
    }
}
