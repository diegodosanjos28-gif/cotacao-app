package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.ConcentracaoFornecedorProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.ConcentracaoFornecedoresResponse;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Painel "Concentração de Fornecedores" (Dashboard) — Top N fornecedores por
// participação no valor "vencido" (mesma base recomendado = menor_preço*quantidade
// de EconomiaResumoService) dentro de uma competência (mês).
@Service
public class ConcentracaoFornecedorService {

    private static final int TOP_N = 5;
    // Limite de dependência sugerido — configurável aqui (mesmo padrão de todo
    // threshold de negócio do backend, ver ex. ClassificacaoConferenciaService).
    // Ecoado em ConcentracaoFornecedoresResponse.limiteDependenciaPct pro frontend
    // nunca hardcodar o número.
    private static final int LIMITE_DEPENDENCIA_PCT = 40;

    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final FornecedorRepository fornecedorRepository;

    public ConcentracaoFornecedorService(CotacaoProdutoFornecedorRepository cpfRepository,
                                          FornecedorRepository fornecedorRepository) {
        this.cpfRepository = cpfRepository;
        this.fornecedorRepository = fornecedorRepository;
    }

    @Transactional(readOnly = true)
    public ConcentracaoFornecedoresResponse topConcentracao(YearMonth mes) {
        OffsetDateTime inicio = mes.atDay(1).atStartOfDay(VariacaoPrecoService.FUSO_COMPETENCIA).toOffsetDateTime();
        OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(VariacaoPrecoService.FUSO_COMPETENCIA).toOffsetDateTime();

        List<ConcentracaoFornecedorProjecao> linhas = cpfRepository.buscarTopConcentracao(inicio, fim, TOP_N);

        Set<UUID> fornecedorIds = linhas.stream().map(ConcentracaoFornecedorProjecao::getFornecedorId)
                .collect(Collectors.toSet());
        Map<UUID, Fornecedor> fornecedores = new HashMap<>();
        if (!fornecedorIds.isEmpty()) {
            fornecedorRepository.findAllById(fornecedorIds).forEach(f -> fornecedores.put(f.getId(), f));
        }

        List<ConcentracaoFornecedoresResponse.ItemConcentracao> itens = linhas.stream()
                .map(p -> {
                    boolean acimaDoLimite = p.getSharePct().compareTo(BigDecimal.valueOf(LIMITE_DEPENDENCIA_PCT)) >= 0;
                    return new ConcentracaoFornecedoresResponse.ItemConcentracao(
                            p.getFornecedorId(),
                            nomeFornecedor(fornecedores, p.getFornecedorId()),
                            p.getValorComprado(),
                            p.getSharePct(),
                            acimaDoLimite);
                })
                .toList();

        // Lista já vem ordenada por sharePct DESC (ver buscarTopConcentracao) — se o
        // líder (posição 0) não está acima do limite, nenhum dos demais está,
        // dispensando uma segunda query só pra essa checagem.
        boolean algumEmRisco = !itens.isEmpty() && itens.get(0).acimaDoLimite();

        return new ConcentracaoFornecedoresResponse(itens, LIMITE_DEPENDENCIA_PCT, algumEmRisco);
    }

    // Mesmo critério de EconomiaResumoService/ComparativoService: fornecedor INATIVO
    // não é resolvido pelo nome, cai pro UUID como string.
    private String nomeFornecedor(Map<UUID, Fornecedor> fornecedores, UUID id) {
        Fornecedor f = fornecedores.get(id);
        if (f == null || f.getStatus() == FornecedorStatus.INATIVO) return id.toString();
        return f.getNome();
    }
}
