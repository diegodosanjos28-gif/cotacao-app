package com.prx.cotacao.cotacao.respostafornecedor.service;

import com.prx.cotacao.cotacao.mensagem.service.PrecoReferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.EmbalagemDetectada;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ItemConciliado;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.PesoVolume;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.CandidatoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.ItemConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;

/**
 * Motor de classificação OK/Atenção/Revisar de uma resposta de fornecedor conferida
 * contra a lista base — porte fiel de buildSupplierReview + _srCT do protótipo. Consome
 * a saída de {@link ConciliacaoRespostaService#conciliarEnhanced} /
 * {@link ConciliacaoRespostaService#agrupar}. Ver
 * /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md seções 0 e 4.
 *
 * <p>Ainda não é chamado por nenhum controller/service — é o motor de classificação para
 * o próximo épico (B4/B5).</p>
 */
@Service
public class ClassificacaoConferenciaService {

    private static final BigDecimal LIMIAR_DIVERGENCIA_MEDIDA = new BigDecimal("0.001");
    private static final BigDecimal LIMIAR_CONFIANCA_BAIXA = new BigDecimal("0.45");

    private final MatchingProdutoService matchingProduto;
    private final PrecoReferenciaService precoReferencia;

    public ClassificacaoConferenciaService(MatchingProdutoService matchingProduto,
                                            PrecoReferenciaService precoReferencia) {
        this.matchingProduto = matchingProduto;
        this.precoReferencia = precoReferencia;
    }

    /**
     * Classifica os itens conciliados de UMA resposta de fornecedor contra os itens base
     * da cotação.
     *
     * @param produtosBase      itens base da cotação (mesma lista usada para conciliar)
     * @param itemsConciliados  saída completa (não filtrada) de conciliarEnhanced para
     *                          esse fornecedor — usada para localizar itens
     *                          "não identificados" com preço (viram EXTRA_ITEM)
     * @param agrupamento       saída de {@link ConciliacaoRespostaService#agrupar} para os
     *                          mesmos itemsConciliados/produtosBase
     * @param referenciaPorItemBase preço de referência (mediana dos demais fornecedores
     *                          já confirmados nesta cotação) por item base — usado para
     *                          o gatilho de suspeita de preço de fardo/caixa em item COM
     *                          preço (B.3); item sem referência disponível não entra
     *                          neste mapa
     */
    public List<ItemConferencia> classificar(
            List<ProdutoCatalogo> produtosBase,
            List<ItemConciliado> itemsConciliados,
            ConciliacaoRespostaService.ResultadoAgrupamento agrupamento,
            Map<UUID, BigDecimal> referenciaPorItemBase
    ) {
        List<ItemConferencia> resultado = new ArrayList<>();
        Map<UUID, List<ItemConciliado>> porProduto = agrupamento.porProduto();

        for (ProdutoCatalogo p : produtosBase) {
            List<ItemConciliado> ms = porProduto.getOrDefault(p.id(), List.of());
            if (ms.isEmpty()) {
                // Nenhum candidato para esse item base — não gera ItemConferencia (segue o
                // protótipo à risca: buildSupplierReview faz `if(ms.length===0){return}`,
                // sem "missing_item").
                continue;
            }
            if (ms.size() > 1) {
                List<CandidatoResposta> candidatos = ms.stream().map(this::paraCandidato).toList();
                resultado.add(new ItemConferencia(p.id(), StatusConferencia.REVISAR,
                        List.of(MotivoConferencia.MULTIPLE_OPTIONS), candidatos));
                continue;
            }

            ItemConciliado it = ms.get(0);
            resultado.add(classificarMatchUnico(p, it, referenciaPorItemBase.get(p.id())));
        }

        // Itens demovidos no Passo 1 do agrupamento (divergentes de volume que perderam a
        // linha para um match direto) — viram itens extra, elegíveis para "Adicionar item
        // à lista".
        for (ItemConciliado it : agrupamento.extras()) {
            resultado.add(itemExtra(it));
        }

        // Linhas "não identificadas" mas com preço reconhecido — também viram item extra.
        for (ItemConciliado it : itemsConciliados) {
            if (ItemConciliado.STATUS_NAO_IDENTIFICADO.equals(it.status()) && it.preco() != null) {
                resultado.add(itemExtra(it));
            }
        }

        return resultado;
    }

