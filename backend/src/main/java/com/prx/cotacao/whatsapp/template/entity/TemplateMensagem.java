package com.prx.cotacao.whatsapp.template.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import com.prx.cotacao.whatsapp.template.ResultadoNotificacao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "template_mensagem")
public class TemplateMensagem extends TenantAuditEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResultadoNotificacao resultado;

    @Column(name = "nome_template_meta", nullable = false)
    private String nomeTemplateMeta;

    @Column(nullable = false)
    private String idioma = "pt_BR";

    @Column(columnDefinition = "TEXT")
    private String conteudo;

    @Column(name = "descricao_parametros", columnDefinition = "TEXT")
    private String descricaoParametros;

    @Column(nullable = false)
    private boolean ativo = true;

    public ResultadoNotificacao getResultado() { return resultado; }
    public void setResultado(ResultadoNotificacao resultado) { this.resultado = resultado; }

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
