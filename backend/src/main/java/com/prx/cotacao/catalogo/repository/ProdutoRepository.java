package com.prx.cotacao.catalogo.repository;

import com.prx.cotacao.catalogo.entity.Produto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {

    // Hibernate filter já aplica tenant_id — findAll(Pageable) retorna apenas itens do
    // tenant atual. findAllById (herdado) usado para resolução de nome por IDs, sem
    // paginação — bounded pelo tamanho da lista de uma cotação, não pelo catálogo todo.
    //
    // Native (não JPQL): precisa do operador ILIKE do Postgres puro, sem LOWER() em
    // volta, pra bater com o índice GIN de trigrama (idx_produto_nome_trgm, V32) — um
    // LOWER(p.nome) LIKE LOWER(...) em JPQL não usaria esse índice, forçando sequential
    // scan do catálogo inteiro do tenant a cada busca do autocomplete (achado 08-20:
    // esse método passou a rodar a cada tecla digitada, debounced). ORDER BY nome
    // hardcoded porque native query do Spring Data NÃO aplica o Sort de Pageable
    // automaticamente (só LIMIT/OFFSET) — HistoricoPrecoService depende de ordem
    // alfabética estável aqui.
    @Query(value = "SELECT * FROM produto WHERE nome ILIKE CONCAT('%', :busca, '%') ORDER BY nome ASC",
           countQuery = "SELECT COUNT(*) FROM produto WHERE nome ILIKE CONCAT('%', :busca, '%')",
           nativeQuery = true)
    Page<Produto> buscarPorNome(@Param("busca") String busca, Pageable pageable);
}
