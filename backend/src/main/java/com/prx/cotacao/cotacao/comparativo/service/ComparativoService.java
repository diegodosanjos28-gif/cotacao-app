package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
import com.prx.cotacao.cotacao.mensagem.service.PrecoReferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaParseada;
import com.prx.cotacao.cotacao.core.service.ParserListaProdutosService;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;

@Service
public class ComparativoService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final FornecedorRepository fornecedorRepository;
    private final ProdutoRepository produtoRepository;
    private final ParserListaProdutosService parserListaProdutos;
    private final PrecoReferenciaService precoReferencia;

    public ComparativoService(CotacaoRepository cotacaoRepository,
                               CotacaoProdutoRepository cotacaoProdutoRepository,
                               CotacaoProdutoFornecedorRepository cpfRepository,
                               FornecedorRepository fornecedorRepository,
                               ProdutoRepository produtoRepository,
                               ParserListaProdutosService parserListaProdutos,
                               PrecoReferenciaService precoReferencia) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.produtoRepository = produtoRepository;
        this.parserListaProdutos = parserListaProdutos;
        this.precoReferencia = precoReferencia;
    }

    @Transactional(readOnly = true)
    public List<ComparativoItemResponse> comparativo(UUID cotacaoId) {
        cotacaoRepository.findByIdOrThrow(cotacaoId);

        List<CotacaoProduto> itens = cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId);
        List<CotacaoProdutoFornecedor> todasRespostas = cpfRepository.findByCotacaoId(cotacaoId);

        // Cache de fornecedores e produtos (Hibernate filter aplica tenant automaticamente)
        Map<UUID, Fornecedor> fornecedores = new HashMap<>();
        fornecedorRepository.findByStatusNot(FornecedorStatus.INATIVO)
                .forEach(f -> fornecedores.put(f.getId(), f));

        Map<UUID, Produto> produtos = new HashMap<>();
        produtoRepository.findAll()
                .forEach(p -> produtos.put(p.getId(), p));

        // Agrupar respostas por cotacao_produto_id
        Map<UUID, List<CotacaoProdutoFornecedor>> respostasPorItem = new HashMap<>();
        for (CotacaoProdutoFornecedor cpf : todasRespostas) {
            respostasPorItem.computeIfAbsent(cpf.getCotacaoProdutoId(), k -> new ArrayList<>()).add(cpf);
        }

        List<ComparativoItemResponse> resultado = new ArrayList<>();
        for (CotacaoProduto cp : itens) {
            String nomeProduto = cp.getProdutoId() != null && produtos.containsKey(cp.getProdutoId())
                    ? produtos.get(cp.getProdutoId()).getNome()
                    : nomeSemQuantidadeEUnidade(cp.getTextoOriginal());

            List<CotacaoProdutoFornecedor> respostas = respostasPorItem.getOrDefault(cp.getId(), List.of());
            List<ComparativoItemResponse.PrecoFornecedor> precos = respostas.stream()
                    .map(cpf -> {
                        Fornecedor f = fornecedores.get(cpf.getFornecedorId());
                        String nomeForn = f != null ? f.getNome() : cpf.getFornecedorId().toString();
                        return new ComparativoItemResponse.PrecoFornecedor(
                                cpf.getFornecedorId(), nomeForn,
                                cpf.getPrecoInformado(), cpf.getPrecoUnitarioCalculado(),
                                cpf.isSemEstoque(),
                                cpf.getStatus(),
                                temDivergenciaComparativa(cpf, respostas)
                        );
                    })
                    .toList();

            resultado.add(new ComparativoItemResponse(
                    cp.getId(), cp.getProdutoId(), nomeProduto, cp.getQuantidade(), cp.getUnidade(), precos));
        }

        return resultado;
    }

    // Compara o preço de um fornecedor com a mediana dos preços dos DEMAIS fornecedores
    // (excluindo ele mesmo e itens sem estoque, que não representam oferta real) para o
    // mesmo item. Computado ao vivo a cada chamada — sem persistência, consistente com
    // o resto do Comparativo, que já lê cotacao_produto/cotacao_produto_fornecedor
    // diretamente do banco a cada requisição. Critério (mediana + limiar de 1.5x) e
    // cálculo compartilhados com a Conferência via PrecoReferenciaService — pacote de
    // ajustes pós-call, B.3 (substitui o antigo critério de R$10,00 absolutos).
    private boolean temDivergenciaComparativa(CotacaoProdutoFornecedor alvo, List<CotacaoProdutoFornecedor> respostas) {
        if (alvo.isSemEstoque()) {
            return false;
        }
        // precoUnitarioCalculado, não precoInformado: quando o operador já resolveu a
        // suspeita de preço de fardo/caixa informando "Unid./embalagem" na Conferência,
        // precoInformado continua sendo o snapshot bruto do que o fornecedor mandou
        // (ex.: R$13,00 pela caixa) — é precoUnitarioCalculado que reflete o valor
        // corrigido (R$13,00 ÷ 12 = R$1,08). Comparar pelo bruto faria o badge
        // continuar acusando divergência mesmo depois de corrigido (achado do
        // cliente). precoUnitarioCalculado sempre existe (igual a precoInformado
        // quando não há embalagem_qtd_confirmada, ver ConfirmacaoRespostaService).
        List<BigDecimal> outros = respostas.stream()
                .filter(r -> !r.getId().equals(alvo.getId()))
                .filter(r -> !r.isSemEstoque())
                .map(CotacaoProdutoFornecedor::getPrecoUnitarioCalculado)
                .toList();
        if (outros.isEmpty()) {
            return false;
        }
        BigDecimal mediana = precoReferencia.calcularMediana(outros);
        return precoReferencia.divergeDaReferencia(alvo.getPrecoUnitarioCalculado(), mediana);
    }

    // Item sem produtoId (não conciliado com o catálogo) não tem nome próprio — só
    // texto_original, que é o snapshot imutável da linha colada (auditoria, seção 3.2
    // da doc técnica) e por isso nunca é reescrito quando quantidade/unidade são
    // editadas depois (CotacaoProdutoItemService.editar). Se o comparativo exibisse
    // texto_original puro, a linha continuaria mostrando a quantidade/unidade
    // ORIGINAIS coladas mesmo depois de uma edição. Reparseia com o mesmo parser do
    // ingest só para extrair a porção de nome (sem quantidade/unidade embutida) — a
    // exibição então compõe nome + cp.getQuantidade()/getUnidade() (sempre atuais),
    // igual já acontece para item conciliado com o catálogo.
    private String nomeSemQuantidadeEUnidade(String textoOriginal) {
        List<LinhaParseada> linhas = parserListaProdutos.parsear(textoOriginal);
        return linhas.isEmpty() ? textoOriginal : linhas.get(0).nomeProduto();
    }
}
