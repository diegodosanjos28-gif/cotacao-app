package com.prx.cotacao.whatsapp.template.repository;

import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateMensagemRepository extends JpaRepository<TemplateMensagem, UUID> {

    List<TemplateMensagem> findByTenantId(UUID tenantId);

    Optional<TemplateMensagem> findByTenantIdAndAcaoClienteIdAndAtivoTrue(UUID tenantId, UUID acaoClienteId);

    boolean existsByTenantIdAndAcaoClienteId(UUID tenantId, UUID acaoClienteId);
}
