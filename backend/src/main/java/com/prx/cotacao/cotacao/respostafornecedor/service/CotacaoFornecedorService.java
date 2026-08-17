package com.prx.cotacao.cotacao.respostafornecedor.service;

import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.CotacaoFornecedorResponse;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.service.FornecedorService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;

@Service
public class CotacaoFornecedorService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoFornecedorRepository cfRepository;
    private final FornecedorRepository fornecedorRepository;
    private final FornecedorService fornecedorService;

    public CotacaoFornecedorService(CotacaoRepository cotacaoRepository,
                                     CotacaoFornecedorRepository cfRepository,
                                     FornecedorRepository fornecedorRepository,
                                     FornecedorService fornecedorService) {
        this.cotacaoRepository = cotacaoRepository;
        this.cfRepository = cfRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.fornecedorService = fornecedorService;
    }

    public List<CotacaoFornecedorResponse> listar(UUID cotacaoId) {
        cotacaoRepository.findByIdOrThrow(cotacaoId);
        List<CotacaoFornecedor> itens = cfRepository.findByCotacaoIdOrderByOrdem(cotacaoId);
        Map<UUID, String> nomesPorFornecedor = fornecedorRepository
                .findAllById(itens.stream().map(CotacaoFornecedor::getFornecedorId).toList())
                .stream()
                .collect(Collectors.toMap(Fornecedor::getId, Fornecedor::getNome));
        return itens.stream()
                .map(cf -> CotacaoFornecedorResponse.from(cf, nomesPorFornecedor.get(cf.getFornecedorId())))
                .toList();
    }

    @Transactional
    public CotacaoFornecedorResponse adicionar(UUID cotacaoId, AdicionarFornecedorCotacaoRequest request) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita novos fornecedores");
        }

        boolean temExistente = request.fornecedorId() != null;
        boolean temNovo = request.novoFornecedor() != null;
        if (temExistente == temNovo) {
            throw new IllegalArgumentException("Informe exatamente um entre fornecedorId e novoFornecedor.");
        }

        // Gate do fluxo sequencial (regra 1.1): só libera "+ Adicionar Fornecedor"
        // depois que o último fornecedor adicionado a esta cotação foi confirmado
        // na Conferência. Enforcement aqui é o backstop server-side do disable
        // client-side do botão.
        CotacaoFornecedor ultimo = cfRepository.findTopByCotacaoIdOrderByOrdemDesc(cotacaoId).orElse(null);
        if (ultimo != null && ultimo.getStatus() != CotacaoFornecedorStatus.CONFIRMADO) {
            throw new ConflictException(
                    "Processe e confirme a cotação do fornecedor atual antes de adicionar outro.");
        }

        Fornecedor fornecedor;
        if (temNovo) {
            fornecedor = fornecedorService.criar(request.novoFornecedor());
        } else {
            fornecedor = fornecedorRepository.findById(request.fornecedorId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Fornecedor não encontrado: " + request.fornecedorId()));
        }

        if (cfRepository.existsByCotacaoIdAndFornecedorId(cotacaoId, fornecedor.getId())) {
            throw new ConflictException("Fornecedor já adicionado a esta cotação.");
        }

        CotacaoFornecedor cf = new CotacaoFornecedor();
        cf.setCotacaoId(cotacaoId);
        cf.setFornecedorId(fornecedor.getId());
        cf.setOrdem(ultimo != null ? ultimo.getOrdem() + 1 : 1);
        cf.setStatus(CotacaoFornecedorStatus.PENDENTE);
        CotacaoFornecedor salvo = cfRepository.save(cf);

        return CotacaoFornecedorResponse.from(salvo, fornecedor.getNome());
    }
}
