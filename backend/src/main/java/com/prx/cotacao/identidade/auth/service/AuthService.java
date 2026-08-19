package com.prx.cotacao.identidade.auth.service;

import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.identidade.auth.dto.*;
import com.prx.cotacao.identidade.auth.repository.RefreshTokenRepository;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UsuarioRepository usuarioRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public AuthService(UsuarioRepository usuarioRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginRateLimiter rateLimiter) {
        this.usuarioRepository = usuarioRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
    }

    // Login/refresh buscam o usuário sem saber o tenant ainda — é o próprio propósito
    // desses dois endpoints (descobrir o tenant a partir da credencial/token). RLS em
    // `usuario` bloquearia esse SELECT sem tenant_id/is_admin na sessão; o contexto
    // admin pra /auth/** inteiro é setado em TenantFilter (precisa acontecer ANTES do
    // início da transação — setar TenantContext aqui dentro do método @Transactional
    // seria tarde demais, a conexão já foi vinculada por doBegin() nesse ponto).
    // clientIp vem de HttpServletRequest.getRemoteAddr() (ver AuthResource) — NÃO lê
    // X-Forwarded-For, porque ainda não há um proxy reverso configurado no repo que
    // seja a única fonte confiável desse header; confiar nele sem uma allowlist de
    // proxy permitiria a um atacante forjá-lo pra burlar o limite por IP. Se/quando o
    // deploy ganhar Caddy/nginx na frente (deploy-ops), revisitar isto junto da config
    // de forwarded-headers do Spring.
    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        rateLimiter.checkIp(clientIp);
        // Reserva atômica (email+ip tight / email-only frouxo) ANTES do lookup —
        // reverte com onLoginSuccess no caminho feliz (ver LoginRateLimiter).
        rateLimiter.reserveLoginAttempt(request.email(), clientIp);

        Usuario usuario = usuarioRepository
                .findByEmailAndAtivo(request.email())
                .orElseThrow(() -> {
                    log.warn("Login falhou: usuário não encontrado ou inativo");
                    return new AuthException("Credenciais inválidas");
                });

        if (!passwordEncoder.matches(request.senha(), usuario.getSenhaHash())) {
            log.warn("Login falhou: senha incorreta para usuarioId={}", usuario.getId());
            throw new AuthException("Credenciais inválidas");
        }

        rateLimiter.onLoginSuccess(request.email(), clientIp);
        log.info("Login bem-sucedido: usuarioId={}", usuario.getId());
        return issueTokens(usuario, usuario.getTenantId());
    }

    // Mesma proteção de flood por IP usada em login (mesmo bucket — refresh e login
    // compartilham a superfície /auth/**, então um ataque contra qualquer um dos dois
    // consome o mesmo orçamento por IP). Brute-forçar a assinatura do JWT em si é
    // inviável computacionalmente; o que este check evita é abuso/flood do endpoint.
    // refreshToken chega via cookie httpOnly (lido em AuthResource), não mais no corpo.
    @Transactional
    public TokenResponse refresh(String refreshToken, String clientIp) {
        rateLimiter.checkIp(clientIp);

        if (refreshToken == null || !jwtService.isValid(refreshToken)) {
            throw new AuthException("Token inválido ou expirado");
        }

        Claims claims = jwtService.parseToken(refreshToken);
        if (!jwtService.isRefreshToken(claims)) {
            throw new AuthException("Token inválido");
        }

        UUID jti = UUID.fromString(jwtService.extractJti(claims));
        RefreshToken stored = refreshTokenRepository.findById(jti)
                .orElseThrow(() -> new AuthException("Token não encontrado"));

        // Banco é o árbitro final da expiração (defesa em profundidade além da assinatura JWT)
        if (stored.getExpiraEm().isBefore(OffsetDateTime.now())) {
            throw new AuthException("Token expirado");
        }

        // Detecta reutilização (possível roubo de token)
        if (stored.isUsado()) {
            log.warn("Refresh token reutilizado — revogando todos os tokens de usuarioId={}",
                    stored.getUsuarioId());
            refreshTokenRepository.revokeAllByUsuarioId(stored.getUsuarioId());
            throw new AuthException("Token já utilizado — todos os tokens foram revogados");
        }

        // Invalida o token atual
        stored.setUsado(true);
        refreshTokenRepository.save(stored);

        Usuario usuario = usuarioRepository
                .findById(stored.getUsuarioId())
                .filter(Usuario::isAtivo)
                .orElseThrow(() -> new AuthException("Usuário não encontrado"));

        log.info("Refresh bem-sucedido: usuarioId={}", usuario.getId());
        // Fonte de verdade do tenant em refresh é a linha de refresh token anterior
        // (tenantIdImpersonado), não usuario.getTenantId(): para OPERADOR_CLIENTE os
        // dois sempre coincidem, mas para ADMIN_PRX navegando um tenant escolhido via
        // /auth/selecionar-tenant, usuario.getTenantId() é sempre NULL (admin não tem
        // tenant fixo) — sem propagar a partir do refresh token, a escolha se perderia
        // a cada renovação de access token (15min).
        return issueTokens(usuario, stored.getTenantIdImpersonado());
    }

    // Autorização de quem pode chamar isto (só ADMIN_PRX) é feita no controller via
    // @PreAuthorize — mesmo padrão das rotas /admin/**, que confiam no matcher do
    // SecurityConfig. Mesmo assim, ativo/papel são rechecados aqui (ver filters
    // abaixo): achado de security-reviewer — sem isso, um ADMIN_PRX desativado via
    // /admin/administradores (ex: guarda de "último admin ativo" disparada por outro
    // admin) mas ainda de posse de um access token válido (janela de até 15min)
    // conseguiria renovar sua sessão indefinidamente chamando este endpoint em
    // loop, nunca passando pelo filtro de isAtivo() que refresh() já aplica.
    @Transactional
    public TokenResponse selecionarTenant(UUID usuarioId, UUID tenantId) {
        if (tenantId != null && !tenantRepository.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant não encontrado: " + tenantId);
        }
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .filter(u -> u.getPapel() == Papel.ADMIN_PRX)
                .orElseThrow(() -> new AuthException("Usuário não encontrado"));

        // Revoga refresh tokens anteriores ainda válidos deste admin — achado de
        // security-reviewer: sem isso, cada troca de tenant só acrescenta mais um
        // refresh token de 7 dias à pilha, deixando sessões simultâneas representando
        // tenants diferentes acumuladas indefinidamente. Trocar de tenant é, na
        // prática, uma sessão nova — reduz a superfície de tokens válidos ao mesmo
        // tempo pro papel mais privilegiado do sistema.
        refreshTokenRepository.revokeAllByUsuarioId(usuarioId);

        if (tenantId != null) {
            log.info("Admin usuarioId={} selecionou tenantId={} para navegação", usuarioId, tenantId);
        } else {
            log.info("Admin usuarioId={} voltou ao painel admin (sem tenant selecionado)", usuarioId);
        }
        return issueTokens(usuario, tenantId);
    }

    // Best-effort e idempotente de propósito: logout precisa "funcionar" mesmo sem
    // cookie (já deslogado), com cookie expirado, ou com um valor adulterado — nenhum
    // desses casos deve virar erro pro cliente, só não revoga nada no banco.
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || !jwtService.isValid(refreshToken)) {
            return;
        }
        Claims claims = jwtService.parseToken(refreshToken);
        if (!jwtService.isRefreshToken(claims)) {
            return;
        }
        UUID jti = UUID.fromString(jwtService.extractJti(claims));
        refreshTokenRepository.findById(jti).ifPresent(stored -> {
            stored.setUsado(true);
            refreshTokenRepository.save(stored);
        });
    }

    private TokenResponse issueTokens(Usuario usuario, UUID tenantId) {
        String accessToken = jwtService.generateAccessToken(
                usuario.getId(), tenantId, usuario.getPapel());

        UUID jti = UUID.randomUUID();
        OffsetDateTime expiraEm = OffsetDateTime.now()
                .plusSeconds(refreshTokenExpirationMs / 1000);

        refreshTokenRepository.save(new RefreshToken(jti, usuario.getId(), expiraEm, tenantId));

        String refreshToken = jwtService.generateRefreshToken(usuario.getId(), jti.toString());
        return new TokenResponse(accessToken, refreshToken);
    }
}
