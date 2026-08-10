package com.prx.cotacao.cotacao.core.repository;

import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoProdutoRepository extends JpaRepository<CotacaoProduto, UUID> {

    // Só itens vivos (não soft-deleted, Prompt 12) — renomeado a partir de
    // findByCotacaoIdOrderByOrdem pra forçar erro de compilação em todo call site
    // antigo em vez de confiar que cada um lembraria de filtrar removido_em.
    List<CotacaoProduto> findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(UUID cotacaoId);

    // idx_cotacao_produto_unico_por_produto (V6/V25) só permite um CotacaoProduto vivo
    // por produtoId dentro da mesma cotação — usado por CotacaoProdutoItemService pra
    // dar um erro claro antes de bater na constraint do banco.
    Optional<CotacaoProduto> findByCotacaoIdAndProdutoIdAndRemovidoEmIsNull(UUID cotacaoId, UUID produtoId);

    // Histórico de Preços: itens de todas as cotações finalizadas do tenant de uma vez.
    // Filtra removido_em (Prompt 12): um item excluído do grid antes da finalização não
    // deve continuar contribuindo pro histórico de preços depois de finalizada.
    List<CotacaoProduto> findByCotacaoIdInAndRemovidoEmIsNull(Collection<UUID> cotacaoIds);

    @Query("SELECT MAX(cp.ordem) FROM CotacaoProduto cp WHERE cp.cotacaoId = :cotacaoId")
    Integer findMaxOrdemByCotacaoId(@Param("cotacaoId") UUID cotacaoId);
}
