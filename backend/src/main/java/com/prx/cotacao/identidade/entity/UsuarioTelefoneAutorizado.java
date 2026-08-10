package com.prx.cotacao.identidade.entity;

import com.prx.cotacao.shared.tenant.TenantAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "usuario_telefone_autorizado")
public class UsuarioTelefoneAutorizado extends TenantAuditEntity {

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "numero_whatsapp", nullable = false, unique = true)
    private String numeroWhatsapp;

    @Column(name = "nome_contato")
    private String nomeContato;

    @Column(nullable = false)
    private boolean ativo = true;

    // 55 (Brasil) + DDD (2 dígitos) + "9" (nono dígito de celular) + 8 dígitos do
    // assinante = 13 dígitos, ex. "5547984655980". O campo "from" do webhook da Meta
    // chega SEM esse nono dígito (ex. "554784655980", 12 dígitos) — quirk conhecido da
    // numeração brasileira (a expansão do nono dígito em celulares é posterior ao
    // formato "canônico" que a Cloud API usa). Sem essa normalização, um número
    // cadastrado como digitado no celular (com o 9) nunca bate com o que a Meta manda.
    private static final Pattern NONO_DIGITO_CELULAR_BR = Pattern.compile("^(55\\d{2})9(\\d{8})$");

    public UUID getUsuarioId() { return usuarioId; }
    public void setUsuarioId(UUID usuarioId) { this.usuarioId = usuarioId; }
    public String getNumeroWhatsapp() { return numeroWhatsapp; }
    // Normaliza no próprio setter (não só no service) — garante o invariante "só
    // dígitos, sem nono dígito de celular BR" pra QUALQUER caller que monte esta
    // entidade (service, teste, código futuro), sem depender de disciplina espalhada
    // por vários pontos de chamada.
    public void setNumeroWhatsapp(String v) { this.numeroWhatsapp = normalizarNumero(v); }
    public String getNomeContato() { return nomeContato; }
    public void setNomeContato(String v) { this.nomeContato = v; }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }

    // Fonte única desta regra — usada tanto na gravação (aqui) quanto na leitura do
    // webhook (IdentificacaoWhatsappService), pra garantir que os dois lados SEMPRE
    // normalizem pro mesmo valor, independente de o número ter sido digitado com ou
    // sem "+"/nono dígito.
    public static String normalizarNumero(String v) {
        if (v == null) {
            return null;
        }
        String digitos = v.replaceAll("\\D", "");
        var m = NONO_DIGITO_CELULAR_BR.matcher(digitos);
        return m.matches() ? m.group(1) + m.group(2) : digitos;
    }
}
