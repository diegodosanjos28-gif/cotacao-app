package com.prx.cotacao.catalogo.repository;

import com.prx.cotacao.catalogo.entity.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {

    // Hibernate filter já aplica tenant_id — findAll() retorna apenas itens do tenant atual
    @Query("SELECT p FROM Produto p WHERE LOWER(p.nome) LIKE LOWER(CONCAT('%', :busca, '%'))")
    List<Produto> buscarPorNome(@Param("busca") String busca);
}
