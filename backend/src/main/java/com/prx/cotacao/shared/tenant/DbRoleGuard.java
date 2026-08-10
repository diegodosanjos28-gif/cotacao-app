package com.prx.cotacao.shared.tenant;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Recusa subir a aplicação se a conexão de runtime (a que o Hikari/JPA usa para
 * todas as queries de negócio) for um role Postgres superuser ou BYPASSRLS.
 *
 * Motivação: um role assim ignora TODAS as policies de V10__rls_policies.sql de
 * forma silenciosa e incondicional — não há erro, só um isolamento multi-tenant que
 * nunca é aplicado. Isso já aconteceu neste projeto (achado de revisão de
 * segurança em 2026-07-09: todo ambiente conectava como o role admin/superuser, não
 * como cotacao_app) e não emitia nenhum sinal de alerta. Este check transforma essa
 * suposição em um invariante verificado no boot, não em um comentário de migration.
 *
 * Implementado como SmartInitializingSingleton, não ApplicationRunner: o Tomcat
 * embutido do Spring Boot só abre os connectors (passa a aceitar conexões TCP de
 * verdade) em finishRefresh(), mas ApplicationRunner só roda DEPOIS que refresh()
 * inteiro termina — ou seja, existiria uma janela real em que o servidor já está
 * aceitando requisições HTTP de negócio antes deste guard ter checado o role.
 * afterSingletonsInstantiated() roda no fim de finishBeanFactoryInitialization(),
 * que é anterior a finishRefresh(): lançar aqui aborta o contexto antes da porta
 * abrir, fechando essa janela por completo (achado de security-reviewer).
 */
@Component
public class DbRoleGuard implements SmartInitializingSingleton {

    private final JdbcTemplate jdbcTemplate;

    public DbRoleGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Boolean bypassaRls = jdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class);

        if (Boolean.TRUE.equals(bypassaRls)) {
            String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
            throw new IllegalStateException(
                    "A conexão de runtime da aplicação (spring.datasource.*) está usando o role '"
                    + currentUser + "', que é superuser ou tem BYPASSRLS. Isso desativa TODAS as "
                    + "policies de RLS de V10__rls_policies.sql de forma silenciosa. Configure "
                    + "APP_DB_USER/APP_DB_PASSWORD para conectar como um role de runtime sem esse "
                    + "privilégio (ex: cotacao_app) — reserve o role atual só para "
                    + "spring.flyway.user/password (migrations).");
        }
    }
}
