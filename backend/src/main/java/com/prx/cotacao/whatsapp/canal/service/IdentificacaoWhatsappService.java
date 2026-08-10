package com.prx.cotacao.whatsapp.canal.service;

import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import com.prx.cotacao.whatsapp.canal.dto.TelefoneAutorizado;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve o número de origem da mensagem para o usuário/tenant autorizado.
 *
 * <p>Usa JDBC puro via o {@link DataSource} injetado diretamente (não um repository
 * JPA), de propósito: com {@code spring.jpa.open-in-view=true} (default do projeto), o
 * EntityManager fica vinculado à thread pelo OSIV durante toda a requisição HTTP, e sua
 * conexão física só é obtida uma vez — mesmo {@code Propagation.REQUIRES_NEW} não força
 * uma conexão nova nesse cenário (Spring reutiliza o EntityManager já vinculado à
 * thread independente da propagação declarada, comportamento confirmado testando
 * manualmente esta fase). Como {@link TenantAwareDataSource} só reaplica
 * {@code app.is_admin}/{@code app.current_tenant_id} dentro de {@code getConnection()},
 * qualquer caminho que passe pelo EntityManager do OSIV correria o risco de herdar a
 * sessão Postgres de uma chamada anterior na mesma requisição. Chamar
 * {@code dataSource.getConnection()} diretamente (bypassando JPA/Hibernate/
 * TransactionSynchronizationManager por completo) garante um checkout de conexão
 * genuinamente novo, que o coordenador (WhatsappWebhookService) controla lendo o
 * TenantContext correto (modo admin) no momento exato desta chamada.</p>
 */
@Service
public class IdentificacaoWhatsappService {

    private static final String SQL = """
            SELECT usuario_id, tenant_id FROM usuario_telefone_autorizado
            WHERE numero_whatsapp = ? AND ativo = true
            """;

    private final DataSource dataSource;

    public IdentificacaoWhatsappService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // "from" da Meta chega sempre só em dígitos, sem "+" (ex.: "5511999990000"), e
    // usuario_telefone_autorizado.numero_whatsapp agora é armazenado no mesmo formato
    // (ver UsuarioTelefoneAutorizado.normalizarNumero) — a normalização aqui é
    // defensiva (cobre um "+" residual digitado manualmente, ex. via
    // /dev/whatsapp/simular), não estritamente necessária no caminho real da Meta.
    public Optional<TelefoneAutorizado> buscarPorNumero(String numeroWhatsapp) {
        String normalizado = UsuarioTelefoneAutorizado.normalizarNumero(numeroWhatsapp);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1, normalizado);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID usuarioId = (UUID) rs.getObject("usuario_id");
                UUID tenantId = (UUID) rs.getObject("tenant_id");
                return Optional.of(new TelefoneAutorizado(usuarioId, tenantId));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao buscar telefone autorizado do webhook WhatsApp", e);
        }
    }
}
