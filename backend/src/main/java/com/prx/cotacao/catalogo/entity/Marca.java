package com.prx.cotacao.catalogo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Catálogo de marcas conhecidas usado por {@code MatchingProdutoService.extrairMarca}.
 * {@code tenantId == null} representa uma marca default global (visível a todo tenant,
 * seedada em V16); {@code tenantId != null} é uma marca cadastrada pelo próprio tenant.
 *
 * <p>Não estende {@link com.prx.cotacao.shared.tenant.TenantAuditEntity} porque essa
 * superclasse exige {@code tenant_id NOT NULL} e usa um {@code @Filter} de igualdade
 * estrita — incompatível com a visibilidade "global OU do meu tenant" que esta tabela
 * precisa. Isolamento aqui é feito só via RLS na conexão (mesmo padrão de
 * {@code CotacaoProduto}).</p>
 */
@Entity
@Table(name = "marca")
public class Marca {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String nome;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public OffsetDateTime getCriadoEm() {
        return criadoEm;
    }

    public void setCriadoEm(OffsetDateTime criadoEm) {
        this.criadoEm = criadoEm;
    }
}
