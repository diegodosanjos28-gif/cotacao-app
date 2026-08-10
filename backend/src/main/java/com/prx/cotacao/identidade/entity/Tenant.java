package com.prx.cotacao.identidade.entity;

import com.prx.cotacao.identidade.enums.TenantStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "nome_fantasia", nullable = false)
    private String nomeFantasia;

    @Column(name = "razao_social")
    private String razaoSocial;

    @Column(name = "cnpj", unique = true)
    private String cnpj;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status = TenantStatus.TRIAL;

    @Column(name = "plano")
    private String plano;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    public UUID getId() { return id; }
    public String getNomeFantasia() { return nomeFantasia; }
    public void setNomeFantasia(String nomeFantasia) { this.nomeFantasia = nomeFantasia; }
    public String getRazaoSocial() { return razaoSocial; }
    public void setRazaoSocial(String razaoSocial) { this.razaoSocial = razaoSocial; }
    public String getCnpj() { return cnpj; }
    public void setCnpj(String cnpj) { this.cnpj = cnpj; }
    public TenantStatus getStatus() { return status; }
    public void setStatus(TenantStatus status) { this.status = status; }
    public String getPlano() { return plano; }
    public void setPlano(String plano) { this.plano = plano; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
}
