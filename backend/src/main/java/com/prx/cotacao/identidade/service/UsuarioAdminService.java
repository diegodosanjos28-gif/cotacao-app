package com.prx.cotacao.identidade.service;

import com.prx.cotacao.identidade.dto.UsuarioAdminRequest;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.repository.TenantRepository;
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
 * CRUD de usuário OPERADOR_CLIENTE dentro de um tenant, operado pelo ADMIN_PRX
 * (rotas /admin/tenants/{tenantId}/usuarios). Não gerencia usuário ADMIN_PRX — fora
 * do escopo pedido.
 */
@Service
public class UsuarioAdminService {

    // Exclui caracteres ambíguos na leitura (0/O, 1/l/I) — senha é lida em voz alta ou
    // repassada por WhatsApp pelo admin ao cliente, não só copiada.
    private static final String ALFABETO_SENHA = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int TAMANHO_SENHA_GERADA = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarioRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public UsuarioAdminService(UsuarioRepository usuarioRepository, TenantRepository tenantRepository,
                                PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Usuario> listar(UUID tenantId) {
        garantirTenantExiste(tenantId);
        return usuarioRepository.findByTenantIdOrderByCriadoEmDesc(tenantId);
    }

    @Transactional
    public ResultadoCriacao criar(UUID tenantId, UsuarioAdminRequest request) {
        garantirTenantExiste(tenantId);
        if (usuarioRepository.existsByEmail(request.email())) {
            throw new ConflictException("Já existe um usuário cadastrado com esse email.");
        }

        boolean senhaGeradaAutomaticamente = !StringUtils.hasText(request.senha());
        String senhaPlana = senhaGeradaAutomaticamente ? gerarSenhaAleatoria() : request.senha();

        Usuario u = new Usuario();
        u.setTenantId(tenantId);
        u.setEmail(request.email());
        u.setSenhaHash(passwordEncoder.encode(senhaPlana));
        u.setPapel(Papel.OPERADOR_CLIENTE);
        u.setAtivo(request.ativo() == null || request.ativo());
        usuarioRepository.save(u);

        return new ResultadoCriacao(u, senhaGeradaAutomaticamente ? senhaPlana : null);
    }

    @Transactional
    public Usuario atualizar(UUID tenantId, UUID usuarioId, UsuarioAdminRequest request) {
        Usuario u = buscarNoTenant(tenantId, usuarioId);
        if (!u.getEmail().equalsIgnoreCase(request.email())
                && usuarioRepository.existsByEmailAndIdNot(request.email(), usuarioId)) {
            throw new ConflictException("Já existe um usuário cadastrado com esse email.");
        }
        u.setEmail(request.email());
        if (request.ativo() != null) u.setAtivo(request.ativo());
        if (StringUtils.hasText(request.senha())) {
            u.setSenhaHash(passwordEncoder.encode(request.senha()));
        }
        return usuarioRepository.save(u);
    }

    @Transactional
    public String resetarSenha(UUID tenantId, UUID usuarioId) {
        Usuario u = buscarNoTenant(tenantId, usuarioId);
        String senhaPlana = gerarSenhaAleatoria();
        u.setSenhaHash(passwordEncoder.encode(senhaPlana));
        usuarioRepository.save(u);
        return senhaPlana;
    }

    private Usuario buscarNoTenant(UUID tenantId, UUID usuarioId) {
        Usuario u = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + usuarioId));
        if (!tenantId.equals(u.getTenantId())) {
            throw new ResourceNotFoundException("Usuário não encontrado: " + usuarioId);
        }
        return u;
    }

    private void garantirTenantExiste(UUID tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant não encontrado: " + tenantId);
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
