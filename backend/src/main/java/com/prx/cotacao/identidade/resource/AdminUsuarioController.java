package com.prx.cotacao.identidade.resource;

import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.dto.ResetSenhaResponse;
import com.prx.cotacao.identidade.dto.UsuarioAdminResponse;
import com.prx.cotacao.identidade.service.AdminUsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/administradores")
public class AdminUsuarioController {

    private final AdminUsuarioService adminUsuarioService;

    public AdminUsuarioController(AdminUsuarioService adminUsuarioService) {
        this.adminUsuarioService = adminUsuarioService;
    }

    @GetMapping
    public List<UsuarioAdminResponse> listar() {
        return adminUsuarioService.listar().stream().map(UsuarioAdminResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<UsuarioAdminResponse> criar(@Valid @RequestBody UsuarioAdminRequest request) {
        AdminUsuarioService.ResultadoCriacao resultado = adminUsuarioService.criar(request);
        URI location = URI.create("/admin/administradores/" + resultado.usuario().getId());
        return ResponseEntity.created(location)
                .body(UsuarioAdminResponse.from(resultado.usuario(), resultado.senhaGerada()));
    }

    @PutMapping("/{usuarioId}")
    public UsuarioAdminResponse atualizar(@PathVariable UUID usuarioId,
                                          @Valid @RequestBody UsuarioAdminRequest request) {
        return UsuarioAdminResponse.from(adminUsuarioService.atualizar(usuarioId, request));
    }

    @PostMapping("/{usuarioId}/reset-senha")
    public ResetSenhaResponse resetarSenha(@PathVariable UUID usuarioId) {
        return new ResetSenhaResponse(adminUsuarioService.resetarSenha(usuarioId));
    }
}
