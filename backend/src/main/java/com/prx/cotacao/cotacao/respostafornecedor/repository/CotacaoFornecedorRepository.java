package com.prx.cotacao.cotacao.respostafornecedor.repository;

import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoFornecedorRepository extends JpaRepository<CotacaoFornecedor, UUID> {

    List<CotacaoFornecedor> findByCotacaoIdOrderByOrdem(UUID cotacaoId);

    Optional<CotacaoFornecedor> findTopByCotacaoIdOrderByOrdemDesc(UUID cotacaoId);

    Optional<CotacaoFornecedor> findByCotacaoIdAndFornecedorId(UUID cotacaoId, UUID fornecedorId);

    boolean existsByCotacaoIdAndFornecedorId(UUID cotacaoId, UUID fornecedorId);
}
