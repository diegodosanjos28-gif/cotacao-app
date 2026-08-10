package com.prx.cotacao.identidade.auth.dto;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    private UUID jti;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(nullable = false)
    private boolean usado = false;

    @Column(name = "expira_em", nullable = false)
    private OffsetDateTime expiraEm;

    // Tenant que um ADMIN_PRX escolheu navegar via POST /auth/selecionar-tenant no
    // momento em que este refresh token foi emitido (NULL para OPERADOR_CLIENTE só
    // por coincidência de shape — o tenant dele é fixo e vem de usuario.tenant_id;
    // aqui é preenchido igual, mas a fonte de verdade em runtime é sempre esta coluna,
    // não usuario.tenant_id — ver AuthService.issueTokens/refresh). NULL também para
    // ADMIN_PRX no painel admin puro (nenhum tenant escolhido ainda).
    @Column(name = "tenant_id_impersonado")
    private UUID tenantIdImpersonado;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    public RefreshToken() {}

    public RefreshToken(UUID jti, UUID usuarioId, OffsetDateTime expiraEm, UUID tenantIdImpersonado) {
        this.jti = jti;
        this.usuarioId = usuarioId;
        this.expiraEm = expiraEm;
        this.tenantIdImpersonado = tenantIdImpersonado;
    }

    public UUID getJti() { return jti; }
    public UUID getUsuarioId() { return usuarioId; }
    public boolean isUsado() { return usado; }
    public void setUsado(boolean usado) { this.usado = usado; }
    public OffsetDateTime getExpiraEm() { return expiraEm; }
    public UUID getTenantIdImpersonado() { return tenantIdImpersonado; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
}
