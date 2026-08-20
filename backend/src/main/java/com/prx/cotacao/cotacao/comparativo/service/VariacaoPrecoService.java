package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.comparativo.dto.VariacaoPrecoProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.VariacaoPrecoResponse;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Painel "Variação de Preço por Produto" (Dashboard) — Top N produtos por spread de
// preço dentro de uma competência (mês). Mesma base de "ofertas válidas" de
// EconomiaResumoService (status OK, sem_estoque=false, cp.removido_em IS NULL,
// cotação FINALIZADA) — não inventa outro critério de validade.
@Service
public class VariacaoPrecoService {

    private static final int TOP_N = 5;

    // Fuso do limite de competência (início/fim do mês) — a operação é 100% Brasil e
    // não existia precedente de ZoneId no backend até esta feature. UTC puro
    // deslocaria a virada do mês em até 3h pro operador (ex.: cotação finalizada
    // 31/08 22h BRT = 01/09 01h UTC cairia no mês errado se comparada em UTC puro).
    static final ZoneId FUSO_COMPETENCIA = ZoneId.of("America/Sao_Paulo");

    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final ProdutoRepository produtoRepository;
    private final FornecedorRepository fornecedorRepository;

    public VariacaoPrecoService(CotacaoProdutoFornecedorRepository cpfRepository,
                                 ProdutoRepository produtoRepository,
                                 FornecedorRepository fornecedorRepository) {
        this.cpfRepository = cpfRepository;
        this.produtoRepository = produtoRepository;
        this.fornecedorRepository = fornecedorRepository;
    }

    @Transactional(readOnly = true)
    public VariacaoPrecoResponse topSpread(YearMonth mes) {
        OffsetDateTime inicio = mes.atDay(1).atStartOfDay(FUSO_COMPETENCIA).toOffsetDateTime();
        OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(FUSO_COMPETENCIA).toOffsetDateTime();

        List<VariacaoPrecoProjecao> linhas = cpfRepository.buscarTopSpread(inicio, fim, TOP_N);

        Set<UUID> produtoIds = new HashSet<>();
        Set<UUID> fornecedorIds = new HashSet<>();
        for (VariacaoPrecoProjecao p : linhas) {
            produtoIds.add(p.getProdutoId());
            fornecedorIds.add(p.getFornecedorMenorId());
            fornecedorIds.add(p.getFornecedorMaiorId());
        }

        Map<UUID, String> nomesProduto = new HashMap<>();
        if (!produtoIds.isEmpty()) {
            produtoRepository.findAllById(produtoIds).forEach(p -> nomesProduto.put(p.getId(), p.getNome()));
        }
        Map<UUID, Fornecedor> fornecedores = new HashMap<>();
        if (!fornecedorIds.isEmpty()) {
            fornecedorRepository.findAllById(fornecedorIds).forEach(f -> fornecedores.put(f.getId(), f));
        }

        List<VariacaoPrecoResponse.ItemVariacao> itens = linhas.stream()
                .map(p -> new VariacaoPrecoResponse.ItemVariacao(
                        p.getProdutoId(),
                        nomesProduto.getOrDefault(p.getProdutoId(), p.getProdutoId().toString()),
                        p.getMenorPreco(),
                        p.getMaiorPreco(),
                        p.getSpreadPct(),
                        nomeFornecedor(fornecedores, p.getFornecedorMenorId()),
                        nomeFornecedor(fornecedores, p.getFornecedorMaiorId())))
                .toList();

        long totalElegiveis = linhas.isEmpty() ? 0 : linhas.get(0).getTotalElegiveis();
        return new VariacaoPrecoResponse(itens, (int) totalElegiveis);
    }

    // Mesmo critério de EconomiaResumoService/ComparativoService: fornecedor INATIVO
    // não é resolvido pelo nome, cai pro UUID como string.
    private String nomeFornecedor(Map<UUID, Fornecedor> fornecedores, UUID id) {
        Fornecedor f = fornecedores.get(id);
        if (f == null || f.getStatus() == FornecedorStatus.INATIVO) return id.toString();
        return f.getNome();
    }
}
