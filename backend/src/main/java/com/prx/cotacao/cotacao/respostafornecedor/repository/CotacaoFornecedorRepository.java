package com.prx.cotacao.cotacao.respostafornecedor.repository;

import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoProjecao;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoFornecedorRepository extends JpaRepository<CotacaoFornecedor, UUID> {

    List<CotacaoFornecedor> findByCotacaoIdOrderByOrdem(UUID cotacaoId);

    Optional<CotacaoFornecedor> findTopByCotacaoIdOrderByOrdemDesc(UUID cotacaoId);

    Optional<CotacaoFornecedor> findByCotacaoIdAndFornecedorId(UUID cotacaoId, UUID fornecedorId);

    boolean existsByCotacaoIdAndFornecedorId(UUID cotacaoId, UUID fornecedorId);

    // Hall dos Fornecedores, modo histórico (refactor Entrada de Dados) — médias
    // consolidadas por fornecedor sobre TODAS as cotações FINALIZADA em que ele
    // participou (CotacaoFornecedor CONFIRMADO). RLS das tabelas envolvidas
    // (fornecedor/cotacao/cotacao_produto/cotacao_produto_fornecedor/
    // cotacao_fornecedor) se aplica normalmente a native query — avaliado pelo
    // Postgres na conexão, não pelo Hibernate filter (mesmo padrão de
    // CotacaoProdutoFornecedorRepository.contarKpisHistorico).
    //
    // coberturaMediaPct: por (fornecedor, cotação), % de itens da lista base viva
    // (cotacao_produto.removido_em IS NULL) que o fornecedor cotou com status OK,
    // depois média simples entre as cotações em que ele participou.
    //
    // tempoRespostaMedioMinutos: AVG(atualizado_em - criado_em) em cotacao_fornecedor
    // CONFIRMADO. É uma APROXIMAÇÃO — não existe coluna dedicada de "respondido em"
    // no schema; atualizado_em reflete a última vez que o registro foi salvo (que
    // pode incluir uma confirmação desfeita e refeita depois, ver comentário de
    // ConfirmacaoRespostaService sobre o fluxo de "desfazer"). Exibir como "estimado"
    // na UI (mesma linguagem "Prévia" do mockup) — uma coluna real de timestamp de
    // resposta fica fora de escopo deste refactor.
    @Query(value = """
            WITH confirmados AS (
                SELECT cf.fornecedor_id, cf.cotacao_id, cf.atualizado_em, cf.criado_em
                FROM cotacao_fornecedor cf
                JOIN cotacao c ON c.id = cf.cotacao_id
                WHERE c.status = 'FINALIZADA' AND cf.status = 'CONFIRMADO'
            ),
            itens_base_por_cotacao AS (
                SELECT cp.cotacao_id, COUNT(*) AS total
                FROM cotacao_produto cp
                WHERE cp.removido_em IS NULL
                GROUP BY cp.cotacao_id
            ),
            itens_cotados_por_fornecedor_cotacao AS (
                SELECT cf.fornecedor_id, cf.cotacao_id, COUNT(DISTINCT cpf.cotacao_produto_id) AS cotados
                FROM confirmados cf
                JOIN cotacao_produto cp ON cp.cotacao_id = cf.cotacao_id AND cp.removido_em IS NULL
                JOIN cotacao_produto_fornecedor cpf
                     ON cpf.cotacao_produto_id = cp.id
                    AND cpf.fornecedor_id = cf.fornecedor_id
                    AND cpf.status = 'OK'
                GROUP BY cf.fornecedor_id, cf.cotacao_id
            ),
            cobertura_por_cotacao AS (
                SELECT
                    cf.fornecedor_id,
                    cf.cotacao_id,
                    cf.atualizado_em,
                    cf.criado_em,
                    COALESCE(icf.cotados, 0)::numeric / NULLIF(ibc.total, 0) * 100 AS cobertura_pct
                FROM confirmados cf
                JOIN itens_base_por_cotacao ibc ON ibc.cotacao_id = cf.cotacao_id
                LEFT JOIN itens_cotados_por_fornecedor_cotacao icf
                       ON icf.fornecedor_id = cf.fornecedor_id AND icf.cotacao_id = cf.cotacao_id
            )
            SELECT
                f.id AS fornecedorId,
                f.nome AS nome,
                COUNT(cpc.cotacao_id) AS cotacoesParticipadas,
                COALESCE(AVG(cpc.cobertura_pct), 0) AS coberturaMediaPct,
                COALESCE(AVG(EXTRACT(EPOCH FROM (cpc.atualizado_em - cpc.criado_em)) / 60), 0) AS tempoRespostaMedioMinutos
            FROM fornecedor f
            LEFT JOIN cobertura_por_cotacao cpc ON cpc.fornecedor_id = f.id
            WHERE f.status = 'ATIVO'
            GROUP BY f.id, f.nome
            ORDER BY f.nome
            """, nativeQuery = true)
    List<FornecedorHistoricoProjecao> buscarHistoricoFornecedores();
}
