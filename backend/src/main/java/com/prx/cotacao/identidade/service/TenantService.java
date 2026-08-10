package com.prx.cotacao.identidade.service;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.dto.TenantRequest;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public List<Tenant> listar(String q, TenantStatus status) {
        return tenantRepository.buscar(StringUtils.hasText(q) ? q.trim() : "", status);
    }

    public Tenant buscar(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant não encontrado: " + id));
    }

    @Transactional
    public Tenant criar(TenantRequest request) {
        validarCnpjUnico(request.cnpj(), null);
        Tenant t = new Tenant();
        aplicarRequest(t, request);
        return tenantRepository.save(t);
    }

    @Transactional
    public Tenant atualizar(UUID id, TenantRequest request) {
        Tenant t = buscar(id);
        validarCnpjUnico(request.cnpj(), id);
        aplicarRequest(t, request);
        return tenantRepository.save(t);
    }

    private void validarCnpjUnico(String cnpj, UUID idAtual) {
        if (!StringUtils.hasText(cnpj)) return;
        boolean duplicado = idAtual == null
                ? tenantRepository.existsByCnpj(cnpj)
                : tenantRepository.existsByCnpjAndIdNot(cnpj, idAtual);
        if (duplicado) {
            throw new ConflictException("Já existe um tenant cadastrado com esse CNPJ.");
        }
    }

    private void aplicarRequest(Tenant t, TenantRequest request) {
        t.setNomeFantasia(request.nomeFantasia());
        t.setRazaoSocial(request.razaoSocial());
        t.setCnpj(StringUtils.hasText(request.cnpj()) ? request.cnpj() : null);
        if (request.status() != null) t.setStatus(request.status());
        t.setPlano(request.plano());
    }
}
