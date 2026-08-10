package com.prx.cotacao.cotacao.respostafornecedor.repository;

import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoProdutoFornecedorRepository extends JpaRepository<CotacaoProdutoFornecedor, UUID> {

    List<CotacaoProdutoFornecedor> findByCotacaoProdutoId(UUID cotacaoProdutoId);

    @Query("SELECT cpf FROM CotacaoProdutoFornecedor cpf WHERE cpf.cotacaoProdutoId = :cpId AND cpf.fornecedorId = :fornId")
    Optional<CotacaoProdutoFornecedor> findByCotacaoProdutoIdAndFornecedorId(
            @Param("cpId") UUID cotacaoProdutoId, @Param("fornId") UUID fornecedorId);

    @Query("""
            SELECT cpf FROM CotacaoProdutoFornecedor cpf
            JOIN CotacaoProduto cp ON cp.id = cpf.cotacaoProdutoId
            WHERE cp.cotacaoId = :cotacaoId AND cpf.fornecedorId = :fornecedorId
            """)
    List<CotacaoProdutoFornecedor> findByCotacaoIdAndFornecedorId(
            @Param("cotacaoId") UUID cotacaoId, @Param("fornecedorId") UUID fornecedorId);

    // Conta quantos fornecedores distintos confirmaram a mesma embalagem_qtd para o mesmo produto
    @Query("""
            SELECT COUNT(DISTINCT cpf.fornecedorId) FROM CotacaoProdutoFornecedor cpf
            WHERE cpf.cotacaoProdutoId = :cotacaoProdutoId
              AND cpf.embalagemQtdConfirmada = :qtd
            """)
    long countFornecedoresComMesmaEmbalagem(@Param("cotacaoProdutoId") UUID cotacaoProdutoId, @Param("qtd") Integer qtd);

    @Query("""
            SELECT cpf FROM CotacaoProdutoFornecedor cpf
            JOIN CotacaoProduto cp ON cp.id = cpf.cotacaoProdutoId
            WHERE cp.cotacaoId = :cotacaoId
            """)
    List<CotacaoProdutoFornecedor> findByCotacaoId(@Param("cotacaoId") UUID cotacaoId);

    // Mesma junção de findByCotacaoIdAndFornecedorId, mas ordenada pela ordem da lista
    // base — usada só para reconstruir o texto persistido em ordem legível (ver
    // FornecedorRespostaService#textoPersistido), não pelo caminho quente do webhook.
    @Query("""
            SELECT cpf FROM CotacaoProdutoFornecedor cpf
            JOIN CotacaoProduto cp ON cp.id = cpf.cotacaoProdutoId
            WHERE cp.cotacaoId = :cotacaoId AND cpf.fornecedorId = :fornecedorId
            ORDER BY cp.ordem
            """)
    List<CotacaoProdutoFornecedor> findByCotacaoIdAndFornecedorIdOrderByOrdem(
            @Param("cotacaoId") UUID cotacaoId, @Param("fornecedorId") UUID fornecedorId);

    // Histórico de Preços: respostas de todas as cotações finalizadas do tenant de uma vez.
    @Query("""
            SELECT cpf FROM CotacaoProdutoFornecedor cpf
            JOIN CotacaoProduto cp ON cp.id = cpf.cotacaoProdutoId
            WHERE cp.cotacaoId IN :cotacaoIds
            """)
    List<CotacaoProdutoFornecedor> findByCotacaoIdIn(@Param("cotacaoIds") Collection<UUID> cotacaoIds);
}
