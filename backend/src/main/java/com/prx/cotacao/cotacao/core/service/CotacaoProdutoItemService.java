package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.cotacao.core.dto.AdicionarItemCotacaoRequest;
import com.prx.cotacao.cotacao.core.dto.EditarItemCotacaoRequest;
import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoConciliacao;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Adição/remoção/edição pontual de um item (CotacaoProduto) do grid unificado de
 * Entrada de Dados (Prompt 12) — usado tanto pelo fluxo Web quanto pelo fluxo WhatsApp,
 * que agora compartilham a mesma tela/grid.
 */
@Service
public class CotacaoProdutoItemService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final ProdutoRepository produtoRepository;
    private final ProdutoResolverService produtoResolver;
    private final CurrentUser currentUser;

    public CotacaoProdutoItemService(CotacaoRepository cotacaoRepository,
                                      CotacaoProdutoRepository cotacaoProdutoRepository,
                                      CotacaoProdutoFornecedorRepository cpfRepository,
                                      ProdutoRepository produtoRepository,
                                      ProdutoResolverService produtoResolver,
                                      CurrentUser currentUser) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.produtoRepository = produtoRepository;
        this.produtoResolver = produtoResolver;
        this.currentUser = currentUser;
    }

    // Soft delete incondicional (Prompt 12) — antes disso, excluir um item com
    // cotacao_produto_fornecedor vinculado era bloqueado (ConflictException). Agora
    // todo item, tenha ou não resposta de fornecedor confirmada, é soft-deleted: nunca
    // apagado fisicamente, some do Comparativo/Mapa (que passam a ler só itens vivos),
    // preservado para auditoria. cotacao_produto_fornecedor não é tocado — fica órfão,
    // ligado a um CotacaoProduto agora invisível.
    @Transactional
    public void remover(UUID cotacaoId, UUID cotacaoProdutoId) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita alteração de itens");
        }

        CotacaoProduto cp = buscarItemDaCotacao(cotacaoId, cotacaoProdutoId);
        if (cp.getRemovidoEm() != null) {
            throw new ResourceNotFoundException("Item não encontrado: " + cotacaoProdutoId);
        }

        cp.setRemovidoEm(OffsetDateTime.now());
        cp.setRemovidoPor(currentUser.usuarioId());
        cotacaoProdutoRepository.save(cp);
    }

    @Transactional
    public void editar(UUID cotacaoId, UUID cotacaoProdutoId, EditarItemCotacaoRequest request) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita alteração de itens");
        }

        CotacaoProduto cp = buscarItemDaCotacao(cotacaoId, cotacaoProdutoId);
        if (cp.getRemovidoEm() != null) {
            throw new ResourceNotFoundException("Item não encontrado: " + cotacaoProdutoId);
        }

        // Trava de edição pós-conferência (Prompt 12, spec 3.3): cotacao_produto_fornecedor
        // só é criado por ConfirmacaoRespostaService.confirmar — o único gate de
        // persistência do sistema, para os dois canais (Web e WhatsApp, unificados no
        // Prompt 15 em cima do mesmo RespostaFornecedorCoreService/preview) — a mera
        // existência de uma linha aqui já significa que o operador confirmou pelo menos
        // uma resposta de fornecedor pra este item na Conferência. Editar produto/
        // quantidade/unidade depois disso reabriria uma divergência silenciosa entre o
        // que o fornecedor informou e o que o item passou a pedir — por isso trava, em
        // vez de deixar seguir (como acontecia antes desta regra). Excluir continua
        // permitido (vira soft-delete, ver remover()).
        if (!cpfRepository.findByCotacaoProdutoId(cotacaoProdutoId).isEmpty()) {
            throw new ConflictException(
                    "Este item já tem resposta de fornecedor confirmada e não pode mais ser editado. "
                            + "Remova o item e adicione novamente, se necessário.");
        }

        cp.setQuantidade(request.quantidade());
        cp.setUnidade(request.unidade());

        UUID novoProdutoId = null;
        if (request.produtoId() != null) {
            // findById já filtra por tenant (Hibernate filter + RLS) — um produtoId de
            // outro tenant simplesmente não é encontrado, mesma defesa usada por
            // buscarItemDaCotacao logo abaixo.
            Produto produto = produtoRepository.findById(request.produtoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: " + request.produtoId()));
            novoProdutoId = produto.getId();
        } else if (request.nomeProdutoLivre() != null && !request.nomeProdutoLivre().isBlank()) {
            // Item sem produto identificado (grid "Sem produto identificado") — o
            // operador digitou um nome sem match, em vez de escolher um já cadastrado.
            // Mesmo pipeline resolver-ou-criar de adicionar()/CotacaoListaService,
            // reaproveitado via ProdutoResolverService. Não mexe em textoOriginal: pra
            // um item que veio de paste, esse texto continua sendo o registro de
            // auditoria da linha original colada, não o nome do produto.
            List<ProdutoCatalogo> catalogoDinamico = produtoResolver.catalogoDinamicoDoTenant();
            ResultadoConciliacao conc = produtoResolver.resolverOuCriarProduto(
                    catalogoDinamico, request.nomeProdutoLivre(), request.unidade(), request.nomeProdutoLivre());
            novoProdutoId = conc.produtoIdEncontrado();
        }

        if (novoProdutoId != null) {
            // idx_cotacao_produto_unico_por_produto (V6/V25) só permite um CotacaoProduto
            // vivo por produtoId dentro da mesma cotação — checa aqui pra devolver um
            // erro claro em vez de deixar estourar como violação de constraint genérica.
            UUID produtoIdParaChecagem = novoProdutoId;
            cotacaoProdutoRepository.findByCotacaoIdAndProdutoIdAndRemovidoEmIsNull(cotacaoId, produtoIdParaChecagem)
                    .filter(outro -> !outro.getId().equals(cp.getId()))
                    .ifPresent(outro -> {
                        throw new ConflictException(
                                "Este produto já está associado a outra linha desta cotação.");
                    });
            cp.setProdutoId(novoProdutoId);
        }
        cotacaoProdutoRepository.save(cp);
    }

    // Adição manual de item no grid (Prompt 12) — usada pelo botão "+ Adicionar
    // Produto". Exatamente um de produtoId/nomeProdutoLivre deve vir preenchido:
    // produtoId reaproveita um produto já cadastrado; nomeProdutoLivre passa pelo mesmo
    // pipeline resolver-ou-criar-produto do parser de lista/WhatsApp (decisão de
    // produto confirmada: o catálogo pode nascer também de uma digitação manual, não só
    // do matching automático de uma lista colada).
    @Transactional
    public ItemListaResponse adicionar(UUID cotacaoId, AdicionarItemCotacaoRequest request) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita novos itens");
        }

        boolean temProdutoId = request.produtoId() != null;
        boolean temNomeLivre = request.nomeProdutoLivre() != null && !request.nomeProdutoLivre().isBlank();
        if (temProdutoId == temNomeLivre) {
            throw new ConflictException(
                    "Informe exatamente um: produtoId (produto já cadastrado) ou nomeProdutoLivre (nome novo).");
        }

        // Item manual nunca veio de um paste, então não tem um "texto original" de
        // verdade — mas o operador pode digitar uma anotação livre no campo mesmo
        // assim (ex.: "combinar marca com fornecedor X"). Nulo/ausente vira "".
        String textoOriginal = request.textoOriginal() != null ? request.textoOriginal() : "";

        UUID produtoId;
        if (temProdutoId) {
            // findById já filtra por tenant (Hibernate filter + RLS).
            Produto produto = produtoRepository.findById(request.produtoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: " + request.produtoId()));
            produtoId = produto.getId();
        } else {
            List<ProdutoCatalogo> catalogoDinamico = produtoResolver.catalogoDinamicoDoTenant();
            ResultadoConciliacao conc = produtoResolver.resolverOuCriarProduto(
                    catalogoDinamico, request.nomeProdutoLivre(), request.unidade(), request.nomeProdutoLivre());
            produtoId = conc.produtoIdEncontrado();
        }

        // idx_cotacao_produto_unico_por_produto (V6/V25) — mesma checagem de editar().
        cotacaoProdutoRepository.findByCotacaoIdAndProdutoIdAndRemovidoEmIsNull(cotacaoId, produtoId)
                .ifPresent(outro -> {
                    throw new ConflictException("Este produto já está associado a outra linha desta cotação.");
                });

        Integer maxOrdem = cotacaoProdutoRepository.findMaxOrdemByCotacaoId(cotacaoId);
        CotacaoProduto cp = new CotacaoProduto();
        cp.setCotacaoId(cotacaoId);
        cp.setOrdem(maxOrdem != null ? maxOrdem + 1 : 1);
        cp.setProdutoId(produtoId);
        cp.setTextoOriginal(textoOriginal);
        cp.setQuantidade(request.quantidade());
        cp.setUnidade(request.unidade());
        CotacaoProduto saved = cotacaoProdutoRepository.save(cp);

        // Mesma transição de status que processarLista/importarTexto já fazem — um
        // item novo tira a cotação de RASCUNHO, seja ele vindo de paste ou digitado
        // manualmente no grid.
        cotacao.setStatus(CotacaoStatus.EM_ANDAMENTO);
        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        cotacaoRepository.save(cotacao);

        return new ItemListaResponse(saved.getId(), saved.getTextoOriginal(), saved.getQuantidade(),
                saved.getUnidade(), saved.getProdutoId(), BigDecimal.ONE, true, false);
    }

    private CotacaoProduto buscarItemDaCotacao(UUID cotacaoId, UUID cotacaoProdutoId) {
        CotacaoProduto cp = cotacaoProdutoRepository.findById(cotacaoProdutoId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: " + cotacaoProdutoId));
        if (!cp.getCotacaoId().equals(cotacaoId)) {
            // cotacaoProdutoId existe mas pertence a outra cotação do mesmo tenant
            // (cross-tenant já é bloqueado pelo RLS antes de chegar aqui) — trata como
            // não encontrado, mesmo padrão de AvisoService.resolver().
            throw new ResourceNotFoundException("Item não encontrado: " + cotacaoProdutoId);
        }
        return cp;
    }
}
