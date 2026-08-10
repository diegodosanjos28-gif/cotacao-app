package com.prx.cotacao.cotacao.mapacompra.service;

import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.shared.security.CurrentUser;
import com.prx.cotacao.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.mapacompra.repository.CotacaoAjusteManualRepository;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Ajuste manual da distribuição do Mapa de Compra: mover um item pra outro
 * fornecedor, removê-lo da ordem, ou restaurar (voltar a seguir o algoritmo do
 * cenário). Persistido por item (cotacao_produto_id), não por cenário — sobrevive à
 * troca de cenário (ver TODO-PROTOTYPE.md item 2). Aplicado por cima do resultado dos
 * 3 algoritmos em MapaCompraService.gerar().
 */
@Service
public class CotacaoAjusteManualService {

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final CotacaoAjusteManualRepository ajusteRepository;
    private final MapaCompraService mapaCompraService;
    private final CurrentUser currentUser;

    public CotacaoAjusteManualService(CotacaoRepository cotacaoRepository,
                                       CotacaoProdutoRepository cotacaoProdutoRepository,
                                       CotacaoProdutoFornecedorRepository cpfRepository,
                                       CotacaoAjusteManualRepository ajusteRepository,
                                       MapaCompraService mapaCompraService,
                                       CurrentUser currentUser) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.ajusteRepository = ajusteRepository;
        this.mapaCompraService = mapaCompraService;
        this.currentUser = currentUser;
    }

    @Transactional
    public void mover(UUID cotacaoId, UUID cotacaoProdutoId, UUID fornecedorId) {
        buscarItemDeCotacaoNaoFinalizada(cotacaoId, cotacaoProdutoId);

        boolean ofertaValida = cpfRepository.findByCotacaoProdutoIdAndFornecedorId(cotacaoProdutoId, fornecedorId)
                .filter(mapaCompraService::ofertaValida)
                .isPresent();
        if (!ofertaValida) {
            throw new IllegalArgumentException(
                    "Este fornecedor não tem oferta válida para este item — não é possível mover.");
        }

        upsert(cotacaoId, cotacaoProdutoId, fornecedorId);
    }

    @Transactional
    public void remover(UUID cotacaoId, UUID cotacaoProdutoId) {
        buscarItemDeCotacaoNaoFinalizada(cotacaoId, cotacaoProdutoId);

        boolean temAlgumaOfertaValida = cpfRepository.findByCotacaoProdutoId(cotacaoProdutoId).stream()
                .anyMatch(mapaCompraService::ofertaValida);
        if (!temAlgumaOfertaValida) {
            throw new IllegalArgumentException(
                    "Este item não tem nenhuma oferta válida — já está fora da distribuição, nada a remover.");
        }

        upsert(cotacaoId, cotacaoProdutoId, null);
    }

    @Transactional
    public void restaurar(UUID cotacaoId, UUID cotacaoProdutoId) {
        buscarItemDeCotacaoNaoFinalizada(cotacaoId, cotacaoProdutoId);
        ajusteRepository.deleteByCotacaoIdAndCotacaoProdutoId(cotacaoId, cotacaoProdutoId);
    }

    @Transactional
    public void restaurarTudo(UUID cotacaoId) {
        buscarCotacaoNaoFinalizada(cotacaoId);
        ajusteRepository.deleteByCotacaoId(cotacaoId);
    }

    private void upsert(UUID cotacaoId, UUID cotacaoProdutoId, UUID fornecedorIdOverride) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        ajusteRepository.upsert(tenantId, cotacaoId, cotacaoProdutoId, fornecedorIdOverride, currentUser.usuarioId());
    }

    private Cotacao buscarCotacaoNaoFinalizada(UUID cotacaoId) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita ajuste manual de distribuição");
        }
        return cotacao;
    }

    private void buscarItemDeCotacaoNaoFinalizada(UUID cotacaoId, UUID cotacaoProdutoId) {
        buscarCotacaoNaoFinalizada(cotacaoId);
        CotacaoProduto cp = cotacaoProdutoRepository.findById(cotacaoProdutoId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: " + cotacaoProdutoId));
        if (!cp.getCotacaoId().equals(cotacaoId)) {
            // cotacaoProdutoId existe mas pertence a outra cotação do mesmo tenant
            // (cross-tenant já é bloqueado pelo RLS antes de chegar aqui) — trata como
            // não encontrado, mesmo padrão de CotacaoProdutoItemService.
            throw new ResourceNotFoundException("Item não encontrado: " + cotacaoProdutoId);
        }
    }
}
