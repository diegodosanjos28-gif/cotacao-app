package com.prx.cotacao.cotacao.respostafornecedor.processor.resolver;

import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;

/**
 * Parâmetro de entrada para {@link RespostaFornecedorCoreService#processar} — identifica
 * qual {@link ResolvedorFornecedorRespostaStrategy} resolve o fornecedor e carrega os
 * dados que essa resolução precisa. Uma subclasse por canal
 * ({@code ParametroResolucaoWeb}, {@code ParametroResolucaoWhatsapp}) — o dispatch em
 * {@link RespostaFornecedorCoreService} é feito pela classe concreta (via
 * {@link ResolvedorFornecedorRespostaStrategy#tipoAceito()}), não por um campo
 * discriminador aqui.
 */
public abstract class ParametroResolucaoFornecedor {
}
