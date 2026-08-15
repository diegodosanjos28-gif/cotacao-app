package com.prx.cotacao.identidade;

import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.shared.setup.AdminBootstrapRunner;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitários de AdminBootstrapRunner — UsuarioRepository mockado (Mockito),
 * sem subir contexto Spring nem tocar banco real: a lógica em si (existência prévia,
 * env vars ausentes, criação, idempotência) não depende de RLS de verdade, só de
 * TenantContext.setAdmin(true) estar setado no momento da chamada ao repositório
 * (verificado abaixo), que é o que o TenantAwareDataSource real usaria para liberar o
 * INSERT sob a policy tenant_isolation de V10__rls_policies.sql.
 *
 * PasswordEncoder é um BCryptPasswordEncoder real (não mockado) — barato o bastante
 * para não valer a pena mockar, e garante que o hash gerado é de verdade verificável.
 */
class AdminBootstrapRunnerTest {

    private static final String EMAIL = "admin-bootstrap@prx-test.com";
    private static final String SENHA = "SenhaForteDoBootstrap123";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @AfterEach
    void limparTenantContext() {
        // Garante que um teste que falhe antes do finally do runner não vaze
        // TenantContext.isAdmin()=true pros testes seguintes desta classe.
        TenantContext.clear();
    }

    @Test
    void envVarsConfiguradas_semAdminExistente_criaUsuarioAdminPrxComSenhaEmHash() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        when(repo.existsByPapel(Papel.ADMIN_PRX)).thenReturn(false);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(repo, passwordEncoder, EMAIL, SENHA);
        runner.afterSingletonsInstantiated();

        verify(repo, times(1)).save(any(Usuario.class));
        Usuario salvo = capturarUsuarioSalvo(repo);
        assertEquals(Papel.ADMIN_PRX, salvo.getPapel());
        assertNull(salvo.getTenantId(), "ADMIN_PRX sempre tem tenant_id NULL");
        assertTrue(salvo.isAtivo());
        assertEquals(EMAIL, salvo.getEmail());
        assertNotEquals(SENHA, salvo.getSenhaHash(), "senha nunca deve ser armazenada em texto puro");
        assertTrue(passwordEncoder.matches(SENHA, salvo.getSenhaHash()));
    }

    @Test
    void envVarsConfiguradas_comAdminJaExistente_naoCriaNenhumUsuarioNovo() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        when(repo.existsByPapel(Papel.ADMIN_PRX)).thenReturn(true);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(repo, passwordEncoder, EMAIL, SENHA);
        runner.afterSingletonsInstantiated();

        verify(repo, never()).save(any());
    }

    @Test
    void semEnvVarsConfiguradas_semAdminExistente_naoLancaExcecaoENaoCriaUsuario() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        when(repo.existsByPapel(Papel.ADMIN_PRX)).thenReturn(false);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(repo, passwordEncoder, null, null);

        assertDoesNotThrow(runner::afterSingletonsInstantiated,
                "ausência das env vars não pode impedir o boot da aplicação");
        verify(repo, never()).save(any());
    }

    @Test
    void semEnvVarsConfiguradas_stringVaziaOuEmBranco_tambemNaoCriaUsuario() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        when(repo.existsByPapel(Papel.ADMIN_PRX)).thenReturn(false);

        AdminBootstrapRunner runnerEmailVazio = new AdminBootstrapRunner(repo, passwordEncoder, "  ", SENHA);
        runnerEmailVazio.afterSingletonsInstantiated();

        AdminBootstrapRunner runnerSenhaVazia = new AdminBootstrapRunner(repo, passwordEncoder, EMAIL, "");
        runnerSenhaVazia.afterSingletonsInstantiated();

        verify(repo, never()).save(any());
    }

    @Test
    void rodarDuasVezesEmSequenciaComEnvVarsConfiguradas_criaApenasUmUsuarioNoTotal() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        // Simula dois boots reais: no 1º, ainda não existe ADMIN_PRX; a partir do
        // save() bem-sucedido, o 2º boot já encontraria o usuário criado no 1º.
        when(repo.existsByPapel(Papel.ADMIN_PRX)).thenReturn(false, true);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(repo, passwordEncoder, EMAIL, SENHA);
        runner.afterSingletonsInstantiated();
        runner.afterSingletonsInstantiated();

        verify(repo, times(1)).save(any(Usuario.class));
    }

    @Test
    void aoRodar_setaTenantContextAdminDuranteAChamadaELimpaAoFinal() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        // A checagem de existência é o próprio ponto em que TenantContext.isAdmin()
        // precisa estar true (é a query que a RLS avaliaria primeiro) — captura o
        // valor no momento da chamada via stub.
        when(repo.existsByPapel(eq(Papel.ADMIN_PRX))).thenAnswer(invocation -> {
            assertTrue(TenantContext.isAdmin(), "TenantContext.isAdmin() deve estar true durante a checagem, "
                    + "senão a policy tenant_isolation de V10__rls_policies.sql bloquearia a leitura");
            return false;
        });

        AdminBootstrapRunner runner = new AdminBootstrapRunner(repo, passwordEncoder, EMAIL, SENHA);
        runner.afterSingletonsInstantiated();

        assertFalse(TenantContext.isAdmin(), "TenantContext deve ser limpo (finally) ao final da execução");
    }

    private Usuario capturarUsuarioSalvo(UsuarioRepository repo) {
        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }
}
