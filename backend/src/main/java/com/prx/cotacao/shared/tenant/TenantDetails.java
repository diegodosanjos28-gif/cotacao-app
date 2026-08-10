package com.prx.cotacao.shared.tenant;

/**
 * Carregado no Authentication.details pelo JwtAuthFilter após validação do token.
 */
public record TenantDetails(String tenantId, String papel) {}