    /**
     * Reconcilia a classificação desta rodada com o que já estava confirmado em
     * {@code cotacao_produto_fornecedor} deste MESMO fornecedor numa rodada anterior.
     * Sem este merge, um item base não mencionado na mensagem atual simplesmente some do
     * resultado — e, no fluxo de confirmação, sua linha já persistida acaba apagada
     * junto com o delete-all de reconfirmação (o bug original desta correção).
     *
     * @param produtosBase itens base da cotação, na ordem de exibição
     * @param classificados saída de {@link #classificar} para a resposta atual
     * @param existentes    linhas já confirmadas deste fornecedor nesta cotação, de uma
     *                       confirmação anterior (pode ser vazia no primeiro processamento)
     */
    public List<ItemConferencia> mesclarComConfirmacoesAnteriores(
            List<ProdutoCatalogo> produtosBase,
            List<ItemConferencia> classificados,
            List<CotacaoProdutoFornecedor> existentes
    ) {
        Map<UUID, CotacaoProdutoFornecedor> existentePorItemBase = new HashMap<>();
        for (CotacaoProdutoFornecedor cpf : existentes) {
            existentePorItemBase.put(cpf.getCotacaoProdutoId(), cpf);
        }
        Map<UUID, ItemConferencia> tocadoPorItemBase = new HashMap<>();
        for (ItemConferencia item : classificados) {
            if (item.itemBaseId() != null) {
                tocadoPorItemBase.put(item.itemBaseId(), item);
            }
        }

        List<ItemConferencia> resultado = new ArrayList<>();
        for (ProdutoCatalogo p : produtosBase) {
            ItemConferencia tocado = tocadoPorItemBase.get(p.id());
            CotacaoProdutoFornecedor anterior = existentePorItemBase.get(p.id());
            if (tocado != null) {
                BigDecimal precoAnterior = anterior != null ? anterior.getPrecoInformado() : null;
                resultado.add(new ItemConferencia(tocado.itemBaseId(), tocado.status(), tocado.motivos(),
                        tocado.candidatos(), false, precoAnterior));
            } else if (anterior != null) {
                resultado.add(itemPreservado(p.id(), anterior));
            }
            // Nem tocado nesta rodada, nem confirmado antes — continua fora (decisão F).
        }

        // Itens sem item base (EXTRA_ITEM / linha não identificada com preço) não são
        // indexáveis por produto base — reanexados ao final, sem alteração.
        for (ItemConferencia item : classificados) {
            if (item.itemBaseId() == null) {
                resultado.add(item);
            }
        }

        return resultado;
    }

    private ItemConferencia itemPreservado(UUID itemBaseId, CotacaoProdutoFornecedor anterior) {
        CandidatoResposta candidato = new CandidatoResposta(
                anterior.getTextoOriginal(), null, anterior.getPrecoInformado(),
                anterior.getConfiancaMatch(), anterior.isSemEstoque());
        return new ItemConferencia(itemBaseId, StatusConferencia.OK, List.of(), List.of(candidato),
                true, null);
    }

