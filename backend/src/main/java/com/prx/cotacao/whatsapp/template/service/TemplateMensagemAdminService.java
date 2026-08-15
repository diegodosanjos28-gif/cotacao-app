package com.prx.cotacao.whatsapp.template.service;

import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.whatsapp.template.CatalogoParametrosNotificacao;
import com.prx.cotacao.whatsapp.template.dto.TemplateMensagemAdminRequest;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.repository.TemplateMensagemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRUD dos templates de mensagem WhatsApp de um tenant (rotas
 * /admin/tenants/{tenantId}/templates-mensagem). No máximo 1 linha por
 * (tenant, acao_cliente) — vaga identificada por {@code acaoClienteId}, reforçada pelo
 * índice único {@code uq_template_mensagem_tenant_acao_cliente} no banco.
 */
@Service
public class TemplateMensagemAdminService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final TemplateMensagemRepository templateRepository;
    private final TenantRepository tenantRepository;
    private final AcaoClienteCenarioRepository cenarioRepository;

    public TemplateMensagemAdminService(TemplateMensagemRepository templateRepository,
                                         TenantRepository tenantRepository,
                                         AcaoClienteCenarioRepository cenarioRepository) {
        this.templateRepository = templateRepository;
        this.tenantRepository = tenantRepository;
        this.cenarioRepository = cenarioRepository;
    }

    public List<TemplateMensagem> listar(UUID tenantId) {
        garantirTenantExiste(tenantId);
        return templateRepository.findByTenantId(tenantId);
    }

    @Transactional
    public TemplateMensagem criar(UUID tenantId, TemplateMensagemAdminRequest request) {
        garantirTenantExiste(tenantId);
        AcaoCliente cenario = buscarCenario(request.acaoClienteId());
        validarParametros(request, cenario);

        if (templateRepository.existsByTenantIdAndAcaoClienteId(tenantId, request.acaoClienteId())) {
            throw new ConflictException("Já existe um template cadastrado para " + cenario.getAcao()
                    + (cenario.getResultado() != null ? " / " + cenario.getResultado() : "") + " neste tenant.");
        }

        TemplateMensagem t = new TemplateMensagem();
        t.setTenantId(tenantId);
        t.setAcaoClienteId(request.acaoClienteId());
        aplicarCamposEditaveis(t, request);
        return templateRepository.save(t);
    }

    @Transactional
    public TemplateMensagem atualizar(UUID tenantId, UUID templateId, TemplateMensagemAdminRequest request) {
        TemplateMensagem t = buscarNoTenant(tenantId, templateId);
        // `acaoClienteId` é imutável após criado — a vaga não muda de lugar; ignora o
        // valor vindo do request e usa o que já está gravado.
        AcaoCliente cenario = buscarCenario(t.getAcaoClienteId());
        validarParametros(request, cenario);
        aplicarCamposEditaveis(t, request);
        return templateRepository.save(t);
    }

    private void aplicarCamposEditaveis(TemplateMensagem t, TemplateMensagemAdminRequest request) {
        t.setNomeTemplateMeta(request.nomeTemplateMeta());
        t.setIdioma(request.idioma());
        t.setConteudo(request.conteudo());
        t.setDescricaoParametros(request.descricaoParametros());
        t.setParametrosOrdenados(request.parametrosOrdenados() != null ? request.parametrosOrdenados() : List.of());
        t.setAtivo(request.ativo() == null || request.ativo());
    }

    // Garante que todo {{identificador}} usado no conteúdo existe no catálogo do
    // cenário (acao, resultado) do template E foi adicionado à lista ordenada (senão
    // não teria posição definida no envio real) — e que a lista ordenada não referencia
    // nenhum identificador fora do catálogo.
    private void validarParametros(TemplateMensagemAdminRequest request, AcaoCliente cenario) {
        List<String> tokensNoConteudo = extrairTokens(request.conteudo());
        List<String> catalogoEfetivoIds = CatalogoParametrosNotificacao
                .getEfetivo(cenario.getAcao(), cenario.getResultado())
                .stream().map(CatalogoParametrosNotificacao.ItemCatalogo::identificador).toList();
        List<String> parametrosOrdenados = request.parametrosOrdenados() != null ? request.parametrosOrdenados() : List.of();

        for (String token : tokensNoConteudo) {
            if (!catalogoEfetivoIds.contains(token)) {
                throw new IllegalArgumentException(
                        "Parâmetro \"" + token + "\" usado no conteúdo não existe no catálogo deste cenário.");
            }
            if (!parametrosOrdenados.contains(token)) {
                throw new IllegalArgumentException(
                        "Parâmetro \"" + token + "\" aparece no conteúdo mas não foi adicionado à lista de parâmetros do envio.");
            }
        }
        for (String id : parametrosOrdenados) {
            if (!catalogoEfetivoIds.contains(id)) {
                throw new IllegalArgumentException(
                        "Identificador \"" + id + "\" em parâmetros não existe no catálogo deste cenário.");
            }
        }
    }

    private List<String> extrairTokens(String conteudo) {
        if (conteudo == null) {
            return List.of();
        }
        Matcher m = TOKEN_PATTERN.matcher(conteudo);
        List<String> tokens = new ArrayList<>();
        while (m.find()) {
            tokens.add(m.group(1));
        }
        return tokens;
    }

    private AcaoCliente buscarCenario(UUID acaoClienteId) {
        return cenarioRepository.findById(acaoClienteId)
                .orElseThrow(() -> new ResourceNotFoundException("acao_cliente não encontrado: " + acaoClienteId));
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
