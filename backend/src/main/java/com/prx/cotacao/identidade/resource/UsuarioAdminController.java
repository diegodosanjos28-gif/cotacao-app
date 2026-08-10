package com.prx.cotacao.identidade.resource;

import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.dto.ResetSenhaResponse;
import com.prx.cotacao.identidade.dto.UsuarioAdminResponse;
import com.prx.cotacao.identidade.service.UsuarioAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/tenants/{tenantId}/usuarios")
public class UsuarioAdminController {

    private final UsuarioAdminService usuarioAdminService;

    public UsuarioAdminController(UsuarioAdminService usuarioAdminService) {
        this.usuarioAdminService = usuarioAdminService;
    }

    @GetMapping
    public List<UsuarioAdminResponse> listar(@PathVariable UUID tenantId) {
        return usuarioAdminService.listar(tenantId).stream().map(UsuarioAdminResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<UsuarioAdminResponse> criar(@PathVariable UUID tenantId,
                                                        @Valid @RequestBody UsuarioAdminRequest request) {
        UsuarioAdminService.ResultadoCriacao resultado = usuarioAdminService.criar(tenantId, request);
        URI location = URI.create("/admin/tenants/" + tenantId + "/usuarios/" + resultado.usuario().getId());
        return ResponseEntity.created(location)
                .body(UsuarioAdminResponse.from(resultado.usuario(), resultado.senhaGerada()));
    }

    @PutMapping("/{usuarioId}")
    public UsuarioAdminResponse atualizar(@PathVariable UUID tenantId, @PathVariable UUID usuarioId,
                                           @Valid @RequestBody UsuarioAdminRequest request) {
        return UsuarioAdminResponse.from(usuarioAdminService.atualizar(tenantId, usuarioId, request));
    }

    @PostMapping("/{usuarioId}/reset-senha")
    public ResetSenhaResponse resetarSenha(@PathVariable UUID tenantId, @PathVariable UUID usuarioId) {
        return new ResetSenhaResponse(usuarioAdminService.resetarSenha(tenantId, usuarioId));
    }
}
