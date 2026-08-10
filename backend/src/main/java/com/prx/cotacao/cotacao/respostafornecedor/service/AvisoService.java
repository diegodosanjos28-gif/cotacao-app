package com.prx.cotacao.cotacao.respostafornecedor.service;

import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.security.CurrentUser;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;

@Service
public class AvisoService {

    private static final Logger log = LoggerFactory.getLogger(AvisoService.class);
    private static final long MINIMO_FORNECEDORES_PARA_ATUALIZAR_SUGESTAO = 2;

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final ProdutoRepository produtoRepository;
    private final CurrentUser currentUser;

    public AvisoService(CotacaoRepository cotacaoRepository,
                        CotacaoProdutoFornecedorRepository cpfRepository,
                        CotacaoProdutoRepository cotacaoProdutoRepository,
                        ProdutoRepository produtoRepository,
                        CurrentUser currentUser) {
        this.cotacaoRepository = cotacaoRepository;
        this.cpfRepository = cpfRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.produtoRepository = produtoRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public CotacaoProdutoFornecedor resolver(UUID cotacaoId, UUID cpfId, Integer embalagemQtd) {
        // Cotacao é tenant-filtrada (TenantAuditEntity) — dispara 404 se não for do
        // tenant do caller. CotacaoProdutoFornecedor NÃO tem Hibernate Filter (é
        // isolada só via RLS na cadeia cotacao_produto->cotacao), então sem essa
        // validação de posse explícita, cpfId de qualquer cotação/tenant seria aceito.
        cotacaoRepository.findByIdOrThrow(cotacaoId);

        CotacaoProdutoFornecedor cpf = cpfRepository.findById(cpfId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: " + cpfId));

        CotacaoProduto cotacaoProduto = cotacaoProdutoRepository.findById(cpf.getCotacaoProdutoId())
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado: " + cpfId));

        if (!cotacaoProduto.getCotacaoId().equals(cotacaoId)) {
            // cpfId existe mas pertence a outra cotação do mesmo tenant (cross-tenant
            // já é bloqueado pelo RLS no banco antes de chegar aqui) — trata como não
            // encontrado, não como 403, para não vazar a existência do recurso.
            throw new ResourceNotFoundException("Item não encontrado: " + cpfId);
        }

        // Snapshot imutável: só grava se ainda não foi confirmado. O check-then-act
        // abaixo ainda não é atômico por si só, mas o campo `versao` (@Version) na
        // entidade torna o saveAndFlush() logo abaixo atômico contra escrita
        // concorrente: se duas chamadas concorrentes pro mesmo cpfId lerem
        // embalagemQtdConfirmada=null na mesma versão, a segunda a fazer flush recebe
        // OptimisticLockingFailureException (ver GlobalExceptionHandler) em vez de
        // silenciosamente sobrescrever o snapshot da primeira. Achado do
        // db-schema-guardian em revisão de 2026-07-09, corrigido em 2026-07-11.
        if (cpf.getEmbalagemQtdConfirmada() != null) {
            throw new ConflictException("Embalagem já confirmada para este item");
        }

        // Calcula preço unitário real antes de gravar o snapshot
        if (embalagemQtd > 0 && cpf.getPrecoInformado() != null) {
            java.math.BigDecimal precoUnitario = cpf.getPrecoInformado()
                    .divide(java.math.BigDecimal.valueOf(embalagemQtd), 4, java.math.RoundingMode.HALF_UP);
            cpf.setPrecoUnitarioCalculado(precoUnitario);
        }

        cpf.setEmbalagemQtdConfirmada(embalagemQtd);
        cpf.setStatus(StatusItem.OK);
        cpf.setResolvidoPor(currentUser.usuarioId());
        // flush imediato (em vez de save() simples) força o UPDATE ... WHERE versao=?
        // a rodar agora — se perder a corrida otimista, lança aqui, não silenciosamente
        // no commit da transação.
        cpfRepository.saveAndFlush(cpf);
        log.info("Aviso resolvido: cpfId={}, embalagemQtd={}, resolvidoPor={}",
                cpfId, embalagemQtd, currentUser.usuarioId());

        // Atualiza embalagem_qtd_sugerida no produto APENAS se ≥ 2 fornecedores concordam.
        // Regra 2 do CLAUDE.md: nunca sobrescrever a partir de um único fornecedor.
        UUID cotacaoProdutoId = cpf.getCotacaoProdutoId();
        long acordos = cpfRepository.countFornecedoresComMesmaEmbalagem(cotacaoProdutoId, embalagemQtd);

        if (acordos >= MINIMO_FORNECEDORES_PARA_ATUALIZAR_SUGESTAO) {
            cotacaoProdutoRepository.findById(cotacaoProdutoId).ifPresent(cp -> {
                if (cp.getProdutoId() != null) {
                    produtoRepository.findById(cp.getProdutoId()).ifPresent(produto -> {
                        produto.setEmbalagemQtdSugerida(embalagemQtd);
                        produtoRepository.save(produto);
                    });
                }
            });
        }

        return cpf;
    }
}
