package com.prx.cotacao.cotacao.core.repository;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CotacaoRepository extends JpaRepository<Cotacao, UUID> {

    // Hibernate filter já aplica tenant_id — findAll(Pageable) retorna apenas cotações do tenant
    // findById(id) também já filtra pelo tenant via Hibernate filter

    // Histórico de Preços: todas as finalizadas do tenant, mais recente primeiro
    // (ver idx_cotacao_finalizada, V19).
    List<Cotacao> findByStatusOrderByFinalizadaEmDesc(CotacaoStatus status);

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
