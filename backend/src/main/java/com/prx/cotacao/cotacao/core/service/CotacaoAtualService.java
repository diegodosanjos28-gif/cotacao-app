package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.cotacao.core.dto.CotacaoAtualResponse;
import com.prx.cotacao.cotacao.core.dto.FornecedorRespostaResumo;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// Card "Cotação atual" da landing da Entrada de Dados (refactor) — tenant-wide: a
// cotação RASCUNHO/EM_ANDAMENTO mais recente do tenant inteiro, não filtrada por
// usuário criador (decisão do refactor: qualquer usuário da loja revisa a mesma
// cotação, ao contrário do roteamento de sessão WhatsApp que é por criadoPor).
@Service
public class CotacaoAtualService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final CotacaoFornecedorRepository cfRepository;
    private final FornecedorRepository fornecedorRepository;

    public CotacaoAtualService(CotacaoRepository cotacaoRepository,
                                CotacaoProdutoRepository cotacaoProdutoRepository,
                                CotacaoProdutoFornecedorRepository cpfRepository,
                                CotacaoFornecedorRepository cfRepository,
                                FornecedorRepository fornecedorRepository) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.cfRepository = cfRepository;
        this.fornecedorRepository = fornecedorRepository;
    }

    @Transactional(readOnly = true)
    public Optional<CotacaoAtualResponse> buscar() {
        Optional<Cotacao> cotacaoOpt = cotacaoRepository.findFirstByStatusInOrderByUltimaAtividadeEmDesc(
                List.of(CotacaoStatus.RASCUNHO, CotacaoStatus.EM_ANDAMENTO));
        if (cotacaoOpt.isEmpty()) {
            return Optional.empty();
        }
        Cotacao cotacao = cotacaoOpt.get();

        List<CotacaoProduto> itensBase =
                cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacao.getId());
        List<CotacaoProdutoFornecedor> ofertas = cpfRepository.findByCotacaoId(cotacao.getId());

        // itensCotados (nível cotação): itens distintos da lista base com pelo menos
        // uma oferta OK de qualquer fornecedor.
        long itensCotados = ofertas.stream()
                .filter(o -> o.getStatus() == StatusItem.OK)
                .map(CotacaoProdutoFornecedor::getCotacaoProdutoId)
                .distinct()
                .count();

        // itensCotados por fornecedor (Hall modo ativa + avatar strip) — cada
        // fornecedor cota no máximo 1 vez por item (UNIQUE(cotacao_produto_id,
        // fornecedor_id), V7), então count() já é a contagem distinta.
        Map<UUID, Long> itensCotadosPorFornecedor = ofertas.stream()
                .filter(o -> o.getStatus() == StatusItem.OK)
                .collect(Collectors.groupingBy(CotacaoProdutoFornecedor::getFornecedorId, Collectors.counting()));

        List<CotacaoFornecedor> cotacaoFornecedores = cfRepository.findByCotacaoIdOrderByOrdem(cotacao.getId());
        Map<UUID, String> nomesPorFornecedor = fornecedorRepository
                .findAllById(cotacaoFornecedores.stream().map(CotacaoFornecedor::getFornecedorId).toList())
                .stream()
                .collect(Collectors.toMap(Fornecedor::getId, Fornecedor::getNome));

        List<FornecedorRespostaResumo> fornecedores = cotacaoFornecedores.stream()
                .map(cf -> new FornecedorRespostaResumo(
                        cf.getFornecedorId(),
                        nomesPorFornecedor.get(cf.getFornecedorId()),
                        cf.getStatus(),
                        // Aproximação (mesma ressalva de HallFornecedoresService): sem coluna
                        // dedicada de "respondido em", atualizadoEm é o proxy disponível.
                        cf.getStatus() == CotacaoFornecedorStatus.PENDENTE ? null : cf.getAtualizadoEm(),
                        itensCotadosPorFornecedor.getOrDefault(cf.getFornecedorId(), 0L)))
                .toList();

        return Optional.of(new CotacaoAtualResponse(
                cotacao.getId(),
                cotacao.getTitulo(),
                cotacao.getCanalOrigem(),
                cotacao.getUltimaAtividadeEm(),
                cotacao.getCriadoEm(),
                itensBase.size(),
                itensCotados,
                fornecedores));
    }
}
