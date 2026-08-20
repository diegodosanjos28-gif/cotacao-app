package com.prx.cotacao.cotacao.respostafornecedor.repository;

import com.prx.cotacao.cotacao.comparativo.dto.ConcentracaoFornecedorProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.CotacaoFinalizadaCursorProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.VariacaoPrecoProjecao;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.historico.dto.HistoricoPrecoContadores;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    // Carrossel "Cotações Registradas" (Dashboard) — página por cursor/keyset em
    // (finalizada_em DESC, id DESC), sustentada por idx_cotacao_finalizada (V19).
    // :cursorFinalizadaEm/:cursorId nulos = primeira página (a comparação de tupla
    // some via curto-circuito do "IS NULL OR", mas os CASTs continuam explícitos —
    // mesmo motivo do CAST em CotacaoRepository.buscar(): Postgres tipa a query
    // inteira antes de avaliar o curto-circuito). Busca :limite (size+1) linhas —
    // o service usa a linha extra só pra derivar hasMore, sem um COUNT separado.
    // :inicio/:fim (nulos = sem filtro) restringem por finalizada_em — filtro de
    // período opcional do carrossel, mesmo padrão nullable-OR dos demais parâmetros.
    // Agregados por cotação reaproveitam a mesma base "vencedor por item" (menor
    // preço, tie-break por fornecedor_id) de resumoEconomiaFinalizadas, só que
    // agrupada por cotação em vez de somada sobre o tenant inteiro.
    @Query(value = """
            WITH pagina AS (
                SELECT c.id AS id, c.finalizada_em AS finalizada_em
                FROM cotacao c
                WHERE c.status = 'FINALIZADA'
                  AND (CAST(:inicio AS timestamptz) IS NULL OR c.finalizada_em >= CAST(:inicio AS timestamptz))
                  AND (CAST(:fim AS timestamptz) IS NULL OR c.finalizada_em < CAST(:fim AS timestamptz))
                  AND (CAST(:cursorFinalizadaEm AS timestamptz) IS NULL
                       OR (c.finalizada_em, c.id) < (CAST(:cursorFinalizadaEm AS timestamptz), CAST(:cursorId AS uuid)))
                ORDER BY c.finalizada_em DESC, c.id DESC
                LIMIT :limite
            ),
            ofertas_validas AS (
                SELECT
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
                WHERE cp.cotacao_id IN (SELECT id FROM pagina)
                  AND cp.removido_em IS NULL
                  AND cpf.status = 'OK'
                  AND cpf.sem_estoque = false
            ),
            vencedor_por_item AS (
                SELECT cotacao_id, fornecedor_id, (maior - menor) * quantidade AS economia, menor * quantidade AS recomendado
                FROM ofertas_validas
                WHERE rn = 1
            ),
            itens_por_cotacao AS (
                SELECT cp.cotacao_id AS cotacao_id, COUNT(*) AS itens
                FROM cotacao_produto cp
                WHERE cp.cotacao_id IN (SELECT id FROM pagina) AND cp.removido_em IS NULL
                GROUP BY cp.cotacao_id
            )
            SELECT
                p.id AS id,
                p.finalizada_em AS finalizadaEm,
                COALESCE(i.itens, 0) AS itensCotados,
                COALESCE(SUM(v.recomendado), 0) AS valorTotalComprado,
                COALESCE(SUM(v.economia), 0) AS economizado,
                CASE WHEN (COALESCE(SUM(v.recomendado), 0) + COALESCE(SUM(v.economia), 0)) > 0
                     THEN (COALESCE(SUM(v.economia), 0) / (COALESCE(SUM(v.recomendado), 0) + COALESCE(SUM(v.economia), 0))) * 100
                     ELSE 0 END AS economizadoPct,
                COUNT(DISTINCT v.fornecedor_id) AS fornecedoresCount
            FROM pagina p
            LEFT JOIN vencedor_por_item v ON v.cotacao_id = p.id
            LEFT JOIN itens_por_cotacao i ON i.cotacao_id = p.id
            GROUP BY p.id, p.finalizada_em, i.itens
            ORDER BY p.finalizada_em DESC, p.id DESC
            """, nativeQuery = true)
    List<CotacaoFinalizadaCursorProjecao> buscarPaginaCarrossel(
            @Param("cursorFinalizadaEm") OffsetDateTime cursorFinalizadaEm,
            @Param("cursorId") UUID cursorId,
            @Param("limite") int limite,
            @Param("inicio") OffsetDateTime inicio,
            @Param("fim") OffsetDateTime fim);

    // Total de cotações FINALIZADA que batem com o filtro do carrossel (mesmo
    // :inicio/:fim de buscarPaginaCarrossel, nulos = sem filtro) — contagem
    // independente do que já foi carregado no cliente, não incrementada a cada
    // página (achado do usuário: o badge "N cotações" não pode refletir só o que
    // chegou na primeira leva). idx_cotacao_finalizada (V19) cobre esse COUNT.
    @Query(value = """
            SELECT COUNT(*) FROM cotacao c
            WHERE c.status = 'FINALIZADA'
              AND (CAST(:inicio AS timestamptz) IS NULL OR c.finalizada_em >= CAST(:inicio AS timestamptz))
              AND (CAST(:fim AS timestamptz) IS NULL OR c.finalizada_em < CAST(:fim AS timestamptz))
            """, nativeQuery = true)
    long contarFinalizadas(@Param("inicio") OffsetDateTime inicio, @Param("fim") OffsetDateTime fim);

    // Painel "Variação de Preço por Produto" (Dashboard) — Top N produtos por spread
    // (maior-menor)/menor dentro da competência [:inicio, :fim). Mesma base de
    // ofertas válidas dos demais painéis; HAVING >= 2 fornecedores é a "regra de
    // ouro" do handoff (produto cotado por 1 só fornecedor não tem spread). Tie-break
    // de qual fornecedor é "o do menor/maior preço" por fornecedor_id ASC, mesmo
    // critério do resto do sistema.
    @Query(value = """
            WITH ofertas_validas AS (
                SELECT
                    cp.produto_id AS produto_id,
                    cpf.fornecedor_id AS fornecedor_id,
                    cpf.preco_unitario_calculado AS preco
                FROM cotacao_produto_fornecedor cpf
                JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id
                JOIN cotacao c ON c.id = cp.cotacao_id
                WHERE c.status = 'FINALIZADA'
                  AND cp.removido_em IS NULL
                  AND cpf.status = 'OK'
                  AND cpf.sem_estoque = false
                  AND cp.produto_id IS NOT NULL
                  AND c.finalizada_em >= :inicio AND c.finalizada_em < :fim
            ),
            por_produto AS (
                SELECT produto_id,
                       COUNT(DISTINCT fornecedor_id) AS qtd_fornecedores,
                       MIN(preco) AS menor_preco,
                       MAX(preco) AS maior_preco
                FROM ofertas_validas
                GROUP BY produto_id
                HAVING COUNT(DISTINCT fornecedor_id) >= 2
            ),
            fornecedor_menor AS (
                SELECT DISTINCT ON (ov.produto_id) ov.produto_id AS produto_id, ov.fornecedor_id AS fornecedor_id
                FROM ofertas_validas ov
                JOIN por_produto pp ON pp.produto_id = ov.produto_id AND ov.preco = pp.menor_preco
                ORDER BY ov.produto_id, ov.fornecedor_id ASC
            ),
            fornecedor_maior AS (
                SELECT DISTINCT ON (ov.produto_id) ov.produto_id AS produto_id, ov.fornecedor_id AS fornecedor_id
                FROM ofertas_validas ov
                JOIN por_produto pp ON pp.produto_id = ov.produto_id AND ov.preco = pp.maior_preco
                ORDER BY ov.produto_id, ov.fornecedor_id ASC
            )
            SELECT
                pp.produto_id AS produtoId,
                pp.menor_preco AS menorPreco,
                pp.maior_preco AS maiorPreco,
                ((pp.maior_preco - pp.menor_preco) / pp.menor_preco * 100) AS spreadPct,
                fmin.fornecedor_id AS fornecedorMenorId,
                fmax.fornecedor_id AS fornecedorMaiorId,
                -- Contagem sobre TODOS os produtos elegíveis (>= 2 fornecedores), não só
                -- os Top N devolvidos abaixo — janela avaliada antes do LIMIT (ordem
                -- lógica do SQL: WHERE/GROUP BY -> janela -> ORDER BY -> LIMIT), então
                -- todas as linhas do Top N carregam o mesmo total.
                COUNT(*) OVER () AS totalElegiveis
            FROM por_produto pp
            JOIN fornecedor_menor fmin ON fmin.produto_id = pp.produto_id
            JOIN fornecedor_maior fmax ON fmax.produto_id = pp.produto_id
            ORDER BY spreadPct DESC
            LIMIT :topN
            """, nativeQuery = true)
    List<VariacaoPrecoProjecao> buscarTopSpread(
            @Param("inicio") OffsetDateTime inicio, @Param("fim") OffsetDateTime fim, @Param("topN") int topN);

    // Painel "Concentração de Fornecedores" (Dashboard) — Top N fornecedores por
    // participação no valor "vencido" (mesma base recomendado = menor*quantidade de
    // EconomiaResumoService) dentro da competência [:inicio, :fim).
    @Query(value = """
            WITH ofertas_validas AS (
                SELECT
                    cp.quantidade AS quantidade,
                    cpf.fornecedor_id AS fornecedor_id,
                    cpf.preco_unitario_calculado AS preco,
                    ROW_NUMBER() OVER (
                        PARTITION BY cpf.cotacao_produto_id
                        ORDER BY cpf.preco_unitario_calculado ASC, cpf.fornecedor_id ASC
                    ) AS rn
                FROM cotacao_produto_fornecedor cpf
                JOIN cotacao_produto cp ON cp.id = cpf.cotacao_produto_id
                JOIN cotacao c ON c.id = cp.cotacao_id
                WHERE c.status = 'FINALIZADA'
                  AND cp.removido_em IS NULL
                  AND cpf.status = 'OK'
                  AND cpf.sem_estoque = false
                  AND c.finalizada_em >= :inicio AND c.finalizada_em < :fim
            ),
            vencedor_por_item AS (
                SELECT fornecedor_id, preco * quantidade AS valor
                FROM ofertas_validas
                WHERE rn = 1
            ),
            por_fornecedor AS (
                SELECT fornecedor_id, SUM(valor) AS valor_comprado
                FROM vencedor_por_item
                GROUP BY fornecedor_id
            ),
            total AS (
                SELECT SUM(valor_comprado) AS total_geral FROM por_fornecedor
            )
            SELECT
                pf.fornecedor_id AS fornecedorId,
                pf.valor_comprado AS valorComprado,
                CASE WHEN t.total_geral > 0 THEN (pf.valor_comprado / t.total_geral * 100) ELSE 0 END AS sharePct
            FROM por_fornecedor pf, total t
            ORDER BY sharePct DESC
            LIMIT :topN
            """, nativeQuery = true)
    List<ConcentracaoFornecedorProjecao> buscarTopConcentracao(
            @Param("inicio") OffsetDateTime inicio, @Param("fim") OffsetDateTime fim, @Param("topN") int topN);
}
