package com.prx.cotacao.cotacao.core.repository;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoRepository extends JpaRepository<Cotacao, UUID> {

    // Hibernate filter já aplica tenant_id — findAll(Pageable) retorna apenas cotações do tenant
    // findById(id) também já filtra pelo tenant via Hibernate filter

    // Listagem do dashboard (Prompt 24) com filtro opcional por status e por termo de
    // busca (título ou canal). status/termo nulos = mesmo resultado do findAll(Pageable)
    // — usado por KPIs/Economia, que continuam chamando sem filtro.
    // :termo precisa de CAST explícito pra string — sem ele, o parâmetro nulo dentro
    // do CONCAT confunde a inferência de tipo do driver Postgres (acaba tipando como
    // bytea e quebra o LOWER(...) mesmo quando a cláusula "termo IS NULL" já
    // curto-circuitaria em tempo de execução; Postgres tipa a query inteira antes de
    // avaliar).
    @Query("""
            SELECT c FROM Cotacao c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:termo IS NULL
                   OR LOWER(c.titulo) LIKE LOWER(CONCAT('%', CAST(:termo AS string), '%'))
                   OR LOWER(CAST(c.canalOrigem AS string)) LIKE LOWER(CONCAT('%', CAST(:termo AS string), '%')))
            """)
    Page<Cotacao> buscar(@Param("status") CotacaoStatus status, @Param("termo") String termo, Pageable pageable);

    // Roteamento de WhatsApp (Fase 3, seção 10.4 da doc técnica): "cotação em
    // andamento" é por usuário (criadoPor), não só por tenant — coberta por
    // idx_cotacao_whatsapp_usuario (V24). A janela de 48h é o parâmetro
    // ultimaAtividadeDesde (now.minusHours(48)), aplicado aqui, não no índice.
    Optional<Cotacao> findFirstByCriadoPorAndCanalOrigemAndStatusAndUltimaAtividadeEmGreaterThanOrderByUltimaAtividadeEmDesc(
            UUID criadoPor, CanalOrigem canalOrigem, CotacaoStatus status, OffsetDateTime ultimaAtividadeDesde);

    default Cotacao findByIdOrThrow(UUID id) {
        return findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cotação não encontrada: " + id));
    }
}
