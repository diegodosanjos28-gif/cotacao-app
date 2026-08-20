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

    // Comparativo em lote (ComparativoService.comparativoLote): itens de várias
    // cotações de uma vez, em vez de 1 chamada de rede + 1 query por cotação — achado
    // do usuário 08-20: o Dashboard (Economia de Cotações + Todas as cotações, cada
    // linha visível chamando comparativo(id) em paralelo) estava disparando dezenas de
    // requisições simultâneas e estourando o rate limit por IP (429).
    List<CotacaoProduto> findByCotacaoIdInAndRemovidoEmIsNullOrderByOrdem(Collection<UUID> cotacaoIds);

    // idx_cotacao_produto_unico_por_produto (V6/V25) só permite um CotacaoProduto vivo
    // por produtoId dentro da mesma cotação — usado por CotacaoProdutoItemService pra
    // dar um erro claro antes de bater na constraint do banco.
    Optional<CotacaoProduto> findByCotacaoIdAndProdutoIdAndRemovidoEmIsNull(UUID cotacaoId, UUID produtoId);

    @Query("SELECT MAX(cp.ordem) FROM CotacaoProduto cp WHERE cp.cotacaoId = :cotacaoId")
    Integer findMaxOrdemByCotacaoId(@Param("cotacaoId") UUID cotacaoId);
}
