package com.prx.cotacao.cotacao.respostafornecedor.processor;

import com.prx.cotacao.cotacao.mensagem.service.PrecoReferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ContadoresConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemConferenciaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.ItemConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ItemConciliado;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ParserRespostaFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ParametroResolucaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ResolvedorFornecedorRespostaStrategy;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.ClassificacaoConferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Núcleo único de processamento de resposta de fornecedor (Prompt 15, Strategy real a
 * partir de um prompt posterior) — a única diferença legítima entre os canais Web e
 * WhatsApp é COMO o {@code fornecedorId} chega resolvido. Em vez de cada canal resolver
 * por fora e chamar este método já com o {@code fornecedorId} em mãos, este método
 * RECEBE um {@link ParametroResolucaoFornecedor} e despacha internamente, via
 * {@link ResolvedorFornecedorRespostaStrategy}, para o resolver certo (injetado
 * dinamicamente — Spring coleta todas as implementações, incluindo a de
 * {@code whatsapp.canal}, sem este núcleo precisar conhecer nenhuma classe concreta de
 * lá). A partir daí ambos os canais chamam este mesmo método.
 *
 * <p>Roda o pipeline inteiro de parsing + matching + classificação (reaproveitando
 * {@link ParserRespostaFornecedorService}, {@link ConciliacaoRespostaService},
 * {@link ClassificacaoConferenciaService}, {@link PrecoReferenciaService} sem
 * alterá-los) mas NUNCA persiste nada em {@code cotacao_produto_fornecedor} — isso
 * continua exclusivo de {@link ConfirmacaoRespostaService#confirmar}, o único gate de
 * persistência do sistema, para os dois canais, sem exceção. Único efeito colateral
 * persistido: {@code CotacaoFornecedor.status -> PROCESSADO} e
 * {@code textoRespostaPendente} (para permitir reabrir a Conferência depois, sem
 * depender de linhas já persistidas — ver V27).</p>
 */
@Service
public class RespostaFornecedorCoreService {

    private static final Logger log = LoggerFactory.getLogger(RespostaFornecedorCoreService.class);

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final CotacaoFornecedorRepository cotacaoFornecedorRepository;
    private final ParserRespostaFornecedorService parserResposta;
    private final ConciliacaoRespostaService conciliacaoResposta;
    private final ClassificacaoConferenciaService classificacaoConferencia;
    private final PrecoReferenciaService precoReferencia;
    private final Map<Class<? extends ParametroResolucaoFornecedor>, ResolvedorFornecedorRespostaStrategy> resolvedoresPorTipo;

    public RespostaFornecedorCoreService(CotacaoRepository cotacaoRepository,
                                          CotacaoProdutoRepository cotacaoProdutoRepository,
                                          CotacaoProdutoFornecedorRepository cpfRepository,
                                          CotacaoFornecedorRepository cotacaoFornecedorRepository,
                                          ParserRespostaFornecedorService parserResposta,
                                          ConciliacaoRespostaService conciliacaoResposta,
                                          ClassificacaoConferenciaService classificacaoConferencia,
                                          PrecoReferenciaService precoReferencia,
                                          List<ResolvedorFornecedorRespostaStrategy> resolvedores) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.cotacaoFornecedorRepository = cotacaoFornecedorRepository;
        this.parserResposta = parserResposta;
        this.conciliacaoResposta = conciliacaoResposta;
        this.classificacaoConferencia = classificacaoConferencia;
        this.precoReferencia = precoReferencia;
        // Collectors.toMap já falha rápido (Duplicate key) na subida do contexto se
        // dois beans um dia declararem o mesmo tipoAceito() — guarda de configuração,
        // não precisa de teste dedicado.
        this.resolvedoresPorTipo = resolvedores.stream()
                .collect(Collectors.toMap(ResolvedorFornecedorRespostaStrategy::tipoAceito, r -> r));
    }

    @Transactional
    public PreviewRespostaResponse processar(UUID cotacaoId, ParametroResolucaoFornecedor parametroResolucao, String texto) {
        // Dispatch primeiro — mesma ordem relativa de antes da introdução do Strategy
        // real, quando cada orquestrador de canal chamava seu resolver ANTES de invocar
        // este método (ou seja, antes do lookup de cotação e do check de FINALIZADA
        // abaixo). Preservar essa ordem é proposital: hoje o resolver do WhatsApp pode
        // criar Fornecedor/CotacaoFornecedor mesmo quando a cotação já está finalizada,
        // e só depois o 409 é lançado — comportamento pré-existente, não uma decisão
        // nova deste refactor.
        ResolvedorFornecedorRespostaStrategy resolvedor = resolvedoresPorTipo.get(parametroResolucao.getClass());
        if (resolvedor == null) {
            throw new IllegalStateException("Nenhum resolvedor registrado para " + parametroResolucao.getClass());
        }
        UUID fornecedorId = resolvedor.resolver(cotacaoId, parametroResolucao);

        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);

        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita novas respostas");
        }

        // Busca própria da entidade (o resolver já validou/criou o vínculo, mas este
        // SELECT continua indo direto no banco em vez de reaproveitar o retorno do
        // resolver) — pequena duplicação de SELECT indexado (UNIQUE(cotacao_id,
        // fornecedor_id), V15) aceita conscientemente: mantém o pipeline abaixo
        // desacoplado de qual Strategy rodou, e falha alto se algum bug futuro numa
        // Strategy deixar de garantir o vínculo, em vez de mutar o campo errado
        // silenciosamente.
        CotacaoFornecedor cf = cotacaoFornecedorRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Fornecedor " + fornecedorId + " não foi adicionado à cotação " + cotacaoId));

        List<CotacaoProduto> itensBase = cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId);
        List<ProdutoCatalogo> produtosBase = itensBase.stream()
                .map(cp -> new ProdutoCatalogo(cp.getId(), cp.getTextoOriginal()))
                .toList();
        Map<UUID, String> nomesPorItemBase = new HashMap<>();
        for (CotacaoProduto cp : itensBase) {
            nomesPorItemBase.put(cp.getId(), cp.getTextoOriginal());
        }

        ResultadoResposta resposta = parserResposta.parsear(texto);
        List<LinhaFornecedor> linhasValidas = resposta.linhas().stream()
                .filter(l -> !l.ignorada())
                .toList();

        List<ItemConciliado> conciliados = conciliacaoResposta.conciliarEnhanced(linhasValidas, produtosBase);
        ConciliacaoRespostaService.ResultadoAgrupamento agrupamento =
                conciliacaoResposta.agrupar(conciliados, produtosBase);

        // Preço de referência (B.3): mediana dos demais fornecedores JÁ CONFIRMADOS
        // nesta cotação para cada item — a Conferência roda fornecedor-por-fornecedor,
        // então "os outros" só pode vir do que já está persistido, não de outra
        // preview em memória. Item sem nenhuma referência disponível (ex.: primeiro
        // fornecedor confirmado) simplesmente não entra no mapa.
        List<CotacaoProdutoFornecedor> todasRespostasDaCotacao = cpfRepository.findByCotacaoId(cotacaoId);
        Map<UUID, BigDecimal> referenciaPorItemBase = new HashMap<>();
        for (CotacaoProduto cp : itensBase) {
            BigDecimal referencia = precoReferencia.referenciaParaItem(cp.getId(), fornecedorId, todasRespostasDaCotacao);
            if (referencia != null) {
                referenciaPorItemBase.put(cp.getId(), referencia);
            }
        }

        List<ItemConferencia> classificados =
                classificacaoConferencia.classificar(produtosBase, conciliados, agrupamento, referenciaPorItemBase);

        // Reconcilia com o que já está confirmado deste fornecedor nesta cotação (de uma
        // rodada anterior) — sem isso, o preview mostraria só os itens da mensagem atual,
        // escondendo itens já confirmados que não foram mencionados de novo.
        List<CotacaoProdutoFornecedor> existentes =
                cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId);
        classificados = classificacaoConferencia.mesclarComConfirmacoesAnteriores(
                produtosBase, classificados, existentes);

        List<ItemConferenciaResponse> itensResponse = classificados.stream()
                .map(item -> ItemConferenciaResponse.from(item, nomesPorItemBase.get(item.itemBaseId())))
                .toList();

        // Efeito colateral desta chamada: marca o fornecedor como PROCESSADO (mesmo se
        // já estava CONFIRMADO — reprocessar invalida a confirmação anterior, que
        // precisa ser refeita antes de liberar o próximo fornecedor. "Processado" ≠
        // "aprovado") e grava o texto bruto pendente (V27) — permite reabrir a
        // Conferência depois sem depender de linhas já persistidas, já que este método
        // NUNCA persiste em cotacao_produto_fornecedor (só POST .../confirmar faz
        // isso, para os dois canais).
        cf.setStatus(CotacaoFornecedorStatus.PROCESSADO);
        cf.setTextoRespostaPendente(texto);
        cotacaoFornecedorRepository.save(cf);

        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        cotacaoRepository.save(cotacao);

        log.info("Preview de resposta gerado: cotacaoId={}, fornecedorId={}, itens={}",
                cotacaoId, fornecedorId, itensResponse.size());

        return new PreviewRespostaResponse(ContadoresConferencia.from(classificados), itensResponse);
    }
}
