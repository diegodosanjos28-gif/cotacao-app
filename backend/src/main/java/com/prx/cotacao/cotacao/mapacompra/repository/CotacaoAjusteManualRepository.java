package com.prx.cotacao.cotacao.mapacompra.repository;

import com.prx.cotacao.cotacao.mapacompra.entity.CotacaoAjusteManual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoAjusteManualRepository extends JpaRepository<CotacaoAjusteManual, UUID> {

    List<CotacaoAjusteManual> findByCotacaoId(UUID cotacaoId);

    Optional<CotacaoAjusteManual> findByCotacaoIdAndCotacaoProdutoId(UUID cotacaoId, UUID cotacaoProdutoId);

    void deleteByCotacaoIdAndCotacaoProdutoId(UUID cotacaoId, UUID cotacaoProdutoId);

    void deleteByCotacaoId(UUID cotacaoId);

    // UPSERT via ON CONFLICT em vez de find-then-save: evita a race condition de
    // duas requisições concorrentes lendo "não existe" e as duas tentando inserir na
    // mesma UNIQUE (cotacao_id, cotacao_produto_id) — achado do db-schema-guardian.
    // criado_por não é sobrescrito num conflito (mantém quem criou o ajuste
    // originalmente; só o override em si e atualizado_em mudam).
    @Modifying
    @Query(value = """
            INSERT INTO cotacao_ajuste_manual
                (id, tenant_id, cotacao_id, cotacao_produto_id, fornecedor_id_override, criado_por, criado_em)
            VALUES
                (gen_random_uuid(), :tenantId, :cotacaoId, :cotacaoProdutoId, :fornecedorIdOverride, :criadoPor, now())
            ON CONFLICT (cotacao_id, cotacao_produto_id)
            DO UPDATE SET fornecedor_id_override = EXCLUDED.fornecedor_id_override, atualizado_em = now()
            """, nativeQuery = true)
    void upsert(@Param("tenantId") UUID tenantId,
                @Param("cotacaoId") UUID cotacaoId,
                @Param("cotacaoProdutoId") UUID cotacaoProdutoId,
                @Param("fornecedorIdOverride") UUID fornecedorIdOverride,
                @Param("criadoPor") UUID criadoPor);
}
