package com.prx.cotacao.shared.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Extrai o tenant_id do SecurityContext (populado pelo JwtAuthFilter) e o armazena
 * no TenantContext (ThreadLocal). O TenantAwareDataSource usa esse valor para
 * executar SET app.current_tenant_id em cada conexão Postgres antes de queries.
 *
 * ADMIN_PRX é tratado de 3 formas diferentes dependendo da rota e de ter ou não um
 * tenant escolhido via POST /auth/selecionar-tenant (claim tenant_id do JWT) — ver
 * decisão de arquitetura completa nos comentários abaixo.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    // Únicos endpoints que legitimamente precisam rodar sem tenant conhecido (é o
    // próprio propósito deles: descobrir o tenant a partir da credencial/refresh
    // token). Lista exata, não prefixo — um "/auth/**" por prefixo daria bypass de
    // RLS automaticamente pra qualquer endpoint novo adicionado sob /auth no futuro
    // (ex: um /auth/password-reset esperando comportamento normal de tenant),
    // mesmo que SecurityConfig também libere esse endpoint sem auth. Achado de
    // security-reviewer em 2026-07-09.
    private static final Set<String> ENDPOINTS_SEM_TENANT = Set.of("/auth/login", "/auth/refresh");

    // /auth/selecionar-tenant não é um endpoint "sem tenant" (precisa do usuarioId
    // autenticado), mas precisa do MESMO bypass total de /admin/**: o admin troca de
    // tenant a qualquer momento, inclusive enquanto já está navegando um tenant
    // diferente — sem bypass aqui, tenantRepository.existsById() (policy
    // tenant_admin_only, sem fallback por tenant_id) e usuarioRepository.findById()
    // do próprio admin (tenant_id sempre NULL na tabela usuario) falhariam sob RLS.
    private static final String ROTA_SELECIONAR_TENANT = "/auth/selecionar-tenant";

    private final ObjectMapper objectMapper;

    public TenantFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String uri = request.getRequestURI();
            if (ENDPOINTS_SEM_TENANT.contains(uri)) {
                // Precisa ser setado aqui, no filtro, ANTES do controller chamar o
                // service transacional: setar TenantContext dentro de um método
                // @Transactional já seria tarde demais, pois
                // TenantAwareTransactionManager.doBegin() já vinculou a conexão (e
                // TenantAwareDataSource já rodou set_config nela) antes do corpo do
                // método executar. Seguro porque o único dado acessado por esses dois
                // endpoints é o próprio usuário que está autenticando.
                TenantContext.setAdmin(true);
                filterChain.doFilter(request, response);
                return;
            }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getDetails() instanceof TenantDetails details) {
                boolean isAdminPapel = "ADMIN_PRX".equals(details.papel());
                boolean rotaDeGestaoAdmin = uri.startsWith("/admin/") || uri.equals(ROTA_SELECIONAR_TENANT);

                if (isAdminPapel && rotaDeGestaoAdmin) {
                    // Painel administrativo (CRUD de tenants/administradores) e a
                    // própria troca de tenant: bypass total, sempre, independente de
                    // ter ou não um tenant escolhido no momento — é o único jeito do
                    // admin enxergar TODOS os tenants (pra listar/trocar) e a si mesmo
                    // (tenant_id NULL na tabela usuario).
                    TenantContext.setAdmin(true);
                } else if (isAdminPapel && details.tenantId() != null) {
                    // Admin "navegando" um tenant escolhido via
                    // POST /auth/selecionar-tenant, em rota de negócio: tratado
                    // EXATAMENTE como um operador desse tenant — RLS e o Hibernate
                    // Filter (TenantAwareTransactionManager) passam a valer de
                    // verdade. É a defesa em profundidade: mesmo que um bug de
                    // autorização deixasse passar uma chamada indevida, o Postgres
                    // ainda barra fora do tenant escolhido.
                    TenantContext.set(details.tenantId());
                    TenantContext.setAdmin(false);
                } else if (isAdminPapel) {
                    // Admin sem tenant selecionado batendo numa rota de negócio (fora
                    // de /admin/**). Antes desta mudança isso caía direto no bypass
                    // total (is_admin_request()=true, tenant_id NULL) e devolvia dados
                    // de TODOS os tenants misturados sem filtro nenhum — lacuna de
                    // segurança latente, nunca explorada porque o frontend nunca monta
                    // essa chamada pra este papel, mas não bloqueada no backend.
                    responderTenantNaoSelecionado(response);
                    return;
                } else {
                    TenantContext.set(details.tenantId());
                    TenantContext.setAdmin(false);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void responderTenantNaoSelecionado(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Selecione um tenant antes de acessar este recurso.");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
