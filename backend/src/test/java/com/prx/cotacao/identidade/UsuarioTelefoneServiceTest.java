package com.prx.cotacao.identidade;

import com.prx.cotacao.identidade.dto.UsuarioTelefoneRequest;
import com.prx.cotacao.identidade.entity.Tenant;
import com.prx.cotacao.identidade.entity.Usuario;
import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import com.prx.cotacao.identidade.enums.Papel;
import com.prx.cotacao.identidade.enums.TenantStatus;
import com.prx.cotacao.identidade.repository.TenantRepository;
import com.prx.cotacao.identidade.repository.UsuarioRepository;
import com.prx.cotacao.identidade.service.UsuarioTelefoneService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UsuarioTelefoneService}: número de WhatsApp único GLOBALMENTE — mesmo entre
 * tenants diferentes, já que {@code numero_whatsapp} é UNIQUE global (ver
 * V21__create_usuario_telefone_autorizado.sql, não só um índice por tenant) — rejeição
 * de {@code criar} sem tenant (ADMIN_PRX não tem tenant, ver javadoc de
 * {@link UsuarioTelefoneService}), e {@code remover} não vaza existência de telefone de
 * outro usuário: mesmo 404 tanto para "não existe" quanto para "existe mas não é meu".
 *
 * Roda dentro de {@link #comoTenantA}/{@link #comoTenantB} — mesma convenção de
 * {@link com.prx.cotacao.MultiTenantIsolationTest} — reproduzindo o TenantContext de um
 * OPERADOR_CLIENTE autenticado (não admin), condição real sob a qual estas rotas
 * (/usuarios/me/telefones) são chamadas em produção.
 *
 * Pré-requisito: Postgres local rodando (mesmo perfil dev), porta 5555, mesma convenção
 * de {@link com.prx.cotacao.fornecedor.FornecedorServiceNomeUnicoTest}.
 */
@SpringBootTest
@ActiveProfiles("dev")
class UsuarioTelefoneServiceTest {

    @Autowired private UsuarioTelefoneService usuarioTelefoneService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private UUID tenantAId;
    private UUID tenantBId;
    private UUID usuarioAId;
    private UUID usuarioA2Id;
    private UUID usuarioBId;

    @BeforeEach
    void setup() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            Tenant a = new Tenant();
            a.setNomeFantasia("Tenant A - Usuario Telefone Service");
            a.setStatus(TenantStatus.TRIAL);
            tenantAId = tenantRepository.save(a).getId();

            Tenant b = new Tenant();
            b.setNomeFantasia("Tenant B - Usuario Telefone Service");
            b.setStatus(TenantStatus.TRIAL);
            tenantBId = tenantRepository.save(b).getId();

            usuarioAId = usuarioRepository.save(novoUsuario(tenantAId)).getId();
            usuarioA2Id = usuarioRepository.save(novoUsuario(tenantAId)).getId();
            usuarioBId = usuarioRepository.save(novoUsuario(tenantBId)).getId();
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.setAdmin(true);
        tx.execute(status -> {
            jdbc.update("DELETE FROM usuario_telefone_autorizado WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM usuario WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
            jdbc.update("DELETE FROM tenant WHERE id IN (?, ?)", tenantAId, tenantBId);
            return null;
        });
        TenantContext.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T comoTenantA(Supplier<T> fn) {
        TenantContext.set(tenantAId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private <T> T comoTenantB(Supplier<T> fn) {
        TenantContext.set(tenantBId.toString());
        TenantContext.setAdmin(false);
        try { return tx.execute(s -> fn.get()); }
        finally { TenantContext.clear(); }
    }

    private Usuario novoUsuario(UUID tenantId) {
        Usuario u = new Usuario();
        u.setTenantId(tenantId);
        u.setEmail("telefone-service-test-" + UUID.randomUUID() + "@prx-test.com");
        u.setSenhaHash("hash-nao-importa");
        u.setPapel(Papel.OPERADOR_CLIENTE);
        return u;
    }

    private String numeroUnico() {
        return "+5511" + String.format("%09d", SEQ.incrementAndGet());
    }

    private UsuarioTelefoneRequest request(String numero) {
        return new UsuarioTelefoneRequest(numero, "Fulano de Tal");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void criar_grava_telefone_vinculado_ao_usuario_e_ao_tenant() {
        String numero = numeroUnico();

        UsuarioTelefoneAutorizado criado = comoTenantA(() ->
                usuarioTelefoneService.criar(usuarioAId, tenantAId, request(numero)));

        assertEquals(UsuarioTelefoneAutorizado.normalizarNumero(numero), criado.getNumeroWhatsapp());
        assertEquals("Fulano de Tal", criado.getNomeContato());
        assertEquals(usuarioAId, criado.getUsuarioId());
        assertEquals(tenantAId, criado.getTenantId());
        assertTrue(criado.isAtivo(), "Telefone deve nascer ativo por padrão");
        assertNotNull(criado.getId());
    }

    @Test
    void criar_comNumeroJaCadastradoNoMesmoTenant_lancaConflictException() {
        String numero = numeroUnico();
        comoTenantA(() -> usuarioTelefoneService.criar(usuarioAId, tenantAId, request(numero)));

        assertThrows(ConflictException.class,
                () -> comoTenantA(() -> usuarioTelefoneService.criar(usuarioA2Id, tenantAId, request(numero))),
                "Segundo cadastro do mesmo número dentro do mesmo tenant deve lançar 409");
    }

    @Test
    void criar_comNumeroJaCadastradoEmOutroTenant_lancaConflictExceptionComMensagemClara() {
        // numero_whatsapp é UNIQUE *global* (V21). A checagem amigável
        // (existsByNumeroWhatsapp) só enxerga o tenant atual — RLS do Postgres
        // restringe o que a sessão consegue SELECT no nível da conexão, não só o
        // Hibernate @Filter (nem query nativa contorna isso). Por isso
        // UsuarioTelefoneService.criar usa saveAndFlush + catch de
        // DataIntegrityViolationException como backstop: a constraint UNIQUE do banco
        // não é afetada por RLS, então pega o caso cross-tenant e devolve a mesma
        // ConflictException amigável (achado do db-schema-guardian, corrigido).
        String numero = numeroUnico();
        comoTenantA(() -> usuarioTelefoneService.criar(usuarioAId, tenantAId, request(numero)));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> comoTenantB(() -> usuarioTelefoneService.criar(usuarioBId, tenantBId, request(numero))),
                "numero_whatsapp é único no sistema inteiro, não só dentro do tenant");
        assertEquals("Este número já está cadastrado no sistema.", ex.getMessage());
    }

    @Test
    void criar_semTenant_lancaIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> usuarioTelefoneService.criar(usuarioAId, null, request(numeroUnico())),
                "ADMIN_PRX não tem tenant — cadastrar telefone com tenantId nulo deve ser rejeitado");
    }

    @Test
    void listar_retorna_apenas_telefones_do_usuario_informado_mais_recente_primeiro() {
        String numero1 = numeroUnico();
        String numero2 = numeroUnico();
        comoTenantA(() -> usuarioTelefoneService.criar(usuarioAId, tenantAId, request(numero1)));
        comoTenantA(() -> usuarioTelefoneService.criar(usuarioAId, tenantAId, request(numero2)));
        // Telefone de outro usuário do mesmo tenant não deve vazar na lista do primeiro.
        comoTenantA(() -> usuarioTelefoneService.criar(usuarioA2Id, tenantAId, request(numeroUnico())));

        List<UsuarioTelefoneAutorizado> lista = comoTenantA(() -> usuarioTelefoneService.listar(usuarioAId));

        assertEquals(2, lista.size());
        assertEquals(UsuarioTelefoneAutorizado.normalizarNumero(numero2), lista.get(0).getNumeroWhatsapp(), "Mais recente primeiro (criadoEm desc)");
        assertEquals(UsuarioTelefoneAutorizado.normalizarNumero(numero1), lista.get(1).getNumeroWhatsapp());
    }

    @Test
    void remover_telefonePropioDoUsuario_removeComSucesso() {
        UUID telefoneId = comoTenantA(() ->
                usuarioTelefoneService.criar(usuarioAId, tenantAId, request(numeroUnico())).getId());

        assertDoesNotThrow(() -> comoTenantA(() -> {
            usuarioTelefoneService.remover(usuarioAId, telefoneId);
            return null;
        }));

        List<UsuarioTelefoneAutorizado> lista = comoTenantA(() -> usuarioTelefoneService.listar(usuarioAId));
        assertTrue(lista.isEmpty(), "Telefone removido não deve mais aparecer na listagem");
    }

    @Test
    void remover_telefoneQuePertenceAOutroUsuario_lancaResourceNotFoundExceptionSemVazarExistencia() {
        UUID telefoneDeA2 = comoTenantA(() ->
                usuarioTelefoneService.criar(usuarioA2Id, tenantAId, request(numeroUnico())).getId());

        assertThrows(ResourceNotFoundException.class,
                () -> comoTenantA(() -> {
                    usuarioTelefoneService.remover(usuarioAId, telefoneDeA2);
                    return null;
                }),
                "Telefone existe mas pertence a outro usuarioId — deve dar 404, mesmo caso de 'não existe'");

        // Confirma que a tentativa que falhou não removeu o registro de fato.
        List<UsuarioTelefoneAutorizado> listaA2 = comoTenantA(() -> usuarioTelefoneService.listar(usuarioA2Id));
        assertEquals(1, listaA2.size());
    }

    @Test
    void remover_telefoneInexistente_lancaResourceNotFoundException() {
        UUID idInexistente = UUID.randomUUID();

        assertThrows(ResourceNotFoundException.class,
                () -> comoTenantA(() -> {
                    usuarioTelefoneService.remover(usuarioAId, idInexistente);
                    return null;
                }));
    }
}