    private ItemConferencia classificarMatchUnico(ProdutoCatalogo p, ItemConciliado it, BigDecimal referencia) {
        List<MotivoConferencia> motivos = new ArrayList<>();
        StatusConferencia status = StatusConferencia.OK;

        // Marca diferente da lista base.
        String bb = matchingProduto.extrairMarca(p.nome());
        String sb = it.linhaOriginal().marcaOferecida() != null
                ? it.linhaOriginal().marcaOferecida()
                : matchingProduto.extrairMarca(it.descricao());
        if (bb != null && sb != null && !bb.equals(sb)) {
            motivos.add(MotivoConferencia.BRAND_CHANGED);
            status = escalar(status, StatusConferencia.ATENCAO);
        }

        // Medida (peso/volume): ausência na lista base x divergência entre os dois.
        PesoVolume bw = matchingProduto.extrairPesoVolume(p.nome());
        PesoVolume sw = matchingProduto.extrairPesoVolume(textoParaMedida(it));
        if (bw != null && sw != null && bw.dimensao() != null && sw.dimensao() != null
                && bw.dimensao().equals(sw.dimensao())) {
            if (bw.valorBase().subtract(sw.valorBase()).abs().compareTo(LIMIAR_DIVERGENCIA_MEDIDA) > 0) {
                motivos.add(MotivoConferencia.WEIGHT_CHANGED);
                status = escalar(status, StatusConferencia.REVISAR);
            }
        } else if (bw == null && sw != null && sw.dimensao() != null) {
            motivos.add("peso".equals(sw.dimensao()) ? MotivoConferencia.WEIGHT_ADDED : MotivoConferencia.VOLUME_ADDED);
            status = escalar(status, StatusConferencia.REVISAR);
        }

        // Quantidade por embalagem (ex.: "pacote com 12"): mesma lógica de ausência x
        // divergência, reaproveitando detectarEmbalagemPorTexto — sem parser paralelo.
        EmbalagemDetectada bEmb = matchingProduto.detectarEmbalagemPorTexto(p.nome());
        EmbalagemDetectada sEmb = it.linhaOriginal().embalagemDetectada();
        if ((bEmb == null || bEmb.qtdInterna() == null) && sEmb != null && sEmb.qtdInterna() != null) {
            motivos.add(MotivoConferencia.PACKAGE_QTY_ADDED);
            status = escalar(status, StatusConferencia.REVISAR);
        } else if (bEmb != null && bEmb.qtdInterna() != null && sEmb != null && sEmb.qtdInterna() != null
                && !bEmb.qtdInterna().equals(sEmb.qtdInterna())) {
            motivos.add(MotivoConferencia.PACKAGE_QTY_CHANGED);
            status = escalar(status, StatusConferencia.REVISAR);
        }

        // Suspeita de preço de caixa/fardo. Duas origens: (a) o próprio texto do
        // fornecedor sinaliza ("consultar"/preço de caixa explícito no texto — sem
        // preço extraível ainda), OU (b) o preço informado é ≥1.5x a mediana dos
        // demais fornecedores já confirmados nesta cotação para o mesmo item (B.3,
        // pacote de ajustes pós-call — ativa este gatilho também para item COM preço,
        // decisão do cliente que revoga a inércia deliberada anterior).
        String precoBase = it.linhaOriginal().precoBase();
        boolean precoBaseSuspeita = precoBase != null && !precoBase.equals("unidade") && !precoBase.equals("sem_preco");
        boolean precoMuitoAcimaDaReferencia = precoReferencia.divergeDaReferencia(it.preco(), referencia);
        if (it.linhaOriginal().precoPendente() || precoBaseSuspeita || precoMuitoAcimaDaReferencia) {
            motivos.add(MotivoConferencia.PACKAGE_PRICE_SUSPECTED);
            status = escalar(status, StatusConferencia.REVISAR);
        }

        // Confiança baixa — só rebaixa se ainda OK (semântica preservada via escalar()).
        if (it.confianca() != null && it.confianca().compareTo(LIMIAR_CONFIANCA_BAIXA) < 0
                && ItemConciliado.STATUS_PROVAVEL.equals(it.status())) {
            motivos.add(MotivoConferencia.LOW_CONFIDENCE_MATCH);
            status = escalar(status, StatusConferencia.ATENCAO);
        }

        // Reforça divergência de volume detectada na conciliação (2ª passada).
        if (it.divergenciaVolume() != null) {
            if (!motivos.contains(MotivoConferencia.WEIGHT_CHANGED)) {
                motivos.add(MotivoConferencia.WEIGHT_CHANGED);
            }
            status = escalar(status, StatusConferencia.REVISAR);
        }

        // Reforça marca alternativa detectada na conciliação.
        if (ItemConciliado.TIPO_MARCA_ALTERNATIVA.equals(it.tipoCorrespondencia())) {
            if (!motivos.contains(MotivoConferencia.BRAND_CHANGED)) {
                motivos.add(MotivoConferencia.BRAND_CHANGED);
            }
            status = escalar(status, StatusConferencia.ATENCAO);
        }

        // Decisão do cliente (pacote de ajustes pós-call, B.2): item identificado mas
        // sem preço reconhecível na linha NÃO é mais tratado como pendência — é estado
        // normal ("fornecedor não informou preço para este item"), igual a um
        // fornecedor que simplesmente não menciona o item. Não gera aviso nem aparece
        // na Conferência; ConfirmacaoRespostaService.confirmar trata precoFinal==null
        // como skip explícito (nenhuma linha persistida), não mais como erro bloqueante.
        // "Sem estoque" e "consultar/a combinar" (PACKAGE_PRICE_SUSPECTED, acima)
        // continuam REVISAR — são casos distintos de "não informou".

        return new ItemConferencia(p.id(), status, motivos, List.of(paraCandidato(it)));
    }

    private ItemConferencia itemExtra(ItemConciliado it) {
        return new ItemConferencia(null, StatusConferencia.REVISAR,
                List.of(MotivoConferencia.EXTRA_ITEM), List.of(paraCandidato(it)));
    }

    // it.descricao||it.textoOriginal — mesmo padrão de fallback usado em vários pontos do
    // protótipo quando a descrição pode estar vazia.
    private String textoParaMedida(ItemConciliado it) {
        String d = it.descricao();
        return (d != null && !d.isEmpty()) ? d : it.textoOriginal();
    }

    private CandidatoResposta paraCandidato(ItemConciliado it) {
        return new CandidatoResposta(
                it.textoOriginal(),
                it.linhaOriginal().marcaOferecida(),
                it.preco(),
                it.confianca(),
                it.semEstoque()
        );
    }

    // Nunca rebaixa: REVISAR > ATENCAO > OK. Uma vez atingido REVISAR, uma regra que só
    // "escalaria para ATENCAO" não tem efeito algum — replica textualmente as ressalvas
    // "só rebaixa se ainda OK" do protótipo, e também os casos de atribuição incondicional
    // (que nunca precisam desescalar, já que a cascata é sempre percorrida na mesma ordem).
    private StatusConferencia escalar(StatusConferencia atual, StatusConferencia novo) {
        return novo.ordinal() > atual.ordinal() ? novo : atual;
    }
}
