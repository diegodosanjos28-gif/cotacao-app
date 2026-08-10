package com.prx.cotacao.cotacao.respostafornecedor.parser.service;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.DivergenciaVolume;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ItemConciliado;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Motor de conciliação da resposta de fornecedor contra os itens base da cotação — porte
 * fiel de conciliarEnhanced + _srAgrupar do protótipo. Ver
 * /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md seções 0 e 4.
 *
 * <p>Consome {@link LinhaFornecedor} (saída de {@link ParserRespostaFornecedorService}) e
 * {@link ProdutoCatalogo} (itens base da cotação — mesmo shape id+nome sugerido pelo
 * plano), produz {@link ItemConciliado}. Ainda não é chamado por nenhum outro service —
 * é o motor de conciliação para o próximo épico (B4/B5), que vai reescrever
 * FornecedorRespostaService por cima dele.</p>
 */
@Service
public class ConciliacaoRespostaService {

    private static final BigDecimal LIMIAR_CONFIRMADO = new BigDecimal("0.55");
    private static final BigDecimal LIMIAR_PROVAVEL = new BigDecimal("0.35");
    private static final BigDecimal LIMIAR_SEM_ESTOQUE = new BigDecimal("0.4");
    private static final BigDecimal LIMIAR_VOLUME_SECUNDARIO = new BigDecimal("0.5");
    private static final BigDecimal FATOR_PENALIDADE_VOLUME = new BigDecimal("0.6");
    private static final BigDecimal LIMIAR_REBAIXA_CONFIRMADO_POR_MARCA = new BigDecimal("0.75");
    private static final BigDecimal LIMIAR_REDISTRIBUICAO = new BigDecimal("0.55");
    private static final int MAX_VOLTAS_REDISTRIBUICAO = 5;

    private final MatchingProdutoService matchingProduto;

    public ConciliacaoRespostaService(MatchingProdutoService matchingProduto) {
        this.matchingProduto = matchingProduto;
    }

    /**
     * Resultado do agrupamento por item base: {@code porProduto} mapeia o id do item base
     * para os candidatos que casaram com ele (após passo 1 de demoção e passo 2 de
     * redistribuição); {@code extras} são os candidatos demovidos no passo 1 (divergentes
     * de volume que perderam a linha para um match direto) — elegíveis para "Adicionar
     * item à lista" na etapa de classificação (byProduto.__extra__ no protótipo).
     */
    public record ResultadoAgrupamento(Map<UUID, List<ItemConciliado>> porProduto, List<ItemConciliado> extras) {}

    private record MelhorMatch(ProdutoCatalogo produto, BigDecimal score) {}

    private record Redistribuicao(ItemConciliado item, ProdutoCatalogo destino) {}

    /**
     * Concilia cada linha parseada da resposta do fornecedor contra os itens base da
     * cotação. Porte fiel de conciliarEnhanced do protótipo.
     */
    public List<ItemConciliado> conciliarEnhanced(List<LinhaFornecedor> itemsCotados, List<ProdutoCatalogo> produtosBase) {
        List<ItemConciliado> resultado = new ArrayList<>();
        for (LinhaFornecedor item : itemsCotados) {
            resultado.add(conciliarItem(item, produtosBase));
        }
        return resultado;
    }

