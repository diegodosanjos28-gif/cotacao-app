package com.prx.cotacao.cotacao.respostafornecedor.service;

import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.web.ParametroResolucaoWeb;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.web.ResolvedorFornecedorWebStrategy;
import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;

/**
 * Ponto de entrada do canal Web para processar/reabrir a resposta de um fornecedor.
 * {@link #gerarPreview} é um wrapper fino (Prompt 15 — unificação Web/WhatsApp): monta
 * o {@link ParametroResolucaoWeb} e delega todo o resto — resolução do fornecedor via
 * {@link ResolvedorFornecedorWebStrategy} (despachada internamente, não mais chamada
 * daqui) e o pipeline de parsing/matching/classificação/preview — para
 * {@link RespostaFornecedorCoreService}, o mesmo núcleo usado pelo canal WhatsApp.
 */
@Service
public class FornecedorRespostaService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final CotacaoFornecedorRepository cotacaoFornecedorRepository;
    private final FornecedorRepository fornecedorRepository;
    private final RespostaFornecedorCoreService core;

    public FornecedorRespostaService(CotacaoRepository cotacaoRepository,
                                      CotacaoProdutoFornecedorRepository cpfRepository,
                                      CotacaoFornecedorRepository cotacaoFornecedorRepository,
                                      FornecedorRepository fornecedorRepository,
                                      RespostaFornecedorCoreService core) {
        this.cotacaoRepository = cotacaoRepository;
        this.cpfRepository = cpfRepository;
        this.cotacaoFornecedorRepository = cotacaoFornecedorRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.core = core;
    }

    @Transactional
    public PreviewRespostaResponse gerarPreview(UUID cotacaoId, UUID fornecedorId, String texto) {
        return core.processar(cotacaoId, new ParametroResolucaoWeb(fornecedorId), texto);
    }

    /**
     * Reconstrói o texto que o operador colaria de novo pra reabrir a Conferência de
     * uma resposta ainda não confirmada. Prefere {@code CotacaoFornecedor.textoRespostaPendente}
     * (gravado a cada preview por {@link RespostaFornecedorCoreService#processar} —
     * Prompt 15, V27), que cobre os dois canais igualmente. Fallback (respostas
     * confirmadas antes desta migration, campo nunca gravado): junta
     * {@code texto_original} de cada linha já persistida em
     * {@code cotacao_produto_fornecedor}, na ordem da lista base — alimentar esse texto
     * de volta em {@link #gerarPreview} faz o parser/matching/classificação rodarem de
     * novo peça por peça.
     */
    public String textoPersistido(UUID cotacaoId, UUID fornecedorId) {
        cotacaoRepository.findByIdOrThrow(cotacaoId);
        fornecedorRepository.findById(fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + fornecedorId));
        CotacaoFornecedor cf = cotacaoFornecedorRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Fornecedor " + fornecedorId + " não foi adicionado à cotação " + cotacaoId));

        String pendente = cf.getTextoRespostaPendente();
        if (pendente != null && !pendente.isBlank()) {
            return pendente;
        }

        return cpfRepository.findByCotacaoIdAndFornecedorIdOrderByOrdem(cotacaoId, fornecedorId).stream()
                .map(CotacaoProdutoFornecedor::getTextoOriginal)
                .filter(texto -> texto != null && !texto.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * "Cancelar Conferência" (achado do usuário, 2026-08-04): apaga toda resposta já
     * persistida deste fornecedor pra esta cotação e volta {@code CotacaoFornecedor.status}
     * pra {@code PENDENTE} — o fornecedor volta ao estado "ainda não respondeu". Sem
     * isso, cancelar só no navegador (limpar texto/preview locais) não bastava: o botão
     * "Conferir resposta do fornecedor" reconstrói de {@link #textoPersistido}, então a
     * mesma resposta cancelada reapareceria no próximo clique. Não é um soft-delete —
     * a linha é removida de verdade, igual a {@code AvisoService}/demais fluxos deste
     * módulo, que também nunca mantiveram histórico de respostas descartadas. Limpa
     * também {@code textoRespostaPendente} (Prompt 15) — sem isso, cancelar uma
     * resposta ainda não confirmada deixaria o rascunho pendente vivo, e
     * {@link #textoPersistido} o devolveria de novo no próximo clique.
     */
    @Transactional
    public void cancelarResposta(UUID cotacaoId, UUID fornecedorId) {
        cotacaoRepository.findByIdOrThrow(cotacaoId);
        fornecedorRepository.findById(fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + fornecedorId));
        CotacaoFornecedor cf = cotacaoFornecedorRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Fornecedor " + fornecedorId + " não foi adicionado à cotação " + cotacaoId));

        List<CotacaoProdutoFornecedor> respostas = cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId);
        cpfRepository.deleteAll(respostas);

        cf.setStatus(CotacaoFornecedorStatus.PENDENTE);
        cf.setTextoRespostaPendente(null);
        cotacaoFornecedorRepository.save(cf);
    }
}
