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
import com.prx.cotacao.historico.dto.HistoricoPrecoProdutoResponse;
import com.prx.cotacao.historico.dto.PontoReferenciaPrecoResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public List<HistoricoPrecoProdutoResponse> historico() {
        List<Cotacao> finalizadas = cotacaoRepository.findByStatusOrderByFinalizadaEmDesc(CotacaoStatus.FINALIZADA);
        List<UUID> cotacaoIds = finalizadas.stream().map(Cotacao::getId).toList();
        Map<UUID, Cotacao> cotacaoPorId = new HashMap<>();
        finalizadas.forEach(c -> cotacaoPorId.put(c.getId(), c));

        List<CotacaoProduto> itens = cotacaoIds.isEmpty()
                ? List.of() : cotacaoProdutoRepository.findByCotacaoIdInAndRemovidoEmIsNull(cotacaoIds);
        Map<UUID, CotacaoProduto> cpPorId = new HashMap<>();
        itens.forEach(cp -> cpPorId.put(cp.getId(), cp));

        List<CotacaoProdutoFornecedor> respostas = cotacaoIds.isEmpty()
                ? List.of() : cpfRepository.findByCotacaoIdIn(cotacaoIds);

        Map<UUID, Produto> produtos = new HashMap<>();
        produtoRepository.findAll().forEach(p -> produtos.put(p.getId(), p));

        // findAll(), não findByStatusNot(INATIVO) como no ComparativoService: histórico
        // deve continuar mostrando o nome de um fornecedor mesmo que tenha sido
        // inativado depois da cotação em que ofertou.
        Map<UUID, Fornecedor> fornecedores = new HashMap<>();
        fornecedorRepository.findAll().forEach(f -> fornecedores.put(f.getId(), f));

        // Ponto de referência de (produto, cotação) = menor oferta válida da cotação
        // para aquele produto — mesmo critério de MapaCompraService.ofertaValida()/
        // menorPreco(). Não é necessariamente o que foi de fato comprado (Mapa de
        // Compra pode divergir sob EQUILIBRADA/MELHOR_PRAZO/ajuste manual); é o melhor
        // preço de mercado observado naquela cotação — simplificação deliberada para
        // manter a consulta derivável por join simples, sem reprocessar o snapshot do
        // Mapa (docs técnica §11, nota de implementação).
        Map<Chave, CotacaoProdutoFornecedor> melhorPorProdutoCotacao = new HashMap<>();
        for (CotacaoProdutoFornecedor cpf : respostas) {
            if (cpf.getStatus() != StatusItem.OK || cpf.isSemEstoque() || cpf.getPrecoUnitarioCalculado() == null) {
                continue;
            }
            CotacaoProduto cp = cpPorId.get(cpf.getCotacaoProdutoId());
            if (cp == null || cp.getProdutoId() == null || !produtos.containsKey(cp.getProdutoId())) {
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
        for (Produto p : produtos.values()) {
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
        resultado.sort(Comparator.comparing(HistoricoPrecoProdutoResponse::nomeProduto));
        return resultado;
    }
}
