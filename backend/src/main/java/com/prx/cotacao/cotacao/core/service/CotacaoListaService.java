package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.cotacao.core.dto.ImportarTextoItemResponse;
import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaParseada;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoConciliacao;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

@Service
public class CotacaoListaService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final ParserListaProdutosService parserListaProdutos;
    private final ProdutoResolverService produtoResolver;

    public CotacaoListaService(CotacaoRepository cotacaoRepository,
                                CotacaoProdutoRepository cotacaoProdutoRepository,
                                CotacaoProdutoFornecedorRepository cpfRepository,
                                ParserListaProdutosService parserListaProdutos,
                                ProdutoResolverService produtoResolver) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.parserListaProdutos = parserListaProdutos;
        this.produtoResolver = produtoResolver;
    }

    // Itens já persistidos da cotação, no formato do textarea (POST .../lista os cria;
    // isto só lê) — usado pelo frontend pra reabrir a tela de Entrada já com a lista
    // base preenchida, em vez de aparecer vazia sempre que a página é remontada
    // (achado: ListaProdutosSection nunca hidratava a partir do servidor).
    @Transactional(readOnly = true)
    public List<ItemListaResponse> listar(UUID cotacaoId) {
        cotacaoRepository.findByIdOrThrow(cotacaoId);
        Set<UUID> comRespostaConfirmada = itensComRespostaConfirmada(cotacaoId);
        return cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId).stream()
                .map(cp -> new ItemListaResponse(
                        cp.getId(), cp.getTextoOriginal(), cp.getQuantidade(), cp.getUnidade(),
                        cp.getProdutoId(), cp.getProdutoId() != null ? BigDecimal.ONE : BigDecimal.ZERO,
                        cp.getProdutoId() != null, comRespostaConfirmada.contains(cp.getId())))
                .toList();
    }

    @Transactional
    public List<ItemListaResponse> processarLista(UUID cotacaoId, String texto) {
        // findById usa Hibernate filter — só retorna cotação do tenant atual
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);

        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita novos itens");
        }

        List<LinhaParseada> linhas = parserListaProdutos.parsear(texto);
        List<ProdutoCatalogo> catalogoDinamico = produtoResolver.catalogoDinamicoDoTenant();

        int ordemBase = cotacaoProdutoRepository.findMaxOrdemByCotacaoId(cotacaoId) != null
                ? cotacaoProdutoRepository.findMaxOrdemByCotacaoId(cotacaoId) + 1 : 1;

        // Dedup: reenviar a lista (ex.: usuário clica "Adicionar itens" duas vezes com
        // listas sobrepostas, ou o WhatsApp acumula várias mensagens antes da revisão)
        // não pode duplicar CotacaoProduto. Se já existe um item VIVO dessa cotação para
        // o mesmo produtoId (produto já reconhecido no catálogo) ou, na ausência de
        // match, para o mesmo textoOriginal, atualiza esse item em vez de inserir um
        // novo — preservando id/ordem originais. Só considera itens vivos: um produtoId
        // ou textoOriginal de uma linha soft-deleted (Prompt 12) não deve reviver a
        // linha morta silenciosamente. Isso é intencionalmente best-effort: uma linha
        // que não casou com produto nem com texto de uma submissão anterior (ex.: texto
        // reformulado) ainda pode duplicar; não há chave estável melhor disponível sem
        // uma decisão de produto explícita.
        //
        // Este upsert-por-match é usado tanto pelo paste manual quanto pela ingestão de
        // mensagens WhatsApp (WhatsappListaProdutosService) — é intencional e NÃO deve
        // ser confundido com importarTexto() (Prompt 12), que é sempre-append e nunca
        // atualiza uma linha existente (ver javadoc de importarTexto).
        List<CotacaoProduto> existentes = cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId);
        Map<UUID, CotacaoProduto> existentesPorProduto = new HashMap<>();
        Map<String, CotacaoProduto> existentesPorTexto = new HashMap<>();
        for (CotacaoProduto ex : existentes) {
            if (ex.getProdutoId() != null) {
                existentesPorProduto.put(ex.getProdutoId(), ex);
            } else {
                existentesPorTexto.putIfAbsent(ex.getTextoOriginal(), ex);
            }
        }

        Set<UUID> comRespostaConfirmada = itensComRespostaConfirmada(cotacaoId);
        List<ItemListaResponse> resultado = new ArrayList<>();

        for (LinhaParseada linha : linhas) {
            UUID produtoIdEncontrado = null;
            BigDecimal score = BigDecimal.ZERO;
            boolean matched = false;

            if (linha.parseOk()) {
                ResultadoConciliacao conc = produtoResolver.resolverOuCriarProduto(
                        catalogoDinamico, linha.nomeProduto(), linha.unidade(), linha.textoOriginal());
                produtoIdEncontrado = conc.produtoIdEncontrado();
                score = conc.score();
                matched = true;
            }

            CotacaoProduto cp = matched ? existentesPorProduto.get(produtoIdEncontrado) : null;
            if (cp == null) {
                cp = existentesPorTexto.get(linha.textoOriginal());
            }

            boolean novo = (cp == null);
            if (novo) {
                cp = new CotacaoProduto();
                cp.setCotacaoId(cotacaoId);
                cp.setOrdem(ordemBase++);
            }

            cp.setTextoOriginal(linha.textoOriginal());
            cp.setQuantidade(linha.quantidade());
            cp.setUnidade(linha.unidade());
            if (matched) {
                cp.setProdutoId(produtoIdEncontrado);
            }

            CotacaoProduto saved = cotacaoProdutoRepository.save(cp);

            // Mantém os índices de dedup atualizados também dentro desta mesma
            // submissão, para não duplicar quando a própria lista colada repete a
            // mesma linha/produto mais de uma vez.
            if (novo) {
                if (matched) {
                    existentesPorProduto.put(produtoIdEncontrado, saved);
                } else {
                    existentesPorTexto.putIfAbsent(linha.textoOriginal(), saved);
                }
            }

            resultado.add(new ItemListaResponse(
                    saved.getId(), linha.textoOriginal(), linha.quantidade(),
                    linha.unidade(), produtoIdEncontrado, score, matched,
                    comRespostaConfirmada.contains(saved.getId())));
        }

        cotacao.setStatus(CotacaoStatus.EM_ANDAMENTO);
        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        cotacaoRepository.save(cotacao);

        return resultado;
    }

    /**
     * Importação via modal "Colar do WhatsApp" (Prompt 12) — diferente de
     * {@link #processarLista}, NUNCA atualiza uma linha existente: cada linha parseada
     * ou vira uma {@link CotacaoProduto} nova, ou (se o produto resolvido já está vivo
     * na cotação) é ignorada e reportada como {@code duplicado}, sem abortar o resto da
     * importação. Reaproveita o mesmo parser e o mesmo pipeline
     * "resolver-ou-criar-produto" de processarLista via {@link ProdutoResolverService},
     * mas não sua lógica de upsert-por-match — decisão de produto confirmada: o
     * operador decide manualmente se uma linha duplicada deve ser removida, não há
     * merge automático.
     */
    @Transactional
    public List<ImportarTextoItemResponse> importarTexto(UUID cotacaoId, String texto) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);

        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita novos itens");
        }

        List<LinhaParseada> linhas = parserListaProdutos.parsear(texto);
        List<ProdutoCatalogo> catalogoDinamico = produtoResolver.catalogoDinamicoDoTenant();

        Set<UUID> produtosJaVivosNaCotacao = new HashSet<>(
                cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId).stream()
                        .map(CotacaoProduto::getProdutoId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()));

        int ordem = cotacaoProdutoRepository.findMaxOrdemByCotacaoId(cotacaoId) != null
                ? cotacaoProdutoRepository.findMaxOrdemByCotacaoId(cotacaoId) + 1 : 1;

        List<ImportarTextoItemResponse> resultado = new ArrayList<>();

        for (LinhaParseada linha : linhas) {
            UUID produtoId = null;
            boolean matched = false;
            if (linha.parseOk()) {
                ResultadoConciliacao conc = produtoResolver.resolverOuCriarProduto(
                        catalogoDinamico, linha.nomeProduto(), linha.unidade(), linha.textoOriginal());
                produtoId = conc.produtoIdEncontrado();
                matched = true;
            }

            if (matched && produtosJaVivosNaCotacao.contains(produtoId)) {
                // Produto já está vivo na cotação — não insere pra não duplicar (a
                // unique index parcial de V6/V25 rejeitaria de qualquer forma), e não
                // tenta casar/mesclar automaticamente com a linha existente: reporta
                // como duplicado e segue para a próxima linha do paste.
                resultado.add(new ImportarTextoItemResponse(
                        null, linha.textoOriginal(), linha.quantidade(), linha.unidade(),
                        produtoId, true, true));
                continue;
            }

            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setOrdem(ordem++);
            cp.setTextoOriginal(linha.textoOriginal());
            cp.setQuantidade(linha.quantidade());
            cp.setUnidade(linha.unidade());
            cp.setProdutoId(produtoId);
            CotacaoProduto saved = cotacaoProdutoRepository.save(cp);

            if (matched) {
                produtosJaVivosNaCotacao.add(produtoId);
            }

            resultado.add(new ImportarTextoItemResponse(
                    saved.getId(), linha.textoOriginal(), linha.quantidade(), linha.unidade(),
                    produtoId, matched, false));
        }

        cotacao.setStatus(CotacaoStatus.EM_ANDAMENTO);
        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        cotacaoRepository.save(cotacao);

        return resultado;
    }

    private Set<UUID> itensComRespostaConfirmada(UUID cotacaoId) {
        return cpfRepository.findByCotacaoId(cotacaoId).stream()
                .map(CotacaoProdutoFornecedor::getCotacaoProdutoId)
                .collect(Collectors.toSet());
    }
}
