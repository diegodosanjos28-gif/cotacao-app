package com.prx.cotacao.cotacao.respostafornecedor.repository;

import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoProjecao;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.historico.dto.HistoricoPrecoContadores;
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

    // Comparativo em lote (ComparativoService.comparativoLote) — mesma junção de
    // findByCotacaoId, mas pra várias cotações de uma vez (ver CotacaoProdutoRepository
    // pro motivo: evitar 1 request/1 query por cotação visível na tela).
    @Query("""
            SELECT cpf FROM CotacaoProdutoFornecedor cpf
            JOIN CotacaoProduto cp ON cp.id = cpf.cotacaoProdutoId
            WHERE cp.cotacaoId IN :cotacaoIds
            """)
    List<CotacaoProdutoFornecedor> findByCotacaoIdIn(@Param("cotacaoIds") Collection<UUID> cotacaoIds);

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

    // Histórico de Preços (paginado por produto): candidatos de preço válidos escopados
    // a um lote de produtoIds (a página atual), em vez do full scan de todas as
    // cotações finalizadas do tenant. A lógica de "melhor oferta por (produto,
    // cotação)" (menor preço, tie-break por fornecedorId) continua em Java
    // (HistoricoPrecoService), só que agora sobre este conjunto já reduzido.
    @Query("""
            SELECT cpf FROM CotacaoProdutoFornecedor cpf
            JOIN CotacaoProduto cp ON cp.id = cpf.cotacaoProdutoId
            JOIN Cotacao c ON c.id = cp.cotacaoId
            WHERE cp.produtoId IN :produtoIds
              AND cp.removidoEm IS NULL
              AND c.status = :statusCotacao
              AND cpf.status = :statusItem
              AND cpf.semEstoque = false
              AND cpf.precoUnitarioCalculado IS NOT NULL
            """)
    List<CotacaoProdutoFornecedor> findCandidatosHistoricoPorProdutos(
            @Param("produtoIds") Collection<UUID> produtoIds,
            @Param("statusCotacao") CotacaoStatus statusCotacao,
            @Param("statusItem") StatusItem statusItem);

    // KPIs da tela Histórico de Preços (contadores agregados de todo o tenant,
    // independentes da página exibida — ver HistoricoPrecoPageResponse). Só precisa dos
    // 2 pontos mais recentes por produto pra classificar "acima"/"oportunidade"
    // (mesmo critério de frontend/src/lib/historicoPrecos.ts), então roda como uma
    // única query agregada no banco em vez de materializar o histórico inteiro em
    // memória Java. DISTINCT ON replica o mesmo tie-break do "melhor por (produto,
    // cotação)" (menor preço, depois menor fornecedor_id em ordem de bytes — idêntico a
    // cpf.getFornecedorId().toString().compareTo(...) em Java, já que o tipo uuid do
    // Postgres ordena pelos mesmos bytes que a representação hexadecimal minúscula
    // canônica do UUID). RLS das tabelas envolvidas (cotacao/cotacao_produto/
    // cotacao_produto_fornecedor) se aplica normalmente a native query — é avaliado
    // pelo Postgres na conexão, não pelo Hibernate filter.
    @Query(value = """
            WITH melhor_oferta AS (
                SELECT DISTINCT ON (cp.produto_id, cp.cotacao_id)
                       cp.produto_id AS produto_id,
                       c.finalizada_em AS finalizada_em,
                       cpf.preco_unitario_calculado AS preco
                FROM cotacao_produto_fornecedor cpf
                JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id
                JOIN cotacao c ON c.id = cp.cotacao_id
                WHERE cp.produto_id IS NOT NULL
                  AND cp.removido_em IS NULL
                  AND c.status = 'FINALIZADA'
                  AND cpf.status = 'OK'
                  AND cpf.sem_estoque = false
                  AND cpf.preco_unitario_calculado IS NOT NULL
                ORDER BY cp.produto_id, cp.cotacao_id, cpf.preco_unitario_calculado ASC, cpf.fornecedor_id ASC
            ),
            top2 AS (
                SELECT produto_id, preco,
                       ROW_NUMBER() OVER (PARTITION BY produto_id ORDER BY finalizada_em DESC) AS rn
                FROM melhor_oferta
            ),
            agregado AS (
                SELECT produto_id,
                       COUNT(*) AS cnt,
                       MAX(preco) FILTER (WHERE rn = 1) AS preco_recente,
                       MAX(preco) FILTER (WHERE rn = 2) AS preco_anterior
                FROM top2
                WHERE rn <= 2
                GROUP BY produto_id
            )
            SELECT
                COUNT(*) FILTER (WHERE cnt = 2) AS comHistorico,
                COUNT(*) FILTER (WHERE cnt = 2 AND preco_recente >= preco_anterior) AS acima,
                COUNT(*) FILTER (WHERE cnt = 2 AND preco_recente < preco_anterior) AS oportunidade
            FROM agregado
            """, nativeQuery = true)
    HistoricoPrecoContadores contarKpisHistorico();

    // KPIs de "Economia de Cotações" (Dashboard) — agregados sobre TODAS as cotações
    // FINALIZADA do tenant, não uma janela paginada (achado do usuário: os KPIs eram
    // calculados sobre, no máximo, as 20 cotações mais recentes de QUALQUER status,
    // não "todas as finalizadas"). Espelha exatamente a lógica de
    // frontend/src/lib/comparativo.ts (ofertaValida = status OK e não sem_estoque;
    // economiaPotencial = (maior-menor)*quantidade; percentualEconomia =
    // economia/(recomendado+economia)*100; fornecedorMaisCompetitivo = fornecedor com
    // mais itens vencidos por menor preço), agora calculada inteiramente no banco.
    //
    // Uma única passagem por cotacao_produto_fornecedor (ofertas_validas): window
    // functions calculam ranking/menor/maior por item juntos, no mesmo scan — em vez
    // de duas CTEs concorrentes escaneando a tabela de respostas separadamente (uma
    // pra menor/maior preço, outra pra achar o vencedor por DISTINCT ON), o que
    // dobraria o custo sem necessidade: a linha com rn=1 (menor preço, tie-break por
    // fornecedor_id) já contém tanto o fornecedor vencedor quanto os dados pra montar
    // economia/recomendado do item, então vencedor_por_item serve os dois propósitos.
    // Tie-break (menor preço, depois menor fornecedor_id em bytes) segue o mesmo
    // raciocínio de contarKpisHistorico acima. Índices que sustentam os joins:
    // idx_cotacao_status (tenant_id, status), idx_cotacao_produto_vivo (cotacao_id)
    // WHERE removido_em IS NULL, idx_cpf_cotacao_produto (cotacao_produto_id).
    @Query(value = """
            WITH ofertas_validas AS (
                SELECT
                    cp.id AS cotacao_produto_id,
                    cp.cotacao_id AS cotacao_id,
                    cp.quantidade AS quantidade,
                    cpf.fornecedor_id AS fornecedor_id,
                    cpf.preco_unitario_calculado AS preco,
                    ROW_NUMBER() OVER (
                        PARTITION BY cpf.cotacao_produto_id
                        ORDER BY cpf.preco_unitario_calculado ASC, cpf.fornecedor_id ASC
                    ) AS rn,
                    MIN(cpf.preco_unitario_calculado) OVER (PARTITION BY cpf.cotacao_produto_id) AS menor,
                    MAX(cpf.preco_unitario_calculado) OVER (PARTITION BY cpf.cotacao_produto_id) AS maior
                FROM cotacao_produto_fornecedor cpf
                JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id
                JOIN cotacao c ON c.id = cp.cotacao_id
                WHERE c.status = 'FINALIZADA'
                  AND cp.removido_em IS NULL
                  AND cpf.status = 'OK'
                  AND cpf.sem_estoque = false
            ),
            vencedor_por_item AS (
                SELECT
                    cotacao_produto_id,
                    cotacao_id,
                    fornecedor_id,
                    (maior - menor) * quantidade AS economia,
                    menor * quantidade AS recomendado
                FROM ofertas_validas
                WHERE rn = 1
            ),
            economia_por_cotacao AS (
                SELECT
                    c.id AS cotacao_id,
                    COALESCE(SUM(v.economia), 0) AS economia_cotacao,
                    COALESCE(SUM(v.recomendado), 0) AS recomendado_cotacao
                FROM cotacao c
                LEFT JOIN vencedor_por_item v ON v.cotacao_id = c.id
                WHERE c.status = 'FINALIZADA'
                GROUP BY c.id
            ),
            percentuais AS (
                SELECT
                    CASE WHEN (recomendado_cotacao + economia_cotacao) > 0
                         THEN (economia_cotacao / (recomendado_cotacao + economia_cotacao)) * 100
                         ELSE 0 END AS percentual
                FROM economia_por_cotacao
            ),
            vitorias_por_fornecedor AS (
                SELECT fornecedor_id, COUNT(*) AS vitorias
                FROM vencedor_por_item
                GROUP BY fornecedor_id
                ORDER BY vitorias DESC, fornecedor_id ASC
                LIMIT 1
            )
            SELECT
                (SELECT COUNT(*) FROM cotacao WHERE status = 'FINALIZADA') AS cotacoesProcessadas,
                (SELECT COALESCE(SUM(economia), 0) FROM vencedor_por_item) AS economiaAcumulada,
                (SELECT COALESCE(AVG(percentual), 0) FROM percentuais) AS mediaEconomiaPct,
                (SELECT fornecedor_id FROM vitorias_por_fornecedor) AS fornecedorVencedorId,
                (SELECT vitorias FROM vitorias_por_fornecedor) AS fornecedorVencedorContagem
            """, nativeQuery = true)
    EconomiaResumoProjecao resumoEconomiaFinalizadas();
}
