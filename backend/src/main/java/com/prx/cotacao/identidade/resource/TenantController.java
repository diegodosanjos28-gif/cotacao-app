package com.prx.cotacao.identidade.resource;

import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.dto.TenantRequest;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.dto.TenantResponse;
import com.prx.cotacao.identidade.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public List<TenantResponse> listar(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) TenantStatus status) {
        return tenantService.listar(q, status).stream().map(TenantResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TenantResponse buscar(@PathVariable UUID id) {
        return TenantResponse.from(tenantService.buscar(id));
    }

    @PostMapping
    public ResponseEntity<TenantResponse> criar(@Valid @RequestBody TenantRequest request) {
        Tenant t = tenantService.criar(request);
        return ResponseEntity.created(URI.create("/admin/tenants/" + t.getId())).body(TenantResponse.from(t));
    }

    @PutMapping("/{id}")
    public TenantResponse atualizar(@PathVariable UUID id, @Valid @RequestBody TenantRequest request) {
        return TenantResponse.from(tenantService.atualizar(id, request));
    }
}
