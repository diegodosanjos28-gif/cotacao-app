package com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats;

import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoConciliacao;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ParametroResolucaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ResolvedorFornecedorRespostaStrategy;
import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.enums.OrigemCadastro;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Resolução de fornecedor pro canal WhatsApp (Prompt 15) — não há dropdown, o nome vem
 * extraído da 1ª linha da mensagem ({@link ResultadoResposta#nomeFornecedor()}).
 * Casa por similaridade contra os fornecedores do tenant (mesmo motor de
 * {@link MatchingProdutoService#calcSimilaridade} usado pra produto); se não encontrar,
 * cria um novo com {@code status=PENDENTE_DADOS}/{@code origemCadastro=WHATSAPP_AUTO}
 * (doc técnica §10.5). Também garante o vínculo {@code CotacaoFornecedor} — no canal
 * Web isso é feito manualmente pelo operador via {@code POST /cotacoes/{id}/fornecedores}
 * antes de colar a resposta; aqui não há esse passo prévio, então esta Strategy cria o
 * vínculo se ainda não existir. Despachado dinamicamente por
 * {@link RespostaFornecedorCoreService#processar}
 * via {@link ResolvedorFornecedorRespostaStrategy}.
 */
@Service
public class ResolvedorFornecedorWhatsappStrategy implements ResolvedorFornecedorRespostaStrategy {

    private static final String NOME_FORNECEDOR_PADRAO = "Fornecedor WhatsApp";

    private final FornecedorRepository fornecedorRepository;
    private final CotacaoFornecedorRepository cotacaoFornecedorRepository;
    private final MatchingProdutoService matchingProduto;

    public ResolvedorFornecedorWhatsappStrategy(FornecedorRepository fornecedorRepository,
                                                 CotacaoFornecedorRepository cotacaoFornecedorRepository,
                                                 MatchingProdutoService matchingProduto) {
        this.fornecedorRepository = fornecedorRepository;
        this.cotacaoFornecedorRepository = cotacaoFornecedorRepository;
        this.matchingProduto = matchingProduto;
    }

    @Override
    public UUID resolver(UUID cotacaoId, ParametroResolucaoFornecedor parametro) {
        String nomeFornecedorExtraido = ((ParametroResolucaoWhatsapp) parametro).nomeFornecedorExtraido();
        Fornecedor fornecedor = resolverOuCriarFornecedor(nomeFornecedorExtraido);
        cotacaoFornecedorRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedor.getId())
                .orElseGet(() -> criarCotacaoFornecedor(cotacaoId, fornecedor.getId()));
        return fornecedor.getId();
    }

    @Override
    public Class<? extends ParametroResolucaoFornecedor> tipoAceito() {
        return ParametroResolucaoWhatsapp.class;
    }

    /**
     * Casa o nome do fornecedor (1ª linha da mensagem) por similaridade contra os
     * fornecedores do TENANT (não do usuário — fornecedor é cadastro compartilhado).
     * Reaproveita {@link MatchingProdutoService#encontrarMelhorCandidato} sem método
     * novo — {@link ProdutoCatalogo} é genérico (id+nome), não específico de produto.
     */
    private Fornecedor resolverOuCriarFornecedor(String nomeFornecedor) {
        String nome = (nomeFornecedor == null || nomeFornecedor.isBlank()) ? NOME_FORNECEDOR_PADRAO : nomeFornecedor;

        List<ProdutoCatalogo> fornecedoresDoTenant = fornecedorRepository.findAll().stream()
                .map(f -> new ProdutoCatalogo(f.getId(), f.getNome()))
                .toList();
        ResultadoConciliacao match = matchingProduto.encontrarMelhorCandidato(fornecedoresDoTenant, nome);
        if (match.matched()) {
            return fornecedorRepository.findById(match.produtoIdEncontrado())
                    .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + match.produtoIdEncontrado()));
        }

        Fornecedor novo = new Fornecedor();
        // tenantId setado automaticamente pelo TenantAuditEntityListener
        novo.setNome(nome);
        novo.setStatus(FornecedorStatus.PENDENTE_DADOS);
        novo.setOrigemCadastro(OrigemCadastro.WHATSAPP_AUTO);
        return fornecedorRepository.save(novo);
    }

    private CotacaoFornecedor criarCotacaoFornecedor(UUID cotacaoId, UUID fornecedorId) {
        CotacaoFornecedor cf = new CotacaoFornecedor();
        cf.setCotacaoId(cotacaoId);
        cf.setFornecedorId(fornecedorId);
        int proximaOrdem = cotacaoFornecedorRepository.findTopByCotacaoIdOrderByOrdemDesc(cotacaoId)
                .map(anterior -> anterior.getOrdem() + 1).orElse(1);
        cf.setOrdem(proximaOrdem);
        cf.setStatus(CotacaoFornecedorStatus.PENDENTE);
        return cotacaoFornecedorRepository.save(cf);
    }
}
