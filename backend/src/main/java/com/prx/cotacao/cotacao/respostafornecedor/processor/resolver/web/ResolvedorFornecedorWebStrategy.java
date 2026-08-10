package com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.web;

import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ParametroResolucaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ResolvedorFornecedorRespostaStrategy;
import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;

/**
 * Resolução de fornecedor pro canal Web (Prompt 15) — o operador já escolheu o
 * fornecedor num dropdown antes de colar a resposta, então este passo só VALIDA que
 * ele existe e já foi adicionado a esta cotação (nunca cria nada, diferente do
 * equivalente WhatsApp, {@code ResolvedorFornecedorWhatsappStrategy}, que casa por
 * similaridade de nome e pode criar um fornecedor novo). Despachado dinamicamente por
 * {@link RespostaFornecedorCoreService#processar} via {@link ResolvedorFornecedorRespostaStrategy}.
 */
@Service
public class ResolvedorFornecedorWebStrategy implements ResolvedorFornecedorRespostaStrategy {

    private final FornecedorRepository fornecedorRepository;
    private final CotacaoFornecedorRepository cotacaoFornecedorRepository;

    public ResolvedorFornecedorWebStrategy(FornecedorRepository fornecedorRepository,
                                            CotacaoFornecedorRepository cotacaoFornecedorRepository) {
        this.fornecedorRepository = fornecedorRepository;
        this.cotacaoFornecedorRepository = cotacaoFornecedorRepository;
    }

    @Override
    public UUID resolver(UUID cotacaoId, ParametroResolucaoFornecedor parametro) {
        UUID fornecedorIdSelecionado = ((ParametroResolucaoWeb) parametro).fornecedorIdSelecionado();

        fornecedorRepository.findById(fornecedorIdSelecionado)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + fornecedorIdSelecionado));

        // O fornecedor precisa já ter sido adicionado a esta cotação (fluxo sequencial,
        // épico B1) antes de processar uma resposta para ele.
        cotacaoFornecedorRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorIdSelecionado)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Fornecedor " + fornecedorIdSelecionado + " não foi adicionado à cotação " + cotacaoId));

        return fornecedorIdSelecionado;
    }

    @Override
    public Class<? extends ParametroResolucaoFornecedor> tipoAceito() {
        return ParametroResolucaoWeb.class;
    }
}
