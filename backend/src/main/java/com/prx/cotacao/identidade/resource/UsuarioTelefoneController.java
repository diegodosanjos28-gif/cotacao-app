package com.prx.cotacao.identidade.resource;

import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import com.prx.cotacao.identidade.dto.UsuarioTelefoneRequest;
import com.prx.cotacao.identidade.dto.UsuarioTelefoneResponse;
import com.prx.cotacao.identidade.service.UsuarioTelefoneService;
import com.prx.cotacao.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/usuarios/me/telefones")
public class UsuarioTelefoneController {

    private final UsuarioTelefoneService usuarioTelefoneService;
    private final CurrentUser currentUser;

    public UsuarioTelefoneController(UsuarioTelefoneService usuarioTelefoneService, CurrentUser currentUser) {
        this.usuarioTelefoneService = usuarioTelefoneService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<UsuarioTelefoneResponse> listar() {
        return usuarioTelefoneService.listar(currentUser.usuarioId()).stream()
                .map(UsuarioTelefoneResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<UsuarioTelefoneResponse> criar(@Valid @RequestBody UsuarioTelefoneRequest request) {
        UsuarioTelefoneAutorizado criado = usuarioTelefoneService.criar(
                currentUser.usuarioId(), currentUser.tenantId(), request);
        URI location = URI.create("/usuarios/me/telefones/" + criado.getId());
        return ResponseEntity.created(location).body(UsuarioTelefoneResponse.from(criado));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remover(@PathVariable UUID id) {
        usuarioTelefoneService.remover(currentUser.usuarioId(), id);
        return ResponseEntity.noContent().build();
    }
}
