package com.prx.cotacao.notificacao.acaocliente;

import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Semeia o catálogo global {@code acao_cliente} no boot, a partir do enum Java
 * {@link AcaoClienteEnum} — a fonte da verdade de quais linhas devem existir é o enum, não
 * SQL (nenhuma migration faz INSERT nesta tabela, ver {@code V29__create_acao_cliente.sql}).
 * Mesmo padrão de {@code com.prx.cotacao.identidade.AdminBootstrapRunner}:
 * {@code SmartInitializingSingleton} (não {@code ApplicationRunner}/
 * {@code CommandLineRunner} — roda antes do Tomcat embutido abrir os connectors,
 * garantindo que o seed exista antes de qualquer request real), idempotente via
 * {@code existsBy...} no início de cada inserção, sem gate de {@code @Profile}.
 *
 * <p>Depois de semear, faz também o backfill único de {@code template_mensagem}
 * legado (templates configurados antes desta mudança, com {@code resultado}
 * preenchido mas {@code acao_cliente_id} nulo) — precisa rodar DEPOIS do seed, na
 * MESMA {@code afterSingletonsInstantiated()}, porque uma migration SQL não pode
 * referenciar linhas de {@code acao_cliente} que só existem depois deste runner
 * (Flyway roda antes de qualquer bean Spring ser instanciado).</p>
 */
