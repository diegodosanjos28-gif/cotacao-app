package com.prx.cotacao.cotacao.respostafornecedor.processor.resolver;

import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;

import java.util.UUID;

/**
 * Strategy real de resolução de fornecedor por canal — {@link RespostaFornecedorCoreService}
 * injeta todas as implementações (Spring autodetecta os beans, um por subpacote de
 * canal em {@code processor.strategy}) e despacha pelo tipo de
 * {@link ParametroResolucaoFornecedor} recebido, via {@link #tipoAceito()}. O core
 * nunca precisa conhecer as classes concretas — só esta interface.
 *
 * <p>Um prompt anterior já havia cogitado essa interface e rejeitado deliberadamente
 * (nenhum ponto do sistema precisava escolher em runtime entre as duas resoluções na
 * época). Essa premissa mudou: agora o próprio core é o ponto de despacho.</p>
 */
public interface ResolvedorFornecedorRespostaStrategy {

    UUID resolver(UUID cotacaoId, ParametroResolucaoFornecedor parametro);

    Class<? extends ParametroResolucaoFornecedor> tipoAceito();
}
