package com.prx.cotacao.identidade.repository;

import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.Papel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    @Query("SELECT u FROM Usuario u WHERE u.email = :email AND u.ativo = true")
    Optional<Usuario> findByEmailAndAtivo(@Param("email") String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    List<Usuario> findByTenantIdOrderByCriadoEmDesc(UUID tenantId);

    List<Usuario> findByPapelOrderByCriadoEmDesc(Papel papel);

    long countByPapelAndAtivo(Papel papel, boolean ativo);

    boolean existsByPapel(Papel papel);
}
