package com.prx.cotacao.whatsapp.template.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code nomeTemplateMeta}/{@code idioma} são campos legados do envio por Message
 * Template (Prompt 18/19) — desde o Prompt 20 (Service Message em texto livre,
 * ver {@code WhatsappTextoLivreMensageriaService}) nenhum código de produção os lê pra
 * montar a chamada real à Meta. Mantidos nullable (não removidos): dado histórico,
 * estrutura reversível pra um eventual fluxo proativo futuro fora da janela de 24h, sem
 * caso de uso hoje (decisão registrada na doc técnica, seção 10.7).
 */
@Entity
@Table(name = "template_mensagem")
public class TemplateMensagem extends TenantAuditEntity {

    // Vaga imutável após criado — aponta pra uma linha de acao_cliente (ação ×
    // resultado, ver notificacao.acaocliente.entity.AcaoCliente). Não é uma
    // relação JPA (@ManyToOne) de propósito: mesmo padrão de tenant_id nesta mesma
    // entidade e de cotacaoId em CotacaoProduto — FK como UUID cru, resolvida via
    // repository separado quando necessário.
    @Column(name = "acao_cliente_id", nullable = false)
    private UUID acaoClienteId;

    @Column(name = "nome_template_meta")
    private String nomeTemplateMeta;

    @Column
    private String idioma;

    // Texto REALMENTE enviado (Prompt 20) — {{identificador}} é substituído pelo valor
    // real em WhatsappTextoLivreMensageriaService antes do envio.
    @Column(columnDefinition = "TEXT")
    private String conteudo;

    @Column(name = "descricao_parametros", columnDefinition = "TEXT")
    private String descricaoParametros;

    @Column(nullable = false)
    private boolean ativo = true;

    public UUID getAcaoClienteId() { return acaoClienteId; }
    public void setAcaoClienteId(UUID acaoClienteId) { this.acaoClienteId = acaoClienteId; }

    public String getNomeTemplateMeta() { return nomeTemplateMeta; }
    public void setNomeTemplateMeta(String nomeTemplateMeta) { this.nomeTemplateMeta = nomeTemplateMeta; }

    public String getIdioma() { return idioma; }
    public void setIdioma(String idioma) { this.idioma = idioma; }

    public String getConteudo() { return conteudo; }
    public void setConteudo(String conteudo) { this.conteudo = conteudo; }

    public String getDescricaoParametros() { return descricaoParametros; }
    public void setDescricaoParametros(String descricaoParametros) { this.descricaoParametros = descricaoParametros; }

    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }
}
