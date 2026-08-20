package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoResponse;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EconomiaResumoService {

    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final FornecedorRepository fornecedorRepository;

    public EconomiaResumoService(CotacaoProdutoFornecedorRepository cpfRepository,
                                  FornecedorRepository fornecedorRepository) {
        this.cpfRepository = cpfRepository;
        this.fornecedorRepository = fornecedorRepository;
    }

    @Transactional(readOnly = true)
    public EconomiaResumoResponse resumo() {
        EconomiaResumoProjecao p = cpfRepository.resumoEconomiaFinalizadas();

        String nomeVencedor = null;
        if (p.getFornecedorVencedorId() != null) {
            // Mesma regra de ComparativoService.comparativo(): um fornecedor INATIVO
            // (ou já excluído) não é resolvido pelo nome — cai pro UUID como string.
            // O vencedor em si é decidido só por contagem de itens vencidos, sem
            // considerar status do fornecedor (mesmo critério do frontend).
            nomeVencedor = fornecedorRepository.findById(p.getFornecedorVencedorId())
                    .filter(f -> f.getStatus() != FornecedorStatus.INATIVO)
                    .map(Fornecedor::getNome)
                    .orElseGet(() -> p.getFornecedorVencedorId().toString());
        }

        return new EconomiaResumoResponse(
                p.getCotacoesProcessadas(),
                p.getEconomiaAcumulada(),
                p.getMediaEconomiaPct(),
                nomeVencedor,
                p.getFornecedorVencedorContagem());
    }
}
