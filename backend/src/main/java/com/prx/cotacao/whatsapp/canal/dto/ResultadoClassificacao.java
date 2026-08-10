package com.prx.cotacao.whatsapp.canal.dto;

import com.prx.cotacao.whatsapp.canal.enums.TipoMensagemWhatsapp;
import com.prx.cotacao.whatsapp.canal.service.ClassificadorMensagemWhatsapp;

/**
 * Resultado de uma classificação bem-sucedida (ver {@link ClassificadorMensagemWhatsapp}):
 * {@code corpo} é tudo que vem depois da primeira quebra de linha da mensagem (o
 * marcador LISTA_PRODUTOS/RESPOSTA_FORNECEDOR já foi descartado) — vazio se a mensagem
 * não tiver quebra de linha nenhuma.
 */
public record ResultadoClassificacao(TipoMensagemWhatsapp tipo, String corpo) {
}
