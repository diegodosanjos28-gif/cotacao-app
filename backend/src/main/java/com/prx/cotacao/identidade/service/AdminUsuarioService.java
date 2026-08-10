package com.prx.cotacao.identidade.service;

import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * CRUD de usuário ADMIN_PRX (rotas /admin/administradores), operado por um ADMIN_PRX
 * já existente. Espelha UsuarioAdminService (que gerencia OPERADOR_CLIENTE dentro de
 * um tenant), mas aqui não há tenantId — todo ADMIN_PRX tem tenant_id NULL.
 */
@Service
public class AdminUsuarioService {

    // Mesmo alfabeto/tamanho de UsuarioAdminService — critério idêntico (exclui
    // caracteres ambíguos na leitura).
    private static final String ALFABETO_SENHA = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int TAMANHO_SENHA_GERADA = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUsuarioService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Usuario> listar() {
        return usuarioRepository.findByPapelOrderByCriadoEmDesc(Papel.ADMIN_PRX);
    }

    @Transactional
    public ResultadoCriacao criar(UsuarioAdminRequest request) {
        if (usuarioRepository.existsByEmail(request.email())) {
            throw new ConflictException("Já existe um usuário cadastrado com esse email.");
        }

        boolean senhaGeradaAutomaticamente = !StringUtils.hasText(request.senha());
        String senhaPlana = senhaGeradaAutomaticamente ? gerarSenhaAleatoria() : request.senha();

        Usuario u = new Usuario();
        u.setTenantId(null);
        u.setEmail(request.email());
        u.setSenhaHash(passwordEncoder.encode(senhaPlana));
        u.setPapel(Papel.ADMIN_PRX);
        u.setAtivo(request.ativo() == null || request.ativo());
        usuarioRepository.save(u);

        return new ResultadoCriacao(u, senhaGeradaAutomaticamente ? senhaPlana : null);
    }

    @Transactional
    public Usuario atualizar(UUID usuarioId, UsuarioAdminRequest request) {
        Usuario u = buscarAdmin(usuarioId);
        if (!u.getEmail().equalsIgnoreCase(request.email())
                && usuarioRepository.existsByEmailAndIdNot(request.email(), usuarioId)) {
            throw new ConflictException("Já existe um usuário cadastrado com esse email.");
        }

        boolean vaiDesativar = request.ativo() != null && !request.ativo() && u.isAtivo();
        if (vaiDesativar) {
            garantirNaoEhUltimoAdminAtivo();
        }

        u.setEmail(request.email());
        if (request.ativo() != null) u.setAtivo(request.ativo());
        if (StringUtils.hasText(request.senha())) {
            u.setSenhaHash(passwordEncoder.encode(request.senha()));
        }
        return usuarioRepository.save(u);
    }

    @Transactional
    public String resetarSenha(UUID usuarioId) {
        Usuario u = buscarAdmin(usuarioId);
        String senhaPlana = gerarSenhaAleatoria();
        u.setSenhaHash(passwordEncoder.encode(senhaPlana));
        usuarioRepository.save(u);
        return senhaPlana;
    }

    // Sem tenantId pra restringir o escopo (diferente de UsuarioAdminService), a busca
    // por id sozinha permitiria editar/resetar senha de QUALQUER usuário do sistema
    // por adivinhação de UUID — checar papel == ADMIN_PRX fecha esse buraco.
    private Usuario buscarAdmin(UUID usuarioId) {
        Usuario u = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Administrador não encontrado: " + usuarioId));
        if (u.getPapel() != Papel.ADMIN_PRX) {
            throw new ResourceNotFoundException("Administrador não encontrado: " + usuarioId);
        }
        return u;
    }

    // Sem esta guarda, um erro de operação (ou o único admin se autodesativando)
    // trancaria o sistema inteiro fora do painel admin, sem ninguém com acesso pra
    // reverter — não há "recuperação de senha" self-service para ADMIN_PRX.
    private void garantirNaoEhUltimoAdminAtivo() {
        if (usuarioRepository.countByPapelAndAtivo(Papel.ADMIN_PRX, true) <= 1) {
            throw new ConflictException("Não é possível desativar o último administrador ativo.");
        }
    }

    private static String gerarSenhaAleatoria() {
        StringBuilder sb = new StringBuilder(TAMANHO_SENHA_GERADA);
        for (int i = 0; i < TAMANHO_SENHA_GERADA; i++) {
            sb.append(ALFABETO_SENHA.charAt(RANDOM.nextInt(ALFABETO_SENHA.length())));
        }
        return sb.toString();
    }

    public record ResultadoCriacao(Usuario usuario, String senhaGerada) {}
}
