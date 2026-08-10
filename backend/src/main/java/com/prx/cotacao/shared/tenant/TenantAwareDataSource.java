package com.prx.cotacao.shared.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Wrapper do DataSource que injeta app.current_tenant_id e app.is_admin em cada
 * conexão extraída do pool via set_config() (PreparedStatement — sem concatenação
 * de string para evitar SQL injection mesmo que o JWT_SECRET seja comprometido).
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        applyTenant(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = super.getConnection(username, password);
        applyTenant(connection);
        return connection;
    }

    private void applyTenant(Connection connection) throws SQLException {
        String tenantId = TenantContext.get();
        String isAdmin = TenantContext.isAdmin() ? "true" : "false";

        // set_config(key, value, is_local): is_local=false → session scope, sobrevive ao commit.
        // Usar PreparedStatement elimina risco de injection mesmo com tenantId controlado por JWT assinado.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('app.current_tenant_id', ?, false), set_config('app.is_admin', ?, false)")) {
            ps.setString(1, tenantId != null ? tenantId : "");
            ps.setString(2, isAdmin);
            ps.execute();
        }
    }
}
