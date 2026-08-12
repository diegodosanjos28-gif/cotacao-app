package com.prx.cotacao.notificacao;

/**
 * Porta de notificação — decide-se "preciso confirmar sucesso/erro" aqui, sem
 * conhecer o mecanismo concreto de envio (hoje WhatsApp Template API; poderia ser
 * e-mail, SMS, etc. no futuro sem alterar quem chama esta interface).
 *
 * <p>Implementações NUNCA propagam exceção para o chamador — falha no envio da
 * notificação não pode derrubar o fluxo de negócio que a disparou.</p>
 */
public interface MensageriaService {

    void enviarMensagemSucesso(ContextoNotificacao contexto);

    void enviarMensagemErro(ContextoNotificacao contexto);
}
