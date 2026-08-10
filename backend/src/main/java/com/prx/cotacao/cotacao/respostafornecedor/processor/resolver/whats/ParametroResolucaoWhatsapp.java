package com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats;

import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ParametroResolucaoFornecedor;

/**
 * Parâmetro do canal WhatsApp: nome do fornecedor extraído da 1ª linha da mensagem,
 * ainda não casado com nenhum {@code Fornecedor} — ver {@link ResolvedorFornecedorWhatsappStrategy}.
 */
public class ParametroResolucaoWhatsapp extends ParametroResolucaoFornecedor {

    private final String nomeFornecedorExtraido;

    public ParametroResolucaoWhatsapp(String nomeFornecedorExtraido) {
        this.nomeFornecedorExtraido = nomeFornecedorExtraido;
    }

    public String nomeFornecedorExtraido() {
        return nomeFornecedorExtraido;
    }
}
