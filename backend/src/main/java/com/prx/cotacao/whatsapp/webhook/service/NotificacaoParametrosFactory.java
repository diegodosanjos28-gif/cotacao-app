package com.prx.cotacao.whatsapp.webhook.service;

import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoLista;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoResposta;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Monta os parâmetros dinâmicos disponíveis para cada um dos 5 cenários de confirmação
 * do webhook WhatsApp — usado só por {@link WhatsappWebhookService}, então sem
 * interface/DI dinâmica (YAGNI: o chamador já sabe exatamente qual método invocar em
 * cada ramo). As chaves retornadas correspondem aos identificadores do catálogo
 * {@code CatalogoParametrosNotificacao} (genérico {@code tipoMensagem}/{@code detalhe}
 * sempre presentes; chaves específicas de evento só nos 2 cenários de SUCESSO, que têm
 * dado de negócio confiável disponível — ERRO fica só com o genérico, decisão do
 * Prompt 19, dado que a falha acontece antes de identificar produto/fornecedor).
 */
@Component
public class NotificacaoParametrosFactory {

    public Map<String, String> paraListaSucesso(ResultadoProcessamentoLista resultado) {
        String detalhe = resultado.totalItens() + " itens adicionados (" + resultado.itensReconhecidos() + " reconhecidos)";
        return Map.of(
                "tipoMensagem", "Lista de produtos",
                "detalhe", detalhe,
                "totalItens", String.valueOf(resultado.totalItens()),
                "itensReconhecidos", String.valueOf(resultado.itensReconhecidos()),
                "cotacaoTitulo", resultado.cotacaoTitulo() != null ? resultado.cotacaoTitulo() : "");
    }

    public Map<String, String> paraListaErro() {
        return Map.of("tipoMensagem", "Lista de produtos",
                "detalhe", "Não foi possível processar sua lista. Um operador vai verificar em breve.");
    }

    public Map<String, String> paraRespostaSucesso(ResultadoProcessamentoResposta resultado) {
        String detalhe = "Fornecedor " + resultado.nomeFornecedor() + ", " + resultado.totalItens()
                + " itens — aguardando conferência do operador.";
        return Map.of(
                "tipoMensagem", "Resposta de fornecedor",
                "detalhe", detalhe,
                "nomeFornecedor", resultado.nomeFornecedor(),
                "totalItens", String.valueOf(resultado.totalItens()),
                "cotacaoTitulo", resultado.cotacaoTitulo() != null ? resultado.cotacaoTitulo() : "");
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
