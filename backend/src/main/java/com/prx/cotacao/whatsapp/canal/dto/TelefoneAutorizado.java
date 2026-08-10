package com.prx.cotacao.whatsapp.canal.dto;

import com.prx.cotacao.whatsapp.canal.service.IdentificacaoWhatsappService;

import java.util.UUID;

/** Resultado mínimo da identificação de remetente — não a entidade JPA completa (ver {@link IdentificacaoWhatsappService}). */
public record TelefoneAutorizado(UUID usuarioId, UUID tenantId) {}
