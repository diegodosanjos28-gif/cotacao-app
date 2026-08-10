package com.prx.cotacao.cotacao.mapacompra.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.DistribuicaoFornecedor;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.ItemDistribuicao;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.ItemRemovidoManualmente;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse.ProdutoSemFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.PrazoEntregaParser;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.mapacompra.entity.CotacaoAjusteManual;
import com.prx.cotacao.cotacao.mapacompra.repository.CotacaoAjusteManualRepository;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;

/**
 * Prompt 6 — três algoritmos de distribuição fornecedor→produto (seção 6.5 da doc
 * técnica). Não é porte de protótipo — a seção 6.5 não referencia função JS nenhuma,
 * é lógica nova.
 */
@Service
public class MapaCompraService {

    private static final Logger log = LoggerFactory.getLogger(MapaCompraService.class);

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final FornecedorRepository fornecedorRepository;
    private final ProdutoRepository produtoRepository;
    private final CotacaoAjusteManualRepository ajusteManualRepository;
    private final ObjectMapper objectMapper;

    public MapaCompraService(CotacaoRepository cotacaoRepository,
                              CotacaoProdutoRepository cotacaoProdutoRepository,
                              CotacaoProdutoFornecedorRepository cpfRepository,
                              FornecedorRepository fornecedorRepository,
                              ProdutoRepository produtoRepository,
                              CotacaoAjusteManualRepository ajusteManualRepository,
                              ObjectMapper objectMapper) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.produtoRepository = produtoRepository;
        this.ajusteManualRepository = ajusteManualRepository;
        this.objectMapper = objectMapper;
    }

    private record Oferta(UUID fornecedorId, BigDecimal precoUnitario) {}

    // Critério de oferta válida pra entrar no Mapa de Compra: item OK, sem indicação
    // de falta de estoque, e com preço extraído. Também usado por
    // CotacaoAjusteManualService pra validar se um ajuste manual (mover item pra
    // outro fornecedor) é possível — não duplicar esse critério em outro lugar.
    public boolean ofertaValida(CotacaoProdutoFornecedor cpf) {
        return cpf.getStatus() == StatusItem.OK && !cpf.isSemEstoque() && precoDaOferta(cpf) != null;
    }

    private BigDecimal precoDaOferta(CotacaoProdutoFornecedor cpf) {
        return cpf.getPrecoUnitarioCalculado() != null ? cpf.getPrecoUnitarioCalculado() : cpf.getPrecoInformado();
    }

    private MapaCompraResponse deserializarSnapshot(UUID cotacaoId, String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, MapaCompraResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Falha ao desserializar snapshot do Mapa de Compra, caindo pro recálculo ao vivo: cotacaoId={}",
                    cotacaoId, e);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public MapaCompraResponse gerar(UUID cotacaoId, CenarioSelecionado cenario) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);

        // Cotação já finalizada: devolve o snapshot congelado no momento da
        // finalização (CotacaoService.finalizar), não recalcula — a distribuição
        // exibida não deve mudar retroativamente se dado subjacente mudar depois
        // (fornecedor inativado, preço reconfirmado, etc). Ignora o `cenario` pedido
        // porque só existe uma distribuição final. Sem snapshot (cotação finalizada
        // antes da V18, ou falha de deserialização) cai pro recálculo ao vivo abaixo
        // como fallback.
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA && cotacao.getMapaFinalSnapshot() != null) {
            MapaCompraResponse snapshot = deserializarSnapshot(cotacaoId, cotacao.getMapaFinalSnapshot());
            if (snapshot != null) return snapshot;
        }

        List<CotacaoProduto> itens = cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId);
        if (itens.isEmpty()) {
            return new MapaCompraResponse(cenario, List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of(), false, List.of());
        }

        Map<UUID, Fornecedor> fornecedores = fornecedorRepository.findAll().stream()
                .collect(Collectors.toMap(Fornecedor::getId, f -> f));
        Map<UUID, Produto> produtos = produtoRepository.findAll().stream()
                .collect(Collectors.toMap(Produto::getId, p -> p));
        Map<UUID, BigDecimal> quantidadePorItem = itens.stream()
                .collect(Collectors.toMap(CotacaoProduto::getId, CotacaoProduto::getQuantidade));

        Map<UUID, List<Oferta>> ofertasPorItem = new LinkedHashMap<>();
        for (CotacaoProduto cp : itens) ofertasPorItem.put(cp.getId(), new ArrayList<>());
        Set<UUID> fornecedoresComOfertaValida = new LinkedHashSet<>();
        for (CotacaoProdutoFornecedor cpf : cpfRepository.findByCotacaoId(cotacaoId)) {
            if (!ofertaValida(cpf) || !fornecedores.containsKey(cpf.getFornecedorId())) continue;
            BigDecimal preco = precoDaOferta(cpf);
            ofertasPorItem.get(cpf.getCotacaoProdutoId()).add(new Oferta(cpf.getFornecedorId(), preco));
            fornecedoresComOfertaValida.add(cpf.getFornecedorId());
        }

        // Fornecedores com resposta válida mas cadastro incompleto — sempre
        // reportado, independente do cenário pedido, pra explicar ao operador por
        // que a Compra Equilibrada excluiu esses fornecedores (ver
        // MapaCompraResponse#fornecedoresComDadosPendentes e `equilibrada` abaixo).
        List<MapaCompraResponse.FornecedorPendente> fornecedoresComDadosPendentes = fornecedoresComOfertaValida.stream()
                .map(fornecedores::get)
                .filter(f -> f.getStatus() == FornecedorStatus.PENDENTE_DADOS)
                .map(f -> new MapaCompraResponse.FornecedorPendente(f.getId(), f.getNome()))
                .toList();

        Map<UUID, UUID> assignment = switch (cenario) {
            case MENOR_PRECO -> menorPreco(itens, ofertasPorItem);
            case MELHOR_PRAZO -> melhorPrazo(itens, ofertasPorItem, fornecedores);
            case EQUILIBRADA -> equilibrada(itens, ofertasPorItem, fornecedores, quantidadePorItem);
        };

        List<CotacaoAjusteManual> ajustes = ajusteManualRepository.findByCotacaoId(cotacaoId);
        Set<UUID> ajustadosManualmente = new HashSet<>();
        Set<UUID> removidosManualmente = new HashSet<>();
        for (CotacaoAjusteManual ajuste : ajustes) {
            UUID cpId = ajuste.getCotacaoProdutoId();
            UUID overrideFornecedorId = ajuste.getFornecedorIdOverride();
            if (overrideFornecedorId == null) {
                assignment.remove(cpId);
                removidosManualmente.add(cpId);
                continue;
            }
            boolean ofertaAindaValida = ofertasPorItem.getOrDefault(cpId, List.of()).stream()
                    .anyMatch(o -> o.fornecedorId().equals(overrideFornecedorId));
            if (ofertaAindaValida) {
                // Auto-cura: se o fornecedor não tem mais oferta válida (ex: foi
                // inativado depois do ajuste), o override é ignorado silenciosamente
                // e o item volta a seguir o algoritmo do cenário — não é erro.
                assignment.put(cpId, overrideFornecedorId);
                ajustadosManualmente.add(cpId);
            }
        }

        return montarResposta(cenario, itens, ofertasPorItem, assignment, fornecedores, produtos,
                ajustadosManualmente, removidosManualmente, !ajustes.isEmpty(), fornecedoresComDadosPendentes);
    }

    // ── Menor Preço: por item, menor preço unitário entre ofertas válidas,
    // ignorando pedido mínimo (seção 6.5). ──────────────────────────────────────

    private Map<UUID, UUID> menorPreco(List<CotacaoProduto> itens, Map<UUID, List<Oferta>> ofertasPorItem) {
        Map<UUID, UUID> assign = new LinkedHashMap<>();
        for (CotacaoProduto cp : itens) {
            ofertasPorItem.get(cp.getId()).stream()
                    .min(Comparator.comparing(Oferta::precoUnitario)
                            .thenComparing(o -> o.fornecedorId().toString()))
                    .ifPresent(o -> assign.put(cp.getId(), o.fornecedorId()));
        }
        return assign;
    }

    // ── Melhor Prazo: por item, menor prazo de entrega (heurística sobre texto
    // livre — ver PrazoEntregaParser), desempate por preço (seção 6.5). ─────────

    private Map<UUID, UUID> melhorPrazo(List<CotacaoProduto> itens, Map<UUID, List<Oferta>> ofertasPorItem,
                                         Map<UUID, Fornecedor> fornecedores) {
        Map<UUID, Integer> prazoCache = new HashMap<>();
        for (Fornecedor f : fornecedores.values()) {
            prazoCache.put(f.getId(), PrazoEntregaParser.extrairDias(f.getPrazoEntregaPadrao()));
        }
        Map<UUID, UUID> assign = new LinkedHashMap<>();
        for (CotacaoProduto cp : itens) {
            ofertasPorItem.get(cp.getId()).stream()
                    .min(Comparator
                            .comparing((Oferta o) -> prazoCache.get(o.fornecedorId()),
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Oferta::precoUnitario)
                            .thenComparing(o -> o.fornecedorId().toString()))
                    .ifPresent(o -> assign.put(cp.getId(), o.fornecedorId()));
        }
        return assign;
    }

    // ── Compra Equilibrada: concentra em menos fornecedores respeitando pedido
    // mínimo (seção 6.5). Fornecedores PENDENTE_DADOS ficam de fora — não têm
    // pedido mínimo conhecido (seção 3.2). Heurística gulosa, não é ótimo global
    // (o problema exato é NP-difícil e desproporcional pro tamanho de uma lista de
    // compras semanal de mercado): parte do Menor Preço, e pra cada fornecedor
    // usado abaixo do mínimo tenta primeiro "resgatar" movendo pra ele os itens
    // mais baratos de mover entre os que ele também oferta; se não der pra bater o
    // mínimo, remove esse fornecedor da rodada e realoca os itens dele. ─────────

    private Map<UUID, UUID> equilibrada(List<CotacaoProduto> itens, Map<UUID, List<Oferta>> ofertasPorItemOriginal,
                                         Map<UUID, Fornecedor> fornecedores, Map<UUID, BigDecimal> quantidadePorItem) {
        Map<UUID, List<Oferta>> ofertasPorItem = filtrarOfertasElegiveis(ofertasPorItemOriginal, fornecedores);
        Map<UUID, UUID> assign = menorPreco(itens, ofertasPorItem);

        int maxIteracoes = fornecedores.size() + 1;
        for (int iter = 0; iter < maxIteracoes; iter++) {
            Map<UUID, BigDecimal> totais = totalPorFornecedor(assign, ofertasPorItem, quantidadePorItem);

            UUID candidato = fornecedorAbaixoDoMinimo(totais, fornecedores);
            if (candidato == null) break; // estável

            BigDecimal minimo = fornecedores.get(candidato).getPedidoMinimoPadrao();
            boolean resgatado = tentarResgatar(candidato, minimo, totais.get(candidato),
                    itens, assign, ofertasPorItem, quantidadePorItem);
            if (resgatado) continue;

            removerERealocar(candidato, itens, assign, ofertasPorItem);
        }
        return assign;
    }

    // Descarta ofertas de fornecedores PENDENTE_DADOS (sem pedido mínimo conhecido).
    private Map<UUID, List<Oferta>> filtrarOfertasElegiveis(Map<UUID, List<Oferta>> ofertasPorItemOriginal,
                                                             Map<UUID, Fornecedor> fornecedores) {
        Map<UUID, List<Oferta>> ofertasPorItem = new LinkedHashMap<>();
        for (var entry : ofertasPorItemOriginal.entrySet()) {
            List<Oferta> elegiveis = entry.getValue().stream()
                    .filter(o -> {
                        Fornecedor f = fornecedores.get(o.fornecedorId());
                        return f != null && f.getStatus() != FornecedorStatus.PENDENTE_DADOS;
                    })
                    .collect(Collectors.toCollection(ArrayList::new));
            ofertasPorItem.put(entry.getKey(), elegiveis);
        }
        return ofertasPorItem;
    }

    // Primeiro fornecedor com total atribuído abaixo do próprio pedido mínimo, ou null
    // se todos já estão estáveis (mínimo batido ou sem mínimo configurado).
    private UUID fornecedorAbaixoDoMinimo(Map<UUID, BigDecimal> totais, Map<UUID, Fornecedor> fornecedores) {
        for (var e : totais.entrySet()) {
            Fornecedor f = fornecedores.get(e.getKey());
            BigDecimal minimo = f.getPedidoMinimoPadrao();
            if (minimo != null && minimo.compareTo(BigDecimal.ZERO) > 0 && e.getValue().compareTo(minimo) < 0) {
                return e.getKey();
            }
        }
        return null;
    }

    // Tenta mover pro candidato, em ordem do mais barato de mover pro mais caro, itens
    // que ele também oferta, até bater o pedido mínimo. Retorna true se conseguiu
    // ("resgatado") — o candidato permanece na rodada nesse caso.
    private boolean tentarResgatar(UUID candidato, BigDecimal minimo, BigDecimal totalInicial,
                                    List<CotacaoProduto> itens, Map<UUID, UUID> assign,
                                    Map<UUID, List<Oferta>> ofertasPorItem, Map<UUID, BigDecimal> quantidadePorItem) {
        List<Map.Entry<UUID, Oferta>> movimentaveis = new ArrayList<>();
        for (CotacaoProduto cp : itens) {
            if (candidato.equals(assign.get(cp.getId()))) continue;
            ofertasPorItem.get(cp.getId()).stream()
                    .filter(o -> o.fornecedorId().equals(candidato))
                    .findFirst()
                    .ifPresent(o -> movimentaveis.add(Map.entry(cp.getId(), o)));
        }
        movimentaveis.sort(Comparator.comparing(
                e -> custoDoSwitch(e.getKey(), e.getValue(), assign, ofertasPorItem)));

        BigDecimal totalAtual = totalInicial;
        for (var mov : movimentaveis) {
            if (totalAtual.compareTo(minimo) >= 0) break;
            assign.put(mov.getKey(), candidato);
            totalAtual = totalAtual.add(mov.getValue().precoUnitario().multiply(quantidadePorItem.get(mov.getKey())));
        }
        return totalAtual.compareTo(minimo) >= 0;
    }

    // Não deu pra resgatar: remove o candidato desta rodada e realoca os itens dele
    // pra próxima melhor oferta restante (ou deixa sem fornecedor, se não houver outra).
    private void removerERealocar(UUID candidato, List<CotacaoProduto> itens, Map<UUID, UUID> assign,
                                   Map<UUID, List<Oferta>> ofertasPorItem) {
        for (CotacaoProduto cp : itens) {
            if (!candidato.equals(assign.get(cp.getId()))) continue;
            List<Oferta> alternativas = ofertasPorItem.get(cp.getId()).stream()
                    .filter(o -> !o.fornecedorId().equals(candidato))
                    .toList();
            if (alternativas.isEmpty()) {
                assign.remove(cp.getId());
            } else {
                alternativas.stream()
                        .min(Comparator.comparing(Oferta::precoUnitario).thenComparing(o -> o.fornecedorId().toString()))
                        .ifPresent(melhor -> assign.put(cp.getId(), melhor.fornecedorId()));
            }
        }
        for (List<Oferta> lista : ofertasPorItem.values()) {
            lista.removeIf(o -> o.fornecedorId().equals(candidato));
        }
    }

    private Map<UUID, BigDecimal> totalPorFornecedor(Map<UUID, UUID> assign, Map<UUID, List<Oferta>> ofertasPorItem,
                                                       Map<UUID, BigDecimal> quantidadePorItem) {
        Map<UUID, BigDecimal> totais = new LinkedHashMap<>();
        for (var entry : assign.entrySet()) {
            UUID cotacaoProdutoId = entry.getKey();
            UUID fornecedorId = entry.getValue();
            ofertasPorItem.get(cotacaoProdutoId).stream()
                    .filter(o -> o.fornecedorId().equals(fornecedorId))
                    .findFirst()
                    .ifPresent(oferta -> totais.merge(fornecedorId,
                            oferta.precoUnitario().multiply(quantidadePorItem.get(cotacaoProdutoId)), BigDecimal::add));
        }
        return totais;
    }

    private BigDecimal custoDoSwitch(UUID cotacaoProdutoId, Oferta candidato, Map<UUID, UUID> assign,
                                      Map<UUID, List<Oferta>> ofertasPorItem) {
        UUID atual = assign.get(cotacaoProdutoId);
        if (atual == null) return candidato.precoUnitario().negate(); // item sem fornecedor: mover é sempre ganho
        return ofertasPorItem.get(cotacaoProdutoId).stream()
                .filter(o -> o.fornecedorId().equals(atual))
                .findFirst()
                .map(o -> candidato.precoUnitario().subtract(o.precoUnitario()))
                .orElse(candidato.precoUnitario());
    }

    // ── Montagem da resposta, comum aos 3 cenários. ──────────────────────────────

    private MapaCompraResponse montarResposta(CenarioSelecionado cenario, List<CotacaoProduto> itens,
                                               Map<UUID, List<Oferta>> ofertasPorItem, Map<UUID, UUID> assignment,
                                               Map<UUID, Fornecedor> fornecedores, Map<UUID, Produto> produtos,
                                               Set<UUID> ajustadosManualmente, Set<UUID> removidosManualmente,
                                               boolean temAjustesManuais,
                                               List<MapaCompraResponse.FornecedorPendente> fornecedoresComDadosPendentes) {
        Map<UUID, List<ItemDistribuicao>> itensPorFornecedor = new LinkedHashMap<>();
        List<ProdutoSemFornecedor> semFornecedor = new ArrayList<>();
        List<ItemRemovidoManualmente> removidos = new ArrayList<>();
        BigDecimal totalGeral = BigDecimal.ZERO;
        BigDecimal totalPior = BigDecimal.ZERO;

        for (CotacaoProduto cp : itens) {
            String nomeProduto = nomeProduto(cp, produtos);
            List<Oferta> ofertas = ofertasPorItem.get(cp.getId());

            if (!ofertas.isEmpty()) {
                BigDecimal piorPreco = ofertas.stream().map(Oferta::precoUnitario).max(Comparator.naturalOrder()).get();
                totalPior = totalPior.add(piorPreco.multiply(cp.getQuantidade()));
            }

            UUID fornecedorId = assignment.get(cp.getId());
            if (fornecedorId == null) {
                if (removidosManualmente.contains(cp.getId())) {
                    removidos.add(new ItemRemovidoManualmente(cp.getId(), nomeProduto, cp.getQuantidade(), cp.getUnidade()));
                } else {
                    semFornecedor.add(new ProdutoSemFornecedor(cp.getId(), nomeProduto, cp.getQuantidade(), cp.getUnidade()));
                }
                continue;
            }
            Oferta oferta = ofertas.stream().filter(o -> o.fornecedorId().equals(fornecedorId)).findFirst().orElseThrow();
            BigDecimal subtotal = oferta.precoUnitario().multiply(cp.getQuantidade());
            totalGeral = totalGeral.add(subtotal);
            BigDecimal menorPrecoOferta = ofertas.stream().map(Oferta::precoUnitario).min(Comparator.naturalOrder()).get();
            boolean menorPreco = oferta.precoUnitario().compareTo(menorPrecoOferta) == 0;

            itensPorFornecedor.computeIfAbsent(fornecedorId, k -> new ArrayList<>())
                    .add(new ItemDistribuicao(cp.getId(), nomeProduto, cp.getQuantidade(), cp.getUnidade(),
                            oferta.precoUnitario(), subtotal, menorPreco, ajustadosManualmente.contains(cp.getId())));
        }

        List<DistribuicaoFornecedor> distribuicoes = new ArrayList<>();
        for (var entry : itensPorFornecedor.entrySet()) {
            Fornecedor f = fornecedores.get(entry.getKey());
            BigDecimal totalFornecedor = entry.getValue().stream()
                    .map(ItemDistribuicao::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal minimo = f.getPedidoMinimoPadrao();
            boolean abaixo = minimo != null && minimo.compareTo(BigDecimal.ZERO) > 0 && totalFornecedor.compareTo(minimo) < 0;
            BigDecimal falta = abaixo ? minimo.subtract(totalFornecedor) : null;
            distribuicoes.add(new DistribuicaoFornecedor(
                    f.getId(), f.getNome(), entry.getValue(), totalFornecedor, minimo, abaixo, falta,
                    f.getPrazoEntregaPadrao(), f.getCondicaoPagamentoPadrao(),
                    f.getStatus() == FornecedorStatus.PENDENTE_DADOS));
        }
        distribuicoes.sort(Comparator.comparing(DistribuicaoFornecedor::fornecedorNome));

        BigDecimal economia = totalPior.subtract(totalGeral);
        return new MapaCompraResponse(cenario, distribuicoes, semFornecedor, totalGeral, economia,
                removidos, temAjustesManuais, fornecedoresComDadosPendentes);
    }

    private String nomeProduto(CotacaoProduto cp, Map<UUID, Produto> produtos) {
        if (cp.getProdutoId() != null && produtos.containsKey(cp.getProdutoId())) {
            return produtos.get(cp.getProdutoId()).getNome();
        }
        return cp.getTextoOriginal();
    }
}
