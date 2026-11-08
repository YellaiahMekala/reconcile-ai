package com.reconcileai.infrastructure.persistence;

import com.reconcileai.infrastructure.security.TenantScope;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Binds every pooled connection to the caller's tenant on checkout:
 * {@code SELECT set_config('app.tenant_id', ?, false)}.
 *
 * <h2>Why on checkout, not per transaction?</h2>
 * Checkout covers everything: JPA transactions, JdbcTemplate calls without a transaction, and Spring AI's
 * PgVectorStore. A pooled connection is always re-bound before reuse, so a value can never leak from one request
 * to the next. Cost: one tiny round trip per checkout.
 *
 * <h2>Why session-level ({@code false}) rather than SET LOCAL?</h2>
 * SET LOCAL only lives until the end of the current transaction, and autocommit JDBC calls have no transaction.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private static Connection bind(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, TenantScope.current());
            ps.execute();
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }
}
