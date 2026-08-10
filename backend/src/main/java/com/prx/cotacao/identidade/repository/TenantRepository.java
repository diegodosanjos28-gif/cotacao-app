package com.prx.cotacao.identidade.repository;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.enums.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    // `q` nunca é bindado como null (TenantService.listar passa "" quando não há
    // busca) — um `String` null dentro de CONCAT/LOWER faz o Hibernate/driver
    // Postgres não conseguir inferir o tipo do parâmetro e enviar como `bytea`
    // ("function lower(bytea) does not exist"), em vez de text.
    @Query("""
            SELECT t FROM Tenant t
            WHERE (:q = '' OR LOWER(t.nomeFantasia) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.nomeFantasia
            """)
    List<Tenant> buscar(@Param("q") String q, @Param("status") TenantStatus status);

    boolean existsByCnpj(String cnpj);

    boolean existsByCnpjAndIdNot(String cnpj, UUID id);
}
