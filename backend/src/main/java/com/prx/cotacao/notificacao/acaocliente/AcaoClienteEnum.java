package com.prx.cotacao.notificacao.acaocliente;

import com.prx.cotacao.shared.setup.AcaoClienteSetupRunner;

/**
 * Ação de negócio que o sistema tomou em resposta a uma mensagem recebida — genérico e
 * canal-agnóstico (ao contrário de {@code EventoWhatsApp}, que é específico da
 * classificação de texto do canal WhatsApp). Fonte da verdade de quais linhas devem
 * existir na tabela {@code acao_cliente} — ver {@link AcaoClienteSetupRunner}.
 */
public enum AcaoClienteEnum {
    INSERIR_PRODUTOS,
    REGISTRAR_RESPOSTA,
    /** Resultado sempre NULL — fallback universal, 1 único registro na tabela. */
    NAO_IDENTIFICADO
}
