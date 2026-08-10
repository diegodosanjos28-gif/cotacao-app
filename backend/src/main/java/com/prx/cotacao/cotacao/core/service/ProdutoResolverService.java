package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.EmbalagemDetectada;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.PesoVolume;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoConciliacao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * "Resolver ou criar produto" — extraído de {@link CotacaoListaService#processarLista}
 * (Prompt 12) pra ser reaproveitado por {@link CotacaoListaService#importarTexto} e por
 * {@link CotacaoProdutoItemService#adicionar} (adição manual de item no grid com nome
 * livre). Preserva o mesmo pipeline: casa contra o catálogo dinâmico informado, e só
 * cria um Produto novo quando não há candidato com confiança suficiente — o catálogo
 * "nasce do matching da lista" (doc técnica, seção 5).
 */
@Service
public class ProdutoResolverService {

    private final ProdutoRepository produtoRepository;
    private final MatchingProdutoService matchingProduto;

    public ProdutoResolverService(ProdutoRepository produtoRepository, MatchingProdutoService matchingProduto) {
        this.produtoRepository = produtoRepository;
        this.matchingProduto = matchingProduto;
    }

    // findAll com Hibernate filter — só produtos do tenant atual. O resultado é
    // mutável: o chamador deve adicionar a ele os produtos criados durante o próprio
    // loop, pra que ocorrências repetidas do mesmo nome na mesma submissão reaproveitem
    // o produto recém-criado em vez de duplicar (mesmo comportamento de processarLista).
    public List<ProdutoCatalogo> catalogoDinamicoDoTenant() {
        return new ArrayList<>(produtoRepository.findAll().stream()
                .map(p -> new ProdutoCatalogo(p.getId(), p.getNome()))
                .toList());
    }

    // Retorna o ResultadoConciliacao (não só o UUID) pra preservar o score real do
    // match quando já existe candidato no catálogo — em vez de sempre reportar 1.0
    // como se todo produto resolvido fosse uma criação nova.
    @Transactional
    public ResultadoConciliacao resolverOuCriarProduto(List<ProdutoCatalogo> catalogoDinamico, String nomeProduto,
                                                        String unidade, String textoParaDeteccaoDeEmbalagem) {
        ResultadoConciliacao conc = matchingProduto.encontrarMelhorCandidato(catalogoDinamico, nomeProduto);
        if (conc.matched()) {
            return conc;
        }

        // Nenhum produto do catálogo bate com confiança suficiente — cria um produto novo.
        Produto novoProduto = new Produto();
        // Grafia original preservada (não normalizada/minúscula) — o matching já
        // normaliza no momento da comparação, então a forma armazenada não afeta a
        // qualidade do match, só a exibição em Comparativo/Mapa/Histórico.
        novoProduto.setNome(nomeProduto);
        novoProduto.setMarca(matchingProduto.extrairMarca(nomeProduto));
        PesoVolume pesoVolume = matchingProduto.extrairPesoVolume(nomeProduto);
        if (pesoVolume != null) {
            novoProduto.setPesoVolumeValor(pesoVolume.valor());
            novoProduto.setPesoVolumeUnidade(pesoVolume.unidade());
        }
        novoProduto.setUnidadePadrao(unidade);
        EmbalagemDetectada embalagem = matchingProduto.detectarEmbalagemPorTexto(textoParaDeteccaoDeEmbalagem);
        if (embalagem != null) {
            novoProduto.setEmbalagemQtdSugerida(embalagem.qtdInterna());
        }
        Produto salvo = produtoRepository.save(novoProduto);

        catalogoDinamico.add(new ProdutoCatalogo(salvo.getId(), nomeProduto));
        return new ResultadoConciliacao(nomeProduto, salvo.getId(), BigDecimal.ONE, true);
    }
}
