package com.prx.cotacao.whatsapp.canal.dto;

import com.prx.cotacao.whatsapp.canal.service.WhatsappRespostaFornecedorService;

import java.util.UUID;

/** Resultado do processamento de uma resposta de fornecedor vinda por WhatsApp (ver {@link WhatsappRespostaFornecedorService}). */
public record ResultadoProcessamentoResposta(UUID cotacaoId, String nomeFornecedor, int totalItens) {
}