    private ItemConciliado conciliarItem(LinhaFornecedor item, List<ProdutoCatalogo> produtosBase) {
        String descricao = item.nomeProduto();
        ItemConciliado ic = new ItemConciliado(item, descricao);

        if (item.semEstoque()) {
            MelhorMatch melhor = encontrarMelhorMatch(descricao, produtosBase);
            boolean achouSemEstoque = melhor.produto() != null && melhor.score().compareTo(LIMIAR_SEM_ESTOQUE) >= 0;
            ic.setProdutoId(achouSemEstoque ? melhor.produto().id() : null);
            ic.setProdutoNome(melhor.produto() != null ? melhor.produto().nome() : null);
            ic.setConfianca(melhor.score());
            ic.setStatus(achouSemEstoque ? ItemConciliado.STATUS_SEM_ESTOQUE : ItemConciliado.STATUS_NAO_IDENTIFICADO);
            ic.setTipoCorrespondencia(ItemConciliado.TIPO_SEM_ESTOQUE);
            ic.setMarcaSolicitada(melhor.produto() != null ? matchingProduto.extrairMarca(melhor.produto().nome()) : null);
            return ic;
        }

        MelhorMatch melhor = encontrarMelhorMatch(descricao, produtosBase);
        ProdutoCatalogo bestMatch = melhor.produto();
        BigDecimal bestScore = melhor.score();

        // 2ª passada: sem bom match direto, verifica divergência de volume (mesmo produto,
        // medida diferente) antes de desistir.
        DivergenciaVolume divergenciaVolume = null;
        if (bestScore.compareTo(LIMIAR_PROVAVEL) < 0) {
            MelhorMatch melhorVol = encontrarMelhorMatchSemVolume(descricao, produtosBase);
            if (melhorVol.produto() != null && melhorVol.score().compareTo(LIMIAR_VOLUME_SECUNDARIO) >= 0) {
                DivergenciaVolume dv = matchingProduto.detectarDivergenciaVolume(melhorVol.produto().nome(), descricao);
                if (dv != null) {
                    bestMatch = melhorVol.produto();
                    bestScore = melhorVol.score().multiply(FATOR_PENALIDADE_VOLUME);
                    divergenciaVolume = dv;
                }
            }
        }

        String status = ItemConciliado.STATUS_NAO_IDENTIFICADO;
        String tipoCorrespondencia = ItemConciliado.TIPO_NAO_IDENTIFICADO;
        if (bestScore.compareTo(LIMIAR_CONFIRMADO) >= 0) {
            status = ItemConciliado.STATUS_CONFIRMADO;
            tipoCorrespondencia = ItemConciliado.TIPO_EXATO;
        } else if (bestScore.compareTo(LIMIAR_PROVAVEL) >= 0) {
            status = ItemConciliado.STATUS_PROVAVEL;
            tipoCorrespondencia = ItemConciliado.TIPO_SIMILAR;
        }

        String marcaSolicitada = bestMatch != null ? matchingProduto.extrairMarca(bestMatch.nome()) : null;
        String marcaOferecida = item.marcaOferecida() != null ? item.marcaOferecida() : matchingProduto.extrairMarca(descricao);

        // Marca diferente da solicitada
        if (bestMatch != null && marcaSolicitada != null && marcaOferecida != null && !marcaSolicitada.equals(marcaOferecida)) {
            tipoCorrespondencia = ItemConciliado.TIPO_MARCA_ALTERNATIVA;
            if (ItemConciliado.STATUS_CONFIRMADO.equals(status) && bestScore.compareTo(LIMIAR_REBAIXA_CONFIRMADO_POR_MARCA) < 0) {
                status = ItemConciliado.STATUS_PROVAVEL;
            }
        }

        // Divergência de volume/peso (se ainda não detectada na 2ª passada)
        if (divergenciaVolume == null && bestMatch != null) {
            divergenciaVolume = matchingProduto.detectarDivergenciaVolume(bestMatch.nome(), descricao);
        }
        if (divergenciaVolume != null) {
            tipoCorrespondencia = ItemConciliado.TIPO_VOLUME_DIFERENTE;
            if (ItemConciliado.STATUS_CONFIRMADO.equals(status)) {
                status = ItemConciliado.STATUS_PROVAVEL;
            }
        }

        // Suspeita de preço de embalagem (caixa/fardo) — item.precoBase pode ser null aqui
        // para linhas que o parser não conseguiu classificar em nenhum dos ramos que
        // preenchem esse campo (ver ParserRespostaFornecedorService); tratado como
        // equivalente a "unidade" (não aciona a suspeita), já que o protótipo nunca produz
        // um item com precoBase indefinido nesse ponto.
        boolean precoBaseDiferenteDeUnidade = item.precoBase() != null && !item.precoBase().equals("unidade");
        if (item.precoPendente() || precoBaseDiferenteDeUnidade) {
            if ("ambiguo".equals(item.precoBase())) {
                tipoCorrespondencia = ItemConciliado.TIPO_PRECO_AMBIGUO;
            } else if (item.precoPendente()
                    && (ItemConciliado.TIPO_EXATO.equals(tipoCorrespondencia) || ItemConciliado.TIPO_SIMILAR.equals(tipoCorrespondencia))) {
                tipoCorrespondencia = ItemConciliado.TIPO_PRECO_EMBALAGEM;
            }
        }

        ic.setProdutoId(bestMatch != null ? bestMatch.id() : null);
        ic.setProdutoNome(bestMatch != null ? bestMatch.nome() : null);
        ic.setConfianca(bestScore);
        ic.setStatus(status);
        ic.setTipoCorrespondencia(tipoCorrespondencia);
        ic.setMarcaSolicitada(marcaSolicitada);
        ic.setDivergenciaVolume(divergenciaVolume);
        return ic;
    }

    private MelhorMatch encontrarMelhorMatch(String descricao, List<ProdutoCatalogo> produtos) {
        ProdutoCatalogo melhor = null;
        BigDecimal melhorScore = BigDecimal.ZERO;
        for (ProdutoCatalogo p : produtos) {
            BigDecimal sc = matchingProduto.calcSimilaridade(descricao, p.nome());
            if (sc.compareTo(melhorScore) > 0) {
                melhorScore = sc;
                melhor = p;
            }
        }
        return new MelhorMatch(melhor, melhorScore);
    }

    private MelhorMatch encontrarMelhorMatchSemVolume(String descricao, List<ProdutoCatalogo> produtos) {
        ProdutoCatalogo melhor = null;
        BigDecimal melhorScore = BigDecimal.ZERO;
        for (ProdutoCatalogo p : produtos) {
            BigDecimal sc = matchingProduto.calcSimilaridadeSemVolume(descricao, p.nome());
            if (sc.compareTo(melhorScore) > 0) {
                melhorScore = sc;
                melhor = p;
            }
        }
        return new MelhorMatch(melhor, melhorScore);
    }

