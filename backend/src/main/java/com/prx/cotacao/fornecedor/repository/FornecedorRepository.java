package com.prx.cotacao.fornecedor.repository;

import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FornecedorRepository extends JpaRepository<Fornecedor, UUID> {

    // Hibernate filter já aplica tenant_id — não precisamos de findByTenantId
    List<Fornecedor> findByStatusNot(FornecedorStatus status);

    // Nome único só entre fornecedores não-inativos (soft-delete libera o nome pra
    // reuso — ver FornecedorService.inativar).
    boolean existsByNomeIgnoreCaseAndStatusNot(String nome, FornecedorStatus status);

    boolean existsByNomeIgnoreCaseAndStatusNotAndIdNot(String nome, FornecedorStatus status, UUID id);
}