@Component
public class AcaoClienteSetupRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(AcaoClienteSetupRunner.class);

    private static final Map<AcaoClienteEnum, String> DESCRICAO = Map.of(
            AcaoClienteEnum.INSERIR_PRODUTOS, "Cliente enviou uma lista de produtos por WhatsApp.",
            AcaoClienteEnum.REGISTRAR_RESPOSTA, "Cliente enviou a resposta de um fornecedor por WhatsApp.",
            AcaoClienteEnum.NAO_IDENTIFICADO, "Fallback universal — mensagem de formato não reconhecido, "
                    + "ou qualquer ação/resultado sem template específico configurado.");

    private final AcaoClienteCenarioRepository cenarioRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AcaoClienteSetupRunner(AcaoClienteCenarioRepository cenarioRepository, JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate) {
        this.cenarioRepository = cenarioRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        TenantContext.setAdmin(true);
        try {
            seedCenarios();
            backfillTemplatesLegados();
        } finally {
            TenantContext.clear();
        }
    }

    // Itera o enum Java (fonte da verdade), não uma lista fixa de INSERTs em SQL —
    // adicionar um novo valor a AcaoClienteEnum no futuro basta pra esse cenário passar a
    // existir no próximo boot, sem migration nova.
    private void seedCenarios() {
        for (AcaoClienteEnum acao : AcaoClienteEnum.values()) {
            if (acao == AcaoClienteEnum.NAO_IDENTIFICADO) {
                criarSeNaoExiste(acao, null);
                continue;
            }
            for (ResultadoAcaoCliente resultado : ResultadoAcaoCliente.values()) {
                criarSeNaoExiste(acao, resultado);
            }
        }
    }

    private void criarSeNaoExiste(AcaoClienteEnum acao, ResultadoAcaoCliente resultado) {
        boolean existe = resultado == null
                ? cenarioRepository.existsByAcaoAndResultadoIsNull(acao)
                : cenarioRepository.existsByAcaoAndResultado(acao, resultado);
        if (existe) {
            return;
        }
        try {
            AcaoCliente cenario = new AcaoCliente();
            cenario.setAcao(acao);
            cenario.setResultado(resultado);
            cenario.setDescricao(DESCRICAO.get(acao));
            cenarioRepository.save(cenario);
            log.info("Cenário acao_cliente criado: acao={}, resultado={}", acao, resultado);
        } catch (DataIntegrityViolationException e) {
            // Corrida entre 2 instâncias subindo ao mesmo tempo (deploy rolling) — a
            // outra venceu, os índices únicos parciais de V29 pegaram a duplicata.
            log.info("Cenário acao_cliente já criado por outra instância: acao={}, resultado={}", acao, resultado);
        }
    }

    // Idempotente por construção: depois da 1ª execução bem-sucedida, o SELECT abaixo
    // sempre retorna vazio — TemplateMensagemAdminService.criar() sempre preenche
    // acao_cliente_id em templates novos daqui em diante, não existe mais nenhum
    // caminho de código que crie uma linha com acao_cliente_id NULL.
    //
    // JdbcTemplate puro (não JPA): TemplateMensagem não mapeia mais a coluna
    // `resultado` (removida da entidade) — é a única fonte pra distinguir qual linha
    // legada era ERRO/SUCESSO, só legível via SQL cru.
    private void backfillTemplatesLegados() {
        AcaoCliente naoIdentificado = cenarioRepository.findByAcaoAndResultadoIsNull(AcaoClienteEnum.NAO_IDENTIFICADO)
                .orElseThrow(() -> new IllegalStateException(
                        "acao_cliente.NAO_IDENTIFICADO deveria existir — seedCenarios() roda antes, "
                                + "na mesma afterSingletonsInstantiated()."));

        List<Map<String, Object>> legados = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, resultado, nome_template_meta, idioma, conteudo, descricao_parametros "
                        + "FROM template_mensagem WHERE acao_cliente_id IS NULL");
        if (legados.isEmpty()) {
            return;
        }

        Map<UUID, List<Map<String, Object>>> porTenant = legados.stream()
                .collect(Collectors.groupingBy(r -> (UUID) r.get("tenant_id")));

        for (Map.Entry<UUID, List<Map<String, Object>>> entry : porTenant.entrySet()) {
            // UPDATE (promover) + INSERT (backup) + DELETE de um mesmo tenant precisam
            // ser atômicos (achado de security-reviewer): sem transação, um restart do
            // processo entre o UPDATE e o DELETE deixaria a linha de SUCESSO órfã
            // (resultado ainda preenchido, mas a de ERRO já não bate mais no WHERE
            // acao_cliente_id IS NULL do próximo boot) tentando ser promovida de novo
            // pro MESMO acao_cliente_id já ocupado — violando
            // uq_template_mensagem_tenant_acao_cliente e derrubando o boot seguinte.
            transactionTemplate.executeWithoutResult(status -> backfillTenant(naoIdentificado.getId(), entry.getKey(), entry.getValue()));
        }
    }

    private void backfillTenant(UUID naoIdentificadoId, UUID tenantId, List<Map<String, Object>> linhas) {
        Map<String, Object> erro = acharPorResultado(linhas, "ERRO");
        Map<String, Object> sucesso = acharPorResultado(linhas, "SUCESSO");

        // Prioriza ERRO (decisão de produto); se só existir SUCESSO, promove ele em vez
        // de descartar — nunca apaga o único template configurado de um tenant, mesmo
        // que o schema legado nunca tenha exigido o par.
        Map<String, Object> promovido = erro != null ? erro : sucesso;
        if (promovido != null) {
            jdbcTemplate.update("UPDATE template_mensagem SET acao_cliente_id = ? WHERE id = ?",
                    naoIdentificadoId, promovido.get("id"));
            log.info("Template legado promovido a NAO_IDENTIFICADO: tenantId={}, templateId={}",
                    tenantId, promovido.get("id"));
        }

        // Só descarta o conteúdo de SUCESSO quando as duas linhas coexistiam —
        // exatamente o caso confirmado com o usuário (conteúdo de ERRO vence). Grava
        // uma cópia durável em template_mensagem_legado_backup ANTES do DELETE (achado
        // de security-reviewer): é conteúdo de mensagem real já enviada a clientes
        // finais, recuperação manual não pode depender só de log/retenção.
        if (erro != null && sucesso != null) {
            jdbcTemplate.update(
                    "INSERT INTO template_mensagem_legado_backup "
                            + "(template_mensagem_id, tenant_id, resultado, nome_template_meta, idioma, conteudo, descricao_parametros) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    sucesso.get("id"), tenantId, sucesso.get("resultado"), sucesso.get("nome_template_meta"),
                    sucesso.get("idioma"), sucesso.get("conteudo"), sucesso.get("descricao_parametros"));
            log.warn("Descartando template legado de SUCESSO (conteúdo de ERRO já promovido, backup em "
                            + "template_mensagem_legado_backup): tenantId={}, templateId={}, nomeTemplateMeta={}",
                    tenantId, sucesso.get("id"), sucesso.get("nome_template_meta"));
            jdbcTemplate.update("DELETE FROM template_mensagem WHERE id = ?", sucesso.get("id"));
        }
    }

    private Map<String, Object> acharPorResultado(List<Map<String, Object>> rows, String resultado) {
        return rows.stream().filter(r -> resultado.equals(r.get("resultado"))).findFirst().orElse(null);
    }
}
