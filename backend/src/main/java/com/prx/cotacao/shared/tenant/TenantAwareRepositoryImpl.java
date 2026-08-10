package com.prx.cotacao.shared.tenant;

import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Base repository que sobrescreve findById() com JPQL para que o Hibernate Filter
 * de tenant seja aplicado corretamente.
 *
 * Motivação: EntityManager.find() (usado por SimpleJpaRepository.findById()) NÃO
 * aplica Hibernate Filters — vai diretamente ao cache L1/banco por PK, sem WHERE.
 * Queries JPQL SÃO filtradas. Portanto, para entidades que estendem TenantAuditEntity,
 * findById() precisa usar JPQL em vez de em.find().
 */
public class TenantAwareRepositoryImpl<T, ID> extends SimpleJpaRepository<T, ID> {

    private final JpaEntityInformation<T, ?> entityInfo;
    private final EntityManager em;

    public TenantAwareRepositoryImpl(JpaEntityInformation<T, ?> entityInformation,
                                     EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityInfo = entityInformation;
        this.em = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<T> findById(ID id) {
        Class<T> type = entityInfo.getJavaType();

        // Apenas entidades com tenant filter precisam do JPQL.
        // Para entidades sem tenant (Tenant, RefreshToken, etc.), usa o find() padrão.
        if (!TenantAuditEntity.class.isAssignableFrom(type)) {
            return super.findById(id);
        }

        // JPQL → Hibernate Filter é aplicado → tenant-safe
        List<T> result = em.createQuery(
                "SELECT e FROM " + entityInfo.getEntityName() + " e WHERE e.id = :id", type)
                .setParameter("id", id)
                .getResultList();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
