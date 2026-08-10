package com.prx.cotacao.shared.security;

import com.prx.cotacao.shared.tenant.TenantDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Acesso conveniente ao usuário e tenant do request atual via SecurityContext.
 */
@Component
public class CurrentUser {

    public UUID usuarioId() {
        return UUID.fromString(authentication().getName());
    }

    public UUID tenantId() {
        TenantDetails details = tenantDetails();
        if (details.tenantId() == null) return null;
        return UUID.fromString(details.tenantId());
    }

    public boolean isAdmin() {
        TenantDetails details = tenantDetails();
        return "ADMIN_PRX".equals(details.papel());
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private TenantDetails tenantDetails() {
        Object details = authentication().getDetails();
        if (!(details instanceof TenantDetails td)) {
            throw new IllegalStateException("TenantDetails ausente no SecurityContext");
        }
        return td;
    }
}
