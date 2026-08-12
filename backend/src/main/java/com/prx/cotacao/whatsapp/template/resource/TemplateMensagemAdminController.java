package com.prx.cotacao.whatsapp.template.resource;

import com.prx.cotacao.whatsapp.template.dto.TemplateMensagemAdminRequest;
import com.prx.cotacao.whatsapp.template.dto.TemplateMensagemAdminResponse;
import com.prx.cotacao.whatsapp.template.entity.TemplateMensagem;
import com.prx.cotacao.whatsapp.template.service.TemplateMensagemAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/tenants/{tenantId}/templates-mensagem")
public class TemplateMensagemAdminController {

    private final TemplateMensagemAdminService templateService;

    public TemplateMensagemAdminController(TemplateMensagemAdminService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public List<TemplateMensagemAdminResponse> listar(@PathVariable UUID tenantId) {
        return templateService.listar(tenantId).stream().map(TemplateMensagemAdminResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<TemplateMensagemAdminResponse> criar(@PathVariable UUID tenantId,
                                                                 @Valid @RequestBody TemplateMensagemAdminRequest request) {
        TemplateMensagem criado = templateService.criar(tenantId, request);
        URI location = URI.create("/admin/tenants/" + tenantId + "/templates-mensagem/" + criado.getId());
        return ResponseEntity.created(location).body(TemplateMensagemAdminResponse.from(criado));
    }

    @PutMapping("/{templateId}")
    public TemplateMensagemAdminResponse atualizar(@PathVariable UUID tenantId, @PathVariable UUID templateId,
                                                     @Valid @RequestBody TemplateMensagemAdminRequest request) {
        return TemplateMensagemAdminResponse.from(templateService.atualizar(tenantId, templateId, request));
    }
}
