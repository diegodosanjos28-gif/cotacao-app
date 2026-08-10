package com.prx.cotacao.cotacao.mensagem.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.cotacao.mapacompra.service.MapaCompraService;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;

@Service
public class MensagemService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final FornecedorRepository fornecedorRepository;
    private final ProdutoRepository produtoRepository;
    private final MapaCompraService mapaCompraService;

    public MensagemService(CotacaoRepository cotacaoRepository,
                            CotacaoProdutoRepository cotacaoProdutoRepository,
                            CotacaoProdutoFornecedorRepository cpfRepository,
                            FornecedorRepository fornecedorRepository,
                            ProdutoRepository produtoRepository,
                            MapaCompraService mapaCompraService) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.produtoRepository = produtoRepository;
        this.mapaCompraService = mapaCompraService;
    }

    // "Pedido" pro fornecedor: só os itens que o Mapa de Compra atribuiu a ele no
    // cenário selecionado (não a lista inteira da cotação — esse texto é pra
    // confirmar o pedido de compra, não pra pedir cotação).
    @Transactional(readOnly = true)
    public String gerarMensagem(UUID cotacaoId, UUID fornecedorId, CenarioSelecionado cenario) {
        cotacaoRepository.findByIdOrThrow(cotacaoId);

        Fornecedor fornecedor = fornecedorRepository.findById(fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + fornecedorId));

        MapaCompraResponse mapa = mapaCompraService.gerar(cotacaoId, cenario);
        Set<UUID> itensAtribuidos = mapa.distribuicoes().stream()
                .filter(d -> d.fornecedorId().equals(fornecedorId))
                .findFirst()
                .map(d -> d.itens().stream().map(MapaCompraResponse.ItemDistribuicao::cotacaoProdutoId)
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of);

        List<CotacaoProduto> itens = cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId).stream()
                .filter(cp -> itensAtribuidos.contains(cp.getId()))
                .toList();
        List<CotacaoProdutoFornecedor> respostas = cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId);
        Map<UUID, Produto> produtos = produtoRepository.findAll().stream()
                .collect(Collectors.toMap(Produto::getId, p -> p));

        StringBuilder sb = new StringBuilder();
        sb.append("Pedido para ").append(fornecedor.getNome()).append(":\n\n");

        for (CotacaoProduto cp : itens) {
            // textoOriginal é a linha INTEIRA como o cliente colou (ex: "10un sazon
            // legumes 60g" — seção 3.2 da doc técnica), já inclui quantidade/unidade.
            // Só temos um nome de produto "limpo" (sem o prefixo de qtd) quando o item
            // foi conciliado com o catálogo — nesse caso dá pra reformatar a linha
            // (inclusive pra conversão em caixas); sem isso, ecoa textoOriginal como
            // veio, sem duplicar quantidade/unidade na frente.
            Produto produto = cp.getProdutoId() != null ? produtos.get(cp.getProdutoId()) : null;

            var respostaComEmbalagem = respostas.stream()
                    .filter(r -> r.getCotacaoProdutoId().equals(cp.getId()))
                    .filter(r -> !r.isSemEstoque())
                    .filter(r -> r.getEmbalagemQtdConfirmada() != null && r.getEmbalagemQtdConfirmada() > 0)
                    .findFirst();

            if (produto != null && respostaComEmbalagem.isPresent()) {
                // Usa embalagem confirmada pra converter a quantidade pedida (em
                // unidades soltas) em quantidade de caixas — arredonda pra cima,
                // nunca pede caixa fracionada.
                int embalagemQtd = respostaComEmbalagem.get().getEmbalagemQtdConfirmada();
                BigDecimal caixas = cp.getQuantidade().divide(BigDecimal.valueOf(embalagemQtd), 0, RoundingMode.CEILING);
                sb.append(formatarQuantidade(caixas)).append(" cx ").append(produto.getNome())
                  .append(" (").append(formatarQuantidade(cp.getQuantidade())).append(" ").append(cp.getUnidade())
                  .append(" / caixa com ").append(embalagemQtd).append(")\n");
            } else if (produto != null) {
                sb.append(formatarQuantidade(cp.getQuantidade())).append(" ").append(cp.getUnidade())
                  .append(" ").append(produto.getNome()).append("\n");
            } else {
                sb.append(cp.getTextoOriginal()).append("\n");
            }
        }

        if (fornecedor.getCondicaoPagamentoPadrao() != null) {
            sb.append("\nPagamento: ").append(fornecedor.getCondicaoPagamentoPadrao());
        }
        if (fornecedor.getPrazoEntregaPadrao() != null) {
            sb.append("\nEntrega: ").append(fornecedor.getPrazoEntregaPadrao());
        }

        return sb.toString().trim();
    }

    private String formatarQuantidade(BigDecimal qtd) {
        if (qtd.stripTrailingZeros().scale() <= 0) {
            return qtd.toBigInteger().toString();
        }
        return qtd.toPlainString();
    }
}
