package com.prx.cotacao.cotacao.hall.service;

import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoProjecao;
import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoResponse;
import com.prx.cotacao.cotacao.hall.dto.SeloConfiabilidade;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// Hall dos Fornecedores, modo histórico (landing da Entrada de Dados, refactor) —
// sem cotação em andamento, mostra médias consolidadas de todas as cotações
// FINALIZADA em que cada fornecedor ativo participou. Ver
// CotacaoFornecedorRepository.buscarHistoricoFornecedores pra detalhe da agregação e
// a ressalva sobre tempoRespostaMedioMinutos ser uma aproximação.
@Service
public class HallFornecedoresService {

    private final CotacaoFornecedorRepository cfRepository;

    public HallFornecedoresService(CotacaoFornecedorRepository cfRepository) {
        this.cfRepository = cfRepository;
    }

    @Transactional(readOnly = true)
    public List<FornecedorHistoricoResponse> historico() {
        List<FornecedorHistoricoProjecao> linhas = cfRepository.buscarHistoricoFornecedores();
        return linhas.stream()
                .map(p -> new FornecedorHistoricoResponse(
                        p.getFornecedorId(),
                        p.getNome(),
                        p.getCotacoesParticipadas(),
                        p.getCoberturaMediaPct(),
                        p.getTempoRespostaMedioMinutos(),
                        SeloConfiabilidade.deTempoRespostaMinutos(p.getTempoRespostaMedioMinutos())))
                .toList();
    }
}
