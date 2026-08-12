package com.prx.cotacao.whatsapp.webhook.service;

import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoLista;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoResposta;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Monta os parâmetros dinâmicos (chaves {@code tipoMensagem}/{@code detalhe}) para cada
 * um dos 5 cenários de confirmação do webhook WhatsApp — usado só por
 * {@link WhatsappWebhookService}, então sem interface/DI dinâmica (YAGNI: o chamador já
 * sabe exatamente qual método invocar em cada ramo).
 */
@Component
public class NotificacaoParametrosFactory {

    public Map<String, String> paraListaSucesso(ResultadoProcessamentoLista resultado) {
        String detalhe = resultado.totalItens() + " itens adicionados (" + resultado.itensReconhecidos() + " reconhecidos)";
        return Map.of("tipoMensagem", "Lista de produtos", "detalhe", detalhe);
    }

    public Map<String, String> paraListaErro() {
        return Map.of("tipoMensagem", "Lista de produtos",
                "detalhe", "Não foi possível processar sua lista. Um operador vai verificar em breve.");
    }

    public Map<String, String> paraRespostaSucesso(ResultadoProcessamentoResposta resultado) {
        String detalhe = "Fornecedor " + resultado.nomeFornecedor() + ", " + resultado.totalItens()
                + " itens — aguardando conferência do operador.";
        return Map.of("tipoMensagem", "Resposta de fornecedor", "detalhe", detalhe);
    }

    public Map<String, String> paraRespostaErro() {
        return Map.of("tipoMensagem", "Resposta de fornecedor",
                "detalhe", "Não foi possível processar sua resposta. Um operador vai verificar em breve.");
    }

    public Map<String, String> paraFormatoDesconhecido() {
        return Map.of("tipoMensagem", "Desconhecido",
                "detalhe", "Comece a mensagem com LISTA_PRODUTOS ou RESPOSTA_FORNECEDOR na primeira linha.");
    }
}
