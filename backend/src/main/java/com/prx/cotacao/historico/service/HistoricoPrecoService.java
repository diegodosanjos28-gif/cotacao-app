package com.prx.cotacao.historico.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.historico.dto.HistoricoPrecoContadores;
import com.prx.cotacao.historico.dto.HistoricoPrecoPageResponse;
import com.prx.cotacao.historico.dto.HistoricoPrecoProdutoResponse;
import com.prx.cotacao.historico.dto.PontoReferenciaPrecoResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HistoricoPrecoService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final ProdutoRepository produtoRepository;
    private final FornecedorRepository fornecedorRepository;

    public HistoricoPrecoService(CotacaoRepository cotacaoRepository,
                                  CotacaoProdutoRepository cotacaoProdutoRepository,
                                  CotacaoProdutoFornecedorRepository cpfRepository,
                                  ProdutoRepository produtoRepository,
                                  FornecedorRepository fornecedorRepository) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.produtoRepository = produtoRepository;
        this.fornecedorRepository = fornecedorRepository;
    }

    // Chave de agrupamento: 1 ponto de referência por (produto, cotação finalizada).
    private record Chave(UUID produtoId, UUID cotacaoId) {
    }

    // Paginado por produto (sempre por nome, igual ao sort fixo de antes — ignora
    // qualquer sort vindo do cliente) em vez de pelas cotações finalizadas: elimina o
    // full scan de todo o histórico do tenant a cada carregamento da tela. Os
    // candidatos de preço (cpfRepository.findCandidatosHistoricoPorProdutos) já vêm
    // filtrados no banco (finalizada, OK, com estoque, com preço) e escopados só aos
    // produtoIds desta página — a lógica de "melhor oferta por (produto, cotação)"
    // continua em Java, idêntica à anterior, só que sobre um conjunto bem menor.
    @Transactional(readOnly = true)
    public HistoricoPrecoPageResponse historico(Pageable pageable, String q) {
        Pageable paginavel = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("nome").ascending());
        Page<Produto> paginaProdutos = (q != null && !q.isBlank())
                ? produtoRepository.buscarPorNome(q.trim(), paginavel)
                : produtoRepository.findAll(paginavel);
        List<UUID> produtoIds = paginaProdutos.getContent().stream().map(Produto::getId).toList();

        List<CotacaoProdutoFornecedor> respostas = produtoIds.isEmpty()
                ? List.of()
                : cpfRepository.findCandidatosHistoricoPorProdutos(produtoIds, CotacaoStatus.FINALIZADA, StatusItem.OK);

        Set<UUID> cotacaoProdutoIds = respostas.stream().map(CotacaoProdutoFornecedor::getCotacaoProdutoId)
                .collect(Collectors.toSet());
        Map<UUID, CotacaoProduto> cpPorId = new HashMap<>();
        if (!cotacaoProdutoIds.isEmpty()) {
            cotacaoProdutoRepository.findAllById(cotacaoProdutoIds).forEach(cp -> cpPorId.put(cp.getId(), cp));
        }

        Set<UUID> cotacaoIds = cpPorId.values().stream().map(CotacaoProduto::getCotacaoId).collect(Collectors.toSet());
        Map<UUID, Cotacao> cotacaoPorId = new HashMap<>();
        if (!cotacaoIds.isEmpty()) {
            cotacaoRepository.findAllById(cotacaoIds).forEach(c -> cotacaoPorId.put(c.getId(), c));
        }

        // findAllById, não findByStatusNot(INATIVO) como no ComparativoService: histórico
        // deve continuar mostrando o nome de um fornecedor mesmo que tenha sido
        // inativado depois da cotação em que ofertou.
        Set<UUID> fornecedorIds = respostas.stream().map(CotacaoProdutoFornecedor::getFornecedorId)
                .collect(Collectors.toSet());
        Map<UUID, Fornecedor> fornecedores = new HashMap<>();
        if (!fornecedorIds.isEmpty()) {
            fornecedorRepository.findAllById(fornecedorIds).forEach(f -> fornecedores.put(f.getId(), f));
        }

        // Ponto de referência de (produto, cotação) = menor oferta válida da cotação
        // para aquele produto — mesmo critério de MapaCompraService.ofertaValida()/
        // menorPreco(). Não é necessariamente o que foi de fato comprado (Mapa de
        // Compra pode divergir sob EQUILIBRADA/MELHOR_PRAZO/ajuste manual); é o melhor
        // preço de mercado observado naquela cotação — simplificação deliberada para
        // manter a consulta derivável por join simples, sem reprocessar o snapshot do
        // Mapa (docs técnica §11, nota de implementação).
        Map<Chave, CotacaoProdutoFornecedor> melhorPorProdutoCotacao = new HashMap<>();
        for (CotacaoProdutoFornecedor cpf : respostas) {
            CotacaoProduto cp = cpPorId.get(cpf.getCotacaoProdutoId());
            if (cp == null || cp.getProdutoId() == null) {
                continue;
            }
            Chave chave = new Chave(cp.getProdutoId(), cp.getCotacaoId());
            CotacaoProdutoFornecedor atual = melhorPorProdutoCotacao.get(chave);
            boolean melhor = atual == null
                    || cpf.getPrecoUnitarioCalculado().compareTo(atual.getPrecoUnitarioCalculado()) < 0
                    || (cpf.getPrecoUnitarioCalculado().compareTo(atual.getPrecoUnitarioCalculado()) == 0
                        && cpf.getFornecedorId().toString().compareTo(atual.getFornecedorId().toString()) < 0);
            if (melhor) {
                melhorPorProdutoCotacao.put(chave, cpf);
            }
        }

        Map<UUID, List<PontoReferenciaPrecoResponse>> pontosPorProduto = new HashMap<>();
        for (Map.Entry<Chave, CotacaoProdutoFornecedor> entry : melhorPorProdutoCotacao.entrySet()) {
            UUID produtoId = entry.getKey().produtoId();
            UUID cotacaoId = entry.getKey().cotacaoId();
            CotacaoProdutoFornecedor cpf = entry.getValue();
            Cotacao cotacao = cotacaoPorId.get(cotacaoId);
            Fornecedor f = fornecedores.get(cpf.getFornecedorId());
            String nomeForn = f != null ? f.getNome() : cpf.getFornecedorId().toString();
            pontosPorProduto.computeIfAbsent(produtoId, k -> new ArrayList<>())
                    .add(new PontoReferenciaPrecoResponse(
                            cotacaoId, cotacao.getFinalizadaEm(), cpf.getPrecoUnitarioCalculado(),
                            cpf.getFornecedorId(), nomeForn));
        }
        pontosPorProduto.values().forEach(lista ->
                lista.sort(Comparator.comparing(PontoReferenciaPrecoResponse::finalizadaEm).reversed()));

        List<HistoricoPrecoProdutoResponse> resultado = new ArrayList<>();
        for (Produto p : paginaProdutos.getContent()) {
            List<PontoReferenciaPrecoResponse> pontos = pontosPorProduto.getOrDefault(p.getId(), List.of());
            BigDecimal quantidadeRef = null;
            String unidadeRef = null;
            if (!pontos.isEmpty()) {
                CotacaoProdutoFornecedor maisRecente = melhorPorProdutoCotacao.get(new Chave(p.getId(), pontos.get(0).cotacaoId()));
                CotacaoProduto cp = cpPorId.get(maisRecente.getCotacaoProdutoId());
                quantidadeRef = cp.getQuantidade();
                unidadeRef = cp.getUnidade();
            }
            resultado.add(new HistoricoPrecoProdutoResponse(p.getId(), p.getNome(), quantidadeRef, unidadeRef, pontos));
        }

        Page<HistoricoPrecoProdutoResponse> pagina =
                new PageImpl<>(resultado, paginavel, paginaProdutos.getTotalElements());

        HistoricoPrecoContadores contadores = cpfRepository.contarKpisHistorico();
        return new HistoricoPrecoPageResponse(
                pagina, contadores.getComHistorico(), contadores.getAcima(), contadores.getOportunidade());
    }
}
