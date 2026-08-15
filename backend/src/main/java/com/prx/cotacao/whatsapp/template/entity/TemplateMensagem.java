package com.prx.cotacao.whatsapp.template.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Column(name = "nome_template_meta", nullable = false)
    private String nomeTemplateMeta;

    @Column(nullable = false)
    private String idioma = "pt_BR";

    @Column(columnDefinition = "TEXT")
    private String conteudo;

    @Column(name = "descricao_parametros", columnDefinition = "TEXT")
    private String descricaoParametros;

    // Ordem em que os identificadores do catálogo (CatalogoParametrosNotificacao)
    // preenchem {{1}}, {{2}}, ... no envio real — ver WhatsappTemplateMensageriaService.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "parametros_ordenados", nullable = false, columnDefinition = "text[]")
    private List<String> parametrosOrdenados = new ArrayList<>();

    @Column(nullable = false)
    private boolean ativo = true;

    public UUID getAcaoClienteId() { return acaoClienteId; }
    public void setAcaoClienteId(UUID acaoClienteId) { this.acaoClienteId = acaoClienteId; }

    public List<String> getParametrosOrdenados() { return parametrosOrdenados; }
    public void setParametrosOrdenados(List<String> parametrosOrdenados) { this.parametrosOrdenados = parametrosOrdenados; }

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
