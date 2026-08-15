package com.prx.cotacao.whatsapp.canal.dto;

import com.prx.cotacao.whatsapp.canal.service.WhatsappListaProdutosService;

import java.util.UUID;

/** Resultado do processamento de uma lista de produtos vinda por WhatsApp (ver {@link WhatsappListaProdutosService}). */
public record ResultadoProcessamentoLista(UUID cotacaoId, int totalItens, int itensReconhecidos, String cotacaoTitulo) {
}
