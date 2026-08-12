package com.prx.cotacao.whatsapp.template.repository;

import com.prx.cotacao.whatsapp.template.ResultadoNotificacao;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateMensagemRepository extends JpaRepository<TemplateMensagem, UUID> {

    List<TemplateMensagem> findByTenantId(UUID tenantId);

    Optional<TemplateMensagem> findByTenantIdAndResultadoAndAtivoTrue(UUID tenantId, ResultadoNotificacao resultado);

    boolean existsByTenantIdAndResultado(UUID tenantId, ResultadoNotificacao resultado);
}
