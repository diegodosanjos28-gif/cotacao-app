package com.prx.cotacao.identidade.auth.dto;

import java.util.UUID;

// tenantId nulo é um valor válido de propósito: significa "sair do modo navegação,
// voltar ao painel admin puro" — não é um campo opcional por descuido.
public record SelecionarTenantRequest(UUID tenantId) {}
