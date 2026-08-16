package com.prx.cotacao.cotacao.respostafornecedor.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monta a "lista base" de matching (Prompt 15+) a partir de {@link CotacaoProduto}, com o
 * mesmo critério de nome de referência para os dois consumidores que hoje recomputam esse
 * pipeline de forma independente — {@code RespostaFornecedorCoreService} (preview) e
 * {@code ConfirmacaoRespostaService} (confirmar). {@code texto_original} é a fonte
 * preferencial (é o texto real que o parser/matching de resposta do fornecedor foi desenhado
 * para comparar); só cai para o nome do produto do catálogo quando não há texto original —
 * caso de item adicionado manualmente vinculando um produto já cadastrado, onde
 * {@code texto_original} fica "" (ver {@code CotacaoProdutoItemService.adicionar}).
 */
@Service
public class ItemBaseCatalogoResolver {

    private final ProdutoRepository produtoRepository;

    public ItemBaseCatalogoResolver(ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    public record ItensBaseResolvidos(List<ProdutoCatalogo> produtosBase, Map<UUID, String> nomesPorItemBase) {}

    public ItensBaseResolvidos resolver(List<CotacaoProduto> itensBase) {
        Set<UUID> produtoIds = itensBase.stream()
                .map(CotacaoProduto::getProdutoId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<UUID, String> nomePorProdutoId = produtoIds.isEmpty()
                ? Map.of()
                : produtoRepository.findAllById(produtoIds).stream()
                        .collect(Collectors.toMap(Produto::getId, Produto::getNome));

        List<ProdutoCatalogo> produtosBase = new ArrayList<>();
        Map<UUID, String> nomesPorItemBase = new HashMap<>();
        for (CotacaoProduto cp : itensBase) {
            String nome = nomeReferencia(cp, nomePorProdutoId);
            produtosBase.add(new ProdutoCatalogo(cp.getId(), nome));
            nomesPorItemBase.put(cp.getId(), nome);
        }
        return new ItensBaseResolvidos(produtosBase, nomesPorItemBase);
    }

    private String nomeReferencia(CotacaoProduto cp, Map<UUID, String> nomePorProdutoId) {
        String textoOriginal = cp.getTextoOriginal();
        if (textoOriginal != null && !textoOriginal.isBlank()) {
            return textoOriginal;
        }
        if (cp.getProdutoId() != null) {
            return nomePorProdutoId.getOrDefault(cp.getProdutoId(), "");
        }
        return "";
    }
}
