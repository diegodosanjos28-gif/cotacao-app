package com.prx.cotacao.shared.setup;

import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Cria o primeiro usuário ADMIN_PRX no boot, lendo credenciais de
 * ADMIN_BOOTSTRAP_EMAIL/ADMIN_BOOTSTRAP_PASSWORD — hoje não existe nenhum outro jeito
 * de conseguir o primeiro admin (Painel Admin PRX ainda não existe, e nenhuma
 * migration semeia esse usuário).
 *
 * Implementado como SmartInitializingSingleton, não ApplicationRunner: mesmo motivo
 * documentado em {@link com.prx.cotacao.shared.tenant.DbRoleGuard} — ApplicationRunner
 * só roda depois de todo refresh() do contexto terminar, mas o Tomcat embutido já abre
 * os connectors (aceita conexões HTTP de verdade) em finishRefresh(). Usar
 * afterSingletonsInstantiated() garante que o admin (quando aplicável) já existe antes
 * da porta abrir, em vez de depender de uma corrida de timing.
 *
 * Roda em todo profile, incluindo produção — não é gateado por @Profile.
 */
@Component
public class AdminBootstrapRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapSenha;

    public AdminBootstrapRunner(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.bootstrap-email:#{null}}") String bootstrapEmail,
            @Value("${app.admin.bootstrap-password:#{null}}") String bootstrapSenha) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapSenha = bootstrapSenha;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // A checagem de existência em si já precisa do bypass: policy tenant_isolation
        // de V10__rls_policies.sql exige is_admin_request() para enxergar linhas com
        // tenant_id NULL (todo ADMIN_PRX), e no boot, fora de qualquer request HTTP,
        // TenantContext está vazio. Mesmo padrão usado por TenantFilter para
        // /auth/login, /auth/refresh e /admin/**.
        TenantContext.setAdmin(true);
        try {
            if (usuarioRepository.existsByPapel(Papel.ADMIN_PRX)) {
                log.info("Bootstrap de ADMIN_PRX pulado: já existe pelo menos um administrador cadastrado.");
                return;
            }

            if (!StringUtils.hasText(bootstrapEmail) || !StringUtils.hasText(bootstrapSenha)) {
                log.warn("Nenhum ADMIN_PRX cadastrado e ADMIN_BOOTSTRAP_EMAIL/ADMIN_BOOTSTRAP_PASSWORD não "
                        + "configurados — bootstrap pulado. A aplicação subirá sem nenhum administrador até "
                        + "que essas variáveis sejam configuradas (e a aplicação reiniciada) ou um ADMIN_PRX "
                        + "seja criado por outro meio.");
                return;
            }

            Usuario admin = new Usuario();
            admin.setTenantId(null);
            admin.setEmail(bootstrapEmail);
            admin.setSenhaHash(passwordEncoder.encode(bootstrapSenha));
            admin.setPapel(Papel.ADMIN_PRX);
            admin.setAtivo(true);
            usuarioRepository.save(admin);

            log.warn("ADMIN_PRX padrão criado via ADMIN_BOOTSTRAP_EMAIL (email: {}). Troque a senha no "
                    + "primeiro login.", bootstrapEmail);
        } finally {
            TenantContext.clear();
        }
    }
}
