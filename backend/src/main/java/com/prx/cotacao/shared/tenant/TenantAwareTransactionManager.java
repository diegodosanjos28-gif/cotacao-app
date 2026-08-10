package com.prx.cotacao.shared.tenant;

import jakarta.persistence.EntityManager;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;

/**
 * Habilita o Hibernate Filter "tenantFilter" no início de cada transação.
 * Por rodar dentro de doBegin() — após o EntityManager ser vinculado ao thread —
 * cobre TODAS as queries da transação: findById, findAll, @Query customizados e
 * queries derivadas de nome de método.
 *
 * Admin PRX (TenantContext.isAdmin() == true) não recebe o filtro;
 * o isolamento dele é garantido pelo RLS Postgres via is_admin_request().
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareTransactionManager.class);
    private static final String FILTER_NAME = "tenantFilter";
    private static final String FILTER_PARAM = "tenantId";

    public TenantAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        if (TenantContext.isAdmin()) return;

        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) return;

        EntityManagerHolder emHolder = (EntityManagerHolder)
                TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());

        if (emHolder == null || emHolder.getEntityManager() == null) return;

        try {
            EntityManager em = emHolder.getEntityManager();
            Session session = em.unwrap(Session.class);

            Filter existing = session.getEnabledFilter(FILTER_NAME);
            if (existing == null) {
                session.enableFilter(FILTER_NAME)
                       .setParameter(FILTER_PARAM, UUID.fromString(tenantId));
            }
        } catch (Exception e) {
            log.warn("Não foi possível habilitar o filtro Hibernate de tenant: {}", e.getMessage());
        }
    }
}