    /**
     * Agrupa os itens conciliados por item base. Porte fiel de _srAgrupar do protótipo:
     * <ul>
     *   <li>Passo 1 — se um item base recebeu candidatos diretos E divergentes de volume ao
     *   mesmo tempo, os divergentes perdem a linha (viram pool "extra").</li>
     *   <li>Passo 2 — até 5 rodadas de redistribuição: um candidato "sobrando" numa linha
     *   com múltiplos matches é realocado para outra linha base ainda vazia se pontuar
     *   {@code >=0.55} e {@code >=} seu score atual lá. Linha já preenchida não rouba item
     *   de outra.</li>
     * </ul>
     */
    public ResultadoAgrupamento agrupar(List<ItemConciliado> parsedItems, List<ProdutoCatalogo> produtosBase) {
        Map<UUID, List<ItemConciliado>> byProduto = new LinkedHashMap<>();
        List<ItemConciliado> extras = new ArrayList<>();

        for (ItemConciliado it : parsedItems) {
            boolean statusElegivel = ItemConciliado.STATUS_CONFIRMADO.equals(it.status())
                    || ItemConciliado.STATUS_PROVAVEL.equals(it.status());
            if (statusElegivel && it.produtoId() != null) {
                byProduto.computeIfAbsent(it.produtoId(), k -> new ArrayList<>()).add(it);
            }
        }

        // Passo 1 — match direto tem prioridade sobre match divergente de volume/peso
        for (ProdutoCatalogo p : produtosBase) {
            List<ItemConciliado> ms = byProduto.getOrDefault(p.id(), List.of());
            if (ms.size() <= 1) {
                continue;
            }
            List<ItemConciliado> diretos = new ArrayList<>();
            List<ItemConciliado> divergentes = new ArrayList<>();
            for (ItemConciliado m : ms) {
                boolean divergente = m.divergenciaVolume() != null
                        || ItemConciliado.TIPO_VOLUME_DIFERENTE.equals(m.tipoCorrespondencia());
                (divergente ? divergentes : diretos).add(m);
            }
            if (!diretos.isEmpty() && !divergentes.isEmpty()) {
                byProduto.put(p.id(), diretos);
                for (ItemConciliado m : divergentes) {
                    m.setProdutoId(null);
                    extras.add(m);
                }
            }
        }

        // Passo 2 — verificação cruzada: redistribui candidatos "sobrando" para outras
        // linhas base ainda sem correspondência, repetindo até estabilizar (máx 5 voltas).
        boolean mudou = true;
        int voltas = 0;
        while (mudou && voltas < MAX_VOLTAS_REDISTRIBUICAO) {
            mudou = false;
            voltas++;
            for (ProdutoCatalogo p : produtosBase) {
                List<ItemConciliado> ms = byProduto.getOrDefault(p.id(), List.of());
                if (ms.size() <= 1) {
                    continue;
                }
                List<ItemConciliado> ficam = new ArrayList<>();
                List<Redistribuicao> saem = new ArrayList<>();
                for (ItemConciliado m : ms) {
                    String textoBase = textoBaseParaMatch(m);
                    BigDecimal scoreAqui = matchingProduto.calcSimilaridade(textoBase, p.nome());
                    ProdutoCatalogo melhorOutro = null;
                    BigDecimal melhorScore = BigDecimal.ZERO;
                    for (ProdutoCatalogo p2 : produtosBase) {
                        if (p2.id().equals(p.id())) {
                            continue;
                        }
                        if (!byProduto.getOrDefault(p2.id(), List.of()).isEmpty()) {
                            // linha já preenchida não rouba item de outra
                            continue;
                        }
                        BigDecimal sc = matchingProduto.calcSimilaridade(textoBase, p2.nome());
                        if (sc.compareTo(melhorScore) > 0) {
                            melhorScore = sc;
                            melhorOutro = p2;
                        }
                    }
                    if (melhorOutro != null && melhorScore.compareTo(LIMIAR_REDISTRIBUICAO) >= 0
                            && melhorScore.compareTo(scoreAqui) >= 0) {
                        saem.add(new Redistribuicao(m, melhorOutro));
                    } else {
                        ficam.add(m);
                    }
                }
                if (!saem.isEmpty()) {
                    byProduto.put(p.id(), ficam);
                    for (Redistribuicao r : saem) {
                        r.item().setProdutoId(r.destino().id());
                        byProduto.computeIfAbsent(r.destino().id(), k -> new ArrayList<>()).add(r.item());
                    }
                    mudou = true;
                }
            }
        }

        return new ResultadoAgrupamento(byProduto, extras);
    }

    private String textoBaseParaMatch(ItemConciliado m) {
        String d = m.descricao();
        return (d != null && !d.isEmpty()) ? d : m.textoOriginal();
    }
}
