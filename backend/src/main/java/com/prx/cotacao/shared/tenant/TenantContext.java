package com.prx.cotacao.shared.tenant;

/**
 * Mantém tenant_id e isAdmin do request atual em ThreadLocal.
 * Limpado pelo TenantFilter ao final de cada request.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IS_ADMIN = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String tenantId) { CURRENT_TENANT.set(tenantId); }
    public static String get() { return CURRENT_TENANT.get(); }

    public static void setAdmin(boolean isAdmin) { IS_ADMIN.set(isAdmin); }
    public static boolean isAdmin() { return Boolean.TRUE.equals(IS_ADMIN.get()); }

    public static void clear() {
        CURRENT_TENANT.remove();
        IS_ADMIN.remove();
    }
}
