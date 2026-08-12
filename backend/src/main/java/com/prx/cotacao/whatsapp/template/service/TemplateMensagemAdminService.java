package com.prx.cotacao.whatsapp.template.service;

import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.whatsapp.template.dto.TemplateMensagemAdminRequest;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.repository.TemplateMensagemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD dos templates de mensagem WhatsApp de um tenant (rotas
 * /admin/tenants/{tenantId}/templates-mensagem). No máximo 2 linhas por tenant —
 * uma para {@code resultado=SUCESSO}, uma para {@code resultado=ERRO} — reforçado pela
 * constraint {@code UNIQUE(tenant_id, resultado)} no banco.
 */
@Service
public class TemplateMensagemAdminService {

    private final TemplateMensagemRepository templateRepository;
    private final TenantRepository tenantRepository;

    public TemplateMensagemAdminService(TemplateMensagemRepository templateRepository,
                                         TenantRepository tenantRepository) {
        this.templateRepository = templateRepository;
        this.tenantRepository = tenantRepository;
    }

    public List<TemplateMensagem> listar(UUID tenantId) {
        garantirTenantExiste(tenantId);
        return templateRepository.findByTenantId(tenantId);
    }

    @Transactional
    public TemplateMensagem criar(UUID tenantId, TemplateMensagemAdminRequest request) {
        garantirTenantExiste(tenantId);
        if (templateRepository.existsByTenantIdAndResultado(tenantId, request.resultado())) {
            throw new ConflictException("Já existe um template cadastrado para " + request.resultado() + " neste tenant.");
        }

        TemplateMensagem t = new TemplateMensagem();
        t.setTenantId(tenantId);
        t.setResultado(request.resultado());
        aplicarCamposEditaveis(t, request);
        return templateRepository.save(t);
    }

    @Transactional
    public TemplateMensagem atualizar(UUID tenantId, UUID templateId, TemplateMensagemAdminRequest request) {
        TemplateMensagem t = buscarNoTenant(tenantId, templateId);
        // `resultado` é imutável após criado — a vaga (SUCESSO/ERRO) não muda de lugar.
        aplicarCamposEditaveis(t, request);
        return templateRepository.save(t);
    }

    private void aplicarCamposEditaveis(TemplateMensagem t, TemplateMensagemAdminRequest request) {
        t.setNomeTemplateMeta(request.nomeTemplateMeta());
        t.setIdioma(request.idioma());
        t.setConteudo(request.conteudo());
        t.setDescricaoParametros(request.descricaoParametros());
        t.setAtivo(request.ativo() == null || request.ativo());
    }

    private TemplateMensagem buscarNoTenant(UUID tenantId, UUID templateId) {
        TemplateMensagem t = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template não encontrado: " + templateId));
        if (!tenantId.equals(t.getTenantId())) {
            throw new ResourceNotFoundException("Template não encontrado: " + templateId);
        }
        return t;
    }

    private void garantirTenantExiste(UUID tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant não encontrado: " + tenantId);
        }
    }
}
