package com.prx.cotacao.cotacao.respostafornecedor.service;

import com.prx.cotacao.cotacao.mensagem.service.PrecoReferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ConfirmarRespostaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ResolucaoItemRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.TipoResolucao;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.entity.ItemConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.CotacaoFornecedorStatus;
import com.prx.cotacao.cotacao.respostafornecedor.enums.MotivoConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusConferencia;
import com.prx.cotacao.cotacao.respostafornecedor.enums.StatusItem;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ItemConciliado;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.MatchingProdutoService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ParserRespostaFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ProdutoCatalogo;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.CandidatoResposta;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

/**
 * Persiste de fato a resposta de um fornecedor em cotacao_produto_fornecedor, depois
 * que o operador confirma a Conferência (POST .../confirmar). Recomputa o MESMO
 * pipeline usado no preview ({@link FornecedorRespostaService#gerarPreview}) a partir
 * do texto reenviado — não há estado de rascunho persistido entre as duas chamadas
 * (decisão B do plano). Ver
 * /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md seções 1, 3 e 4
 * (épico B5).
 */
@Service
public class ConfirmacaoRespostaService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmacaoRespostaService.class);

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final CotacaoProdutoFornecedorRepository cpfRepository;
    private final CotacaoFornecedorRepository cotacaoFornecedorRepository;
    private final FornecedorRepository fornecedorRepository;
    private final ParserRespostaFornecedorService parserResposta;
    private final ConciliacaoRespostaService conciliacaoResposta;
    private final ClassificacaoConferenciaService classificacaoConferencia;
    private final MatchingProdutoService matchingProduto;
    private final PrecoReferenciaService precoReferencia;

    public ConfirmacaoRespostaService(CotacaoRepository cotacaoRepository,
                                       CotacaoProdutoRepository cotacaoProdutoRepository,
                                       CotacaoProdutoFornecedorRepository cpfRepository,
                                       CotacaoFornecedorRepository cotacaoFornecedorRepository,
                                       FornecedorRepository fornecedorRepository,
                                       ParserRespostaFornecedorService parserResposta,
                                       ConciliacaoRespostaService conciliacaoResposta,
                                       ClassificacaoConferenciaService classificacaoConferencia,
                                       MatchingProdutoService matchingProduto,
                                       PrecoReferenciaService precoReferencia) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.cpfRepository = cpfRepository;
        this.cotacaoFornecedorRepository = cotacaoFornecedorRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.parserResposta = parserResposta;
        this.conciliacaoResposta = conciliacaoResposta;
        this.classificacaoConferencia = classificacaoConferencia;
        this.matchingProduto = matchingProduto;
        this.precoReferencia = precoReferencia;
    }

    @Transactional
    public List<ItemRespostaResponse> confirmar(UUID cotacaoId, UUID fornecedorId, ConfirmarRespostaRequest request) {
        Cotacao cotacao = cotacaoRepository.findByIdOrThrow(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita novas respostas");
        }

        fornecedorRepository.findById(fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + fornecedorId));

        CotacaoFornecedor cf = cotacaoFornecedorRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Fornecedor " + fornecedorId + " não foi adicionado à cotação " + cotacaoId));

        List<CotacaoProduto> itensBase = new ArrayList<>(cotacaoProdutoRepository.findByCotacaoIdAndRemovidoEmIsNullOrderByOrdem(cotacaoId));
        List<ProdutoCatalogo> produtosBase = itensBase.stream()
                .map(cp -> new ProdutoCatalogo(cp.getId(), cp.getTextoOriginal()))
                .toList();
        Map<UUID, String> nomesPorItemBase = new HashMap<>();
        for (CotacaoProduto cp : itensBase) {
            nomesPorItemBase.put(cp.getId(), cp.getTextoOriginal());
        }

        // Recomputa o mesmo pipeline do preview — o texto é a única fonte de verdade
        // reenviada pelo cliente; preço/marca/texto finais de itens auto-aceitos vêm
        // dessa recomputação, nunca de dado ecoado pelo cliente.
        ResultadoResposta resposta = parserResposta.parsear(request.texto());
        List<LinhaFornecedor> linhasValidas = resposta.linhas().stream().filter(l -> !l.ignorada()).toList();
        List<ItemConciliado> conciliados = conciliacaoResposta.conciliarEnhanced(linhasValidas, produtosBase);
        ConciliacaoRespostaService.ResultadoAgrupamento agrupamento =
                conciliacaoResposta.agrupar(conciliados, produtosBase);

        // Preço de referência (B.3) — mesmo cálculo do preview (FornecedorRespostaService.
        // gerarPreview), recomputado aqui porque a confirmação roda o pipeline de
        // classificação do zero (sem estado de rascunho entre preview e confirmar).
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

        // Linhas já existentes deste fornecedor nesta cotação (de uma confirmação
        // anterior) — buscadas ANTES de decidir o que fazer, pra poder preservar
        // snapshots de embalagem já resolvidos manualmente via AvisoService.resolver
        // (regra 2 do CLAUDE.md) e, principalmente, pra saber quais linhas NÃO devem ser
        // tocadas porque o item não foi mencionado na resposta desta rodada (achado do
        // bug de perda de dado: reconfirmar não pode apagar o que não foi reenviado).
        List<CotacaoProdutoFornecedor> existentes =
                cpfRepository.findByCotacaoIdAndFornecedorId(cotacaoId, fornecedorId);
        classificados = classificacaoConferencia.mesclarComConfirmacoesAnteriores(
                produtosBase, classificados, existentes);

        List<ResolucaoItemRequest> resolucoes = request.resolucoes() == null ? List.of() : request.resolucoes();
        for (ResolucaoItemRequest r : resolucoes) {
            if (r.itemBaseId() == null && r.textoOriginalExtra() == null) {
                throw new IllegalArgumentException("Resolução precisa de itemBaseId ou textoOriginalExtra.");
            }
            boolean spinOffCandidato = r.tipo() == TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA;
            if (r.itemBaseId() != null && r.textoOriginalExtra() != null && !spinOffCandidato) {
                throw new IllegalArgumentException(
                        "Resolução não pode ter itemBaseId e textoOriginalExtra ao mesmo tempo.");
            }
            boolean tipoDeExtra = r.tipo() == TipoResolucao.ADICIONAR_A_LISTA || r.tipo() == TipoResolucao.ASSOCIAR_A_ITEM;
            if (tipoDeExtra && r.itemBaseId() != null) {
                throw new IllegalArgumentException(
                        r.tipo() + " só se aplica a item extra (itemBaseId precisa ser nulo, use textoOriginalExtra).");
            }
            if (!tipoDeExtra && !spinOffCandidato && r.textoOriginalExtra() != null) {
                throw new IllegalArgumentException(
                        r.tipo() + " não é válido para item extra — use ADICIONAR_A_LISTA ou ASSOCIAR_A_ITEM.");
            }
            if (r.tipo() == TipoResolucao.ASSOCIAR_A_ITEM && r.associarAItemBaseId() == null) {
                throw new IllegalArgumentException("ASSOCIAR_A_ITEM exige associarAItemBaseId.");
            }
            // Mesma disciplina de EDITAR_MANUAL (linha ~277): item extra não tem
            // candidato "recomputado no servidor" pra aceitar sem dado do cliente — o
            // texto/preço SEMPRE vêm da resolução, então precisam ser explícitos e
            // válidos aqui, na validação de entrada, e não só confiar no valor default
            // do candidato original (achado do security-reviewer: sem isso, o cliente
            // podia mandar precoInformado=null e herdar silenciosamente o preço do
            // candidato original, ou mandar um preço inválido sem barrar na entrada).
            if (tipoDeExtra && (r.textoOriginalSelecionado() == null || r.textoOriginalSelecionado().isBlank()
                    || r.precoInformado() == null || r.precoInformado().signum() <= 0)) {
                throw new IllegalArgumentException(
                        r.tipo() + " precisa de textoOriginalSelecionado e precoInformado > 0.");
            }
            // ADICIONAR_CANDIDATO_A_LISTA (opção não selecionada de MULTIPLE_OPTIONS
            // virando item novo): exige os dois identificadores (itemBaseId de origem +
            // textoOriginalExtra apontando o candidato específico dentro dele) e,
            // igual a ADICIONAR_A_LISTA, texto/preço finais explícitos — o operador pode
            // editar antes de confirmar, então nada aqui pode ficar implícito/herdado.
            if (spinOffCandidato) {
                if (r.itemBaseId() == null || r.textoOriginalExtra() == null || r.textoOriginalExtra().isBlank()) {
                    throw new IllegalArgumentException(
                            "ADICIONAR_CANDIDATO_A_LISTA exige itemBaseId (item de origem) e textoOriginalExtra "
                                    + "(candidato original a transformar em novo item).");
                }
                if (r.textoOriginalSelecionado() == null || r.textoOriginalSelecionado().isBlank()
                        || r.precoInformado() == null || r.precoInformado().signum() <= 0) {
                    throw new IllegalArgumentException(
                            "ADICIONAR_CANDIDATO_A_LISTA precisa de textoOriginalSelecionado e precoInformado > 0.");
                }
            }
        }

        // ADICIONAR_CANDIDATO_A_LISTA fica de fora de resolucoesPorItem/resolucoesExtraPorTexto:
        // ela tem itemBaseId E textoOriginalExtra preenchidos ao mesmo tempo (única
        // exceção à regra "exatamente um dos dois"), e pode haver MAIS de uma por
        // itemBaseId (uma por candidato descartado) — colidiria com a resolução
        // "oficial" do item (SELECIONAR_CANDIDATO) se entrasse no mesmo mapa 1:1.
        Map<UUID, ResolucaoItemRequest> resolucoesPorItem = resolucoes.stream()
                .filter(r -> r.itemBaseId() != null && r.tipo() != TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA)
                .collect(Collectors.toMap(ResolucaoItemRequest::itemBaseId, r -> r, (a, b) -> b));
        Map<String, ResolucaoItemRequest> resolucoesExtraPorTexto = resolucoes.stream()
                .filter(r -> r.textoOriginalExtra() != null && r.tipo() != TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA)
                .collect(Collectors.toMap(ResolucaoItemRequest::textoOriginalExtra, r -> r, (a, b) -> b));
        Map<UUID, List<ResolucaoItemRequest>> spinOffsPorItemBase = resolucoes.stream()
                .filter(r -> r.tipo() == TipoResolucao.ADICIONAR_CANDIDATO_A_LISTA)
                .collect(Collectors.groupingBy(ResolucaoItemRequest::itemBaseId));

        // Itens extra (itemBaseId==null) com decisão do operador (Adicionar à lista /
        // Associar a outro item) viram um ItemConferencia sintético já resolvido (status
        // OK, sem motivos) ANTES do loop principal — assim reaproveitam o MESMO fluxo de
        // upsert de cotacao_produto_fornecedor usado pelos itens normais, sem duplicar a
        // lógica de "escolhido/precoFinal" abaixo. Item extra sem decisão continua
        // descartado, como já era o comportamento antes desta mudança.
        Map<String, ItemConciliado> conciliadosPorTexto = conciliados.stream()
                .collect(Collectors.toMap(ItemConciliado::textoOriginal, ic -> ic, (a, b) -> a));

        // Itens base que JÁ têm uma classificação própria nesta rodada (match genuíno
        // do parser, OU preservado de uma confirmação anterior) — um item extra NUNCA
        // pode ser associado/deduplicado para um destes. Achado do security-reviewer:
        // sem esta trava, ASSOCIAR_A_ITEM apontando pro mesmo itemBaseId de um match
        // OK/ATENÇÃO real (ou uma colisão de nome em ADICIONAR_A_LISTA) sobrescrevia
        // silenciosamente o preço legítimo com o preço fabricado da resolução — a MESMA
        // classe de fabricação de dado que a trava de itens REVISAR (abaixo) já
        // deveria impedir, só que por um caminho que não passava por ela.
        // HashSet explícito (não Collectors.toSet(), cuja mutabilidade não é garantida
        // pela API) — este Set ganha os ids dos itens novos criados por
        // ADICIONAR_CANDIDATO_A_LISTA logo abaixo, para que um SEGUNDO spin-off na
        // mesma submissão também veja o primeiro como "já com classificação própria".
        Set<UUID> itemBaseIdsComClassificacaoReal = new HashSet<>(classificados.stream()
                .map(ItemConferencia::itemBaseId)
                .filter(id -> id != null)
                .collect(Collectors.toSet()));

        List<ItemConferencia> classificadosFinal = new ArrayList<>();
        for (ItemConferencia item : classificados) {
            if (item.itemBaseId() != null) {
                classificadosFinal.add(item);
                continue;
            }
            CandidatoResposta candidatoOriginal = item.candidatos().get(0);
            ResolucaoItemRequest resolucaoExtra = resolucoesExtraPorTexto.get(candidatoOriginal.textoOriginal());
            if (resolucaoExtra == null) {
                continue;
            }
            ItemConciliado itemExtraConciliado = conciliadosPorTexto.get(candidatoOriginal.textoOriginal());
            UUID itemBaseIdEfetivo;
            if (resolucaoExtra.tipo() == TipoResolucao.ADICIONAR_A_LISTA) {
                itemBaseIdEfetivo = resolverOuCriarItemBase(cotacaoId, itensBase, nomesPorItemBase,
                        itemExtraConciliado, resolucaoExtra, itemBaseIdsComClassificacaoReal);
            } else {
                UUID alvo = resolucaoExtra.associarAItemBaseId();
                if (itemBaseIdsComClassificacaoReal.contains(alvo)) {
                    throw new IllegalArgumentException(
                            "Item base " + alvo + " já tem uma correspondência própria nesta rodada — "
                                    + "não pode receber associação de item extra.");
                }
                itemBaseIdEfetivo = validarItemBaseExistente(itensBase, alvo);
            }

            // textoOriginalSelecionado/precoInformado já validados como obrigatórios
            // na entrada (item extra não tem candidato "recomputado" pra confiar sem
            // dado do cliente, ao contrário de ACEITAR_SUGESTAO/SELECIONAR_CANDIDATO).
            CandidatoResposta candidatoFinal = new CandidatoResposta(
                    resolucaoExtra.textoOriginalSelecionado(), candidatoOriginal.marcaOferecida(),
                    resolucaoExtra.precoInformado(), candidatoOriginal.confiancaMatch(),
                    candidatoOriginal.semEstoque());
            classificadosFinal.add(new ItemConferencia(
                    itemBaseIdEfetivo, StatusConferencia.OK, List.of(), List.of(candidatoFinal)));
        }
        classificados = classificadosFinal;

        // ADICIONAR_CANDIDATO_A_LISTA: cada candidato NÃO selecionado de um item
        // MULTIPLE_OPTIONS que o operador marcou vira um CotacaoProduto novo (mesmo
        // destino de resolverOuCriarItemBase, usado hoje por ADICIONAR_A_LISTA), sem
        // tocar no item original — que continua fluindo pelo loop principal abaixo
        // (REVISAR sem resolução própria ainda bloqueia a confirmação, como já era).
        // À parte do loop principal porque pode haver MAIS de uma resolução para o
        // MESMO itemBaseId (uma por candidato descartado que virou item novo).
        // Achado do security-reviewer (defesa em profundidade): rastreia quais
        // itemBaseId de spinOffsPorItemBase foram de fato encontrados em classificados
        // — um id que não bate com nada (typo do frontend, item de outra rodada) hoje
        // seria descartado em silêncio, diferente do resto do método (que sempre falha
        // alto). Removido do Set assim que o item correspondente é processado; o que
        // sobrar no final é erro.
        Set<UUID> spinOffItemBaseIdsPendentes = new HashSet<>(spinOffsPorItemBase.keySet());
        List<ItemConferencia> spinOffsClassificados = new ArrayList<>();
        for (ItemConferencia item : classificados) {
            List<ResolucaoItemRequest> spinOffs = item.itemBaseId() == null ? null
                    : spinOffsPorItemBase.get(item.itemBaseId());
            if (spinOffs == null || spinOffs.isEmpty()) {
                continue;
            }
            spinOffItemBaseIdsPendentes.remove(item.itemBaseId());
            // Achado do security-reviewer: checar só candidatos().size()<=1 depende de
            // um invariante indireto (hoje só MULTIPLE_OPTIONS produz >1 candidato) —
            // frágil a um refactor futuro de ClassificacaoConferenciaService. Checagem
            // explícita de status+motivo deixa a intenção no código, não implícita.
            if (item.candidatos().size() <= 1 || item.status() != StatusConferencia.REVISAR
                    || !item.motivos().contains(MotivoConferencia.MULTIPLE_OPTIONS)) {
                throw new IllegalArgumentException(
                        "Item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" não tem múltiplas opções "
                                + "nesta rodada — ADICIONAR_CANDIDATO_A_LISTA não é aplicável.");
            }
            for (ResolucaoItemRequest spinOff : spinOffs) {
                // Candidato de origem procurado nos candidatos RECOMPUTADOS no servidor
                // (nunca confiar num candidato "fabricado" pelo cliente) — mesmo padrão
                // de proteção já usado por SELECIONAR_CANDIDATO.
                CandidatoResposta candidatoOrigem = item.candidatos().stream()
                        .filter(c -> c.textoOriginal().equals(spinOff.textoOriginalExtra()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Candidato não encontrado para o item \"" + nomeItem(nomesPorItemBase, item.itemBaseId())
                                        + "\": " + spinOff.textoOriginalExtra()));
                ItemConciliado itemConciliadoOrigem = conciliadosPorTexto.get(candidatoOrigem.textoOriginal());
                UUID novoItemBaseId = resolverOuCriarItemBase(cotacaoId, itensBase, nomesPorItemBase,
                        itemConciliadoOrigem, spinOff, itemBaseIdsComClassificacaoReal);
                // Adicionado imediatamente para que um SEGUNDO spin-off na mesma
                // submissão veja este item novo como já tendo classificação própria.
                itemBaseIdsComClassificacaoReal.add(novoItemBaseId);

                // Texto/preço finais vêm da resolução (editáveis pelo operador antes de
                // confirmar — diferente de SELECIONAR_CANDIDATO, que nunca aceita
                // override de preço do cliente); marca/semEstoque herdam do candidato
                // de origem recomputado no servidor.
                CandidatoResposta candidatoFinal = new CandidatoResposta(
                        spinOff.textoOriginalSelecionado(), candidatoOrigem.marcaOferecida(),
                        spinOff.precoInformado(), candidatoOrigem.confiancaMatch(), candidatoOrigem.semEstoque());
                spinOffsClassificados.add(new ItemConferencia(
                        novoItemBaseId, StatusConferencia.OK, List.of(), List.of(candidatoFinal)));
            }
        }
        if (!spinOffItemBaseIdsPendentes.isEmpty()) {
            throw new IllegalArgumentException(
                    "ADICIONAR_CANDIDATO_A_LISTA referencia item(ns) que não estão nesta rodada: "
                            + spinOffItemBaseIdsPendentes);
        }
        classificados.addAll(spinOffsClassificados);

        Map<UUID, Integer> embalagensJaConfirmadas = existentes.stream()
                .filter(e -> e.getEmbalagemQtdConfirmada() != null)
                .collect(Collectors.toMap(CotacaoProdutoFornecedor::getCotacaoProdutoId,
                        CotacaoProdutoFornecedor::getEmbalagemQtdConfirmada, (a, b) -> a));
        Map<UUID, CotacaoProdutoFornecedor> existentePorItemBase = existentes.stream()
                .collect(Collectors.toMap(CotacaoProdutoFornecedor::getCotacaoProdutoId, e -> e, (a, b) -> a));

        List<CotacaoProdutoFornecedor> paraSalvar = new ArrayList<>();
        List<CotacaoProdutoFornecedor> paraDeletar = new ArrayList<>();
        List<String> nomesParaResultado = new ArrayList<>();

        for (ItemConferencia item : classificados) {
            if (item.itemBaseId() == null) {
                // EXTRA_ITEM (resposta sem item base correspondente) nunca persiste —
                // não faz sentido gravar em cotacao_produto_fornecedor sem cotacao_produto_id.
                continue;
            }

            ResolucaoItemRequest resolucao = resolucoesPorItem.get(item.itemBaseId());

            if (item.preservado()) {
                // Item não mencionado na resposta desta rodada — a linha já confirmada
                // permanece intacta, sem passar por save/delete nenhum. Uma resolução
                // anexada a um item que o servidor nem reprocessou seria fabricação de
                // dado pelo cliente (mesma trava aplicada abaixo a itens OK/ATENÇÃO).
                if (resolucao != null) {
                    throw new IllegalArgumentException(
                            "Item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" não foi reprocessado nesta rodada — resolução não é aplicável.");
                }
                continue;
            }

            if (item.status() == StatusConferencia.REVISAR) {
                if (resolucao == null) {
                    throw new IllegalArgumentException(
                            "Item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" está em Revisar e precisa de uma resolução para confirmar.");
                }
            } else if (resolucao != null && item.status() != StatusConferencia.ATENCAO) {
                // Achado do security-reviewer: sem esta trava, o cliente poderia anexar
                // uma "resolução" a um item que a recomputação já classificou como não
                // precisando de decisão e assim fabricar preço/texto arbitrário para ele.
                //
                // ATENÇÃO passou a ser aceito junto com REVISAR: a tela agora oferece
                // Aceitar/Editar/Recusar também nessas linhas (marca ou gramagem
                // divergente é exatamente o caso em que o operador precisa escolher), e
                // sem isso selecionar a opção numa linha de Atenção derrubava a
                // confirmação inteira. A diferença entre os dois status continua sendo o
                // bloqueio, não a permissão: REVISAR sem resolução barra a confirmação,
                // ATENÇÃO sem resolução segue com o candidato do servidor.
                //
                // A trava que importa fica de pé: item OK (ou preservado, tratado acima)
                // continua recusando qualquer dado do cliente, e mesmo em REVISAR/ATENÇÃO
                // só EDITAR_MANUAL aceita preço arbitrário — ACEITAR_SUGESTAO e
                // SELECIONAR_CANDIDATO usam o preço do candidato recomputado no servidor.
                throw new IllegalArgumentException(
                        "Item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" não está em Revisar nem em Atenção — resolução não é aplicável.");
            }

            if (resolucao != null && resolucao.tipo() == TipoResolucao.SEM_OFERTA) {
                // "Sem oferta" é uma remoção explícita pedida pelo usuário nesta rodada —
                // diferente de "não mencionado" (que preserva silenciosamente via
                // item.preservado() acima). Se já havia uma linha confirmada pra este
                // item+fornecedor, ela precisa sumir de propósito.
                CotacaoProdutoFornecedor existente = existentePorItemBase.get(item.itemBaseId());
                if (existente != null) {
                    paraDeletar.add(existente);
                }
                continue;
            }

            CandidatoResposta escolhido;
            BigDecimal precoFinal;

            if (resolucao == null) {
                // OK/ATENCAO: sempre o único candidato recomputado no servidor, sem
                // qualquer dado vindo do cliente.
                escolhido = item.candidatos().get(0);
                precoFinal = escolhido.precoInformado();
            } else {
                switch (resolucao.tipo()) {
                    case ACEITAR_SUGESTAO -> {
                        if (item.candidatos().size() != 1) {
                            throw new IllegalArgumentException(
                                    "Item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" tem múltiplos candidatos — selecione um explicitamente.");
                        }
                        escolhido = item.candidatos().get(0);
                        precoFinal = escolhido.precoInformado();
                    }
                    case SELECIONAR_CANDIDATO -> {
                        escolhido = item.candidatos().stream()
                                .filter(c -> c.textoOriginal().equals(resolucao.textoOriginalSelecionado()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Candidato não encontrado para o item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\": "
                                                + resolucao.textoOriginalSelecionado()));
                        // Preço vem do candidato recomputado, nunca de override do
                        // cliente — só EDITAR_MANUAL aceita preço arbitrário.
                        precoFinal = escolhido.precoInformado();
                    }
                    case EDITAR_MANUAL -> {
                        if (resolucao.textoOriginalSelecionado() == null || resolucao.textoOriginalSelecionado().isBlank()
                                || resolucao.precoInformado() == null) {
                            throw new IllegalArgumentException(
                                    "Edição manual do item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" precisa de texto e preço.");
                        }
                        escolhido = new CandidatoResposta(
                                resolucao.textoOriginalSelecionado(), null, resolucao.precoInformado(),
                                BigDecimal.ZERO, Boolean.TRUE.equals(resolucao.semEstoque()));
                        precoFinal = resolucao.precoInformado();
                    }
                    default -> throw new IllegalStateException("Tipo de resolução inesperado: " + resolucao.tipo());
                }
            }

            if (precoFinal == null) {
                if (resolucao == null && !escolhido.semEstoque()) {
                    // B.2 (pacote de ajustes pós-call): item identificado mas sem preço
                    // reconhecido na linha não é mais erro — é estado normal ("fornecedor
                    // não informou preço para este item"), igual a não ter mencionado o
                    // item. Mesmo tratamento de SEM_OFERTA acima: reverte para "sem info"
                    // se já havia linha confirmada de rodada anterior; senão, não grava
                    // nada. Só chega aqui com resolucao==null porque este item nunca foi
                    // escalado a REVISAR/ATENÇÃO (ver ClassificacaoConferenciaService) —
                    // não há nenhuma decisão do operador para preservar.
                    CotacaoProdutoFornecedor existente = existentePorItemBase.get(item.itemBaseId());
                    if (existente != null) {
                        paraDeletar.add(existente);
                    }
                    continue;
                }
                // preco_informado é NOT NULL no banco. Chegar aqui com uma resolução
                // explícita do operador (ex.: aceitar um candidato "consultar"/
                // precoPendente sem completar o preço) É erro de entrada acionável —
                // diferente do caso acima, que é silencioso por design.
                throw new IllegalArgumentException(
                        "Item \"" + nomeItem(nomesPorItemBase, item.itemBaseId()) + "\" não tem preço reconhecido no texto do fornecedor — "
                                + "edite manualmente com o preço correto antes de confirmar.");
            }

            // Upsert real por cotacao_produto_id: reusa a linha já confirmada (UPDATE)
            // em vez de sempre criar uma nova (o antigo delete-all+insert violaria
            // UNIQUE(cotacao_produto_id, fornecedor_id) sem o delete, e apagava itens
            // não reenviados junto com ele — a causa raiz do bug desta correção).
            CotacaoProdutoFornecedor cpf = existentePorItemBase.getOrDefault(item.itemBaseId(),
                    new CotacaoProdutoFornecedor());
            cpf.setCotacaoProdutoId(item.itemBaseId());
            cpf.setFornecedorId(fornecedorId);
            cpf.setTextoOriginal(escolhido.textoOriginal());
            cpf.setPrecoInformado(precoFinal);
            cpf.setPrecoUnitarioCalculado(precoFinal);
            cpf.setSemEstoque(escolhido.semEstoque());
            cpf.setConfiancaMatch(escolhido.confiancaMatch());
            // Status persistido sempre OK: ATENCAO/REVISAR são severidades do preview,
            // não do dado já confirmado — o motivo (se houver) vira metadado
            // informativo abaixo, não um StatusItem à parte (decisão E do plano).
            cpf.setStatus(StatusItem.OK);
            // Sempre atribuído (mesmo null) — com o upsert em vez de sempre criar uma
            // entidade nova, deixar isto num `if` deixaria um motivo de rodada anterior
            // preso na linha reaproveitada mesmo depois que ela deixou de se aplicar
            // (achado do code-review desta correção).
            cpf.setTipoEmbalagemDetectado(item.motivos().isEmpty() ? null
                    : item.motivos().stream().map(Enum::name).collect(Collectors.joining(",")));
            // Snapshot de embalagem gravado uma única vez, no INSERT — nunca em UPDATE
            // (regra 2 do CLAUDE.md). O snapshot já confirmado numa rodada anterior
            // deste MESMO fornecedor (embalagensJaConfirmadas) sempre vence sobre um
            // valor novo vindo da resolução desta rodada — resolucao.embalagemQtd() só
            // é usado quando ainda não existe snapshot (primeira vez). Antes desta
            // correção, um valor diferente nesta rodada sobrescrevia silenciosamente o
            // já confirmado, violando a garantia de imutabilidade que a entidade
            // documenta (só valia de fato no caminho de AvisoService.resolver).
            Integer snapshotAnterior = embalagensJaConfirmadas.get(item.itemBaseId());
            Integer embalagemQtd;
            if (snapshotAnterior != null) {
                embalagemQtd = snapshotAnterior;
                if (resolucao != null && resolucao.embalagemQtd() != null && resolucao.embalagemQtd() > 0
                        && !resolucao.embalagemQtd().equals(snapshotAnterior)) {
                    log.warn("Item {} já tem embalagem_qtd_confirmada={} — valor {} desta rodada foi descartado (snapshot imutável).",
                            item.itemBaseId(), snapshotAnterior, resolucao.embalagemQtd());
                }
            } else if (resolucao != null && resolucao.embalagemQtd() != null && resolucao.embalagemQtd() > 0) {
                embalagemQtd = resolucao.embalagemQtd();
            } else {
                embalagemQtd = null;
            }
            if (embalagemQtd != null) {
                cpf.setEmbalagemQtdConfirmada(embalagemQtd);
                cpf.setPrecoUnitarioCalculado(
                        precoFinal.divide(BigDecimal.valueOf(embalagemQtd), 4, RoundingMode.HALF_UP));
            }

            paraSalvar.add(cpf);
            nomesParaResultado.add(nomesPorItemBase.get(item.itemBaseId()));
        }

        // Só apaga o que foi explicitamente marcado "sem oferta" nesta rodada — linhas
        // não mencionadas (item.preservado()) nunca chegam a paraDeletar nem a
        // paraSalvar, e por isso sobrevivem intactas.
        if (!paraDeletar.isEmpty()) {
            cpfRepository.deleteAll(paraDeletar);
            cpfRepository.flush();
        }

        List<CotacaoProdutoFornecedor> salvos = cpfRepository.saveAll(paraSalvar);

        cf.setStatus(CotacaoFornecedorStatus.CONFIRMADO);
        // Rascunho pendente (Prompt 15, V27) não faz mais sentido depois de confirmado —
        // os dados agora vivem em cotacao_produto_fornecedor, fonte de verdade a partir
        // daqui; sem limpar, uma reabertura via textoPersistido devolveria o texto da
        // rodada já confirmada em vez do estado atual.
        cf.setTextoRespostaPendente(null);
        cotacaoFornecedorRepository.save(cf);

        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        cotacaoRepository.save(cotacao);

        log.info("Resposta de fornecedor confirmada: cotacaoId={}, fornecedorId={}, itens={}",
                cotacaoId, fornecedorId, salvos.size());

        List<ItemRespostaResponse> resultado = new ArrayList<>();
        for (int i = 0; i < salvos.size(); i++) {
            CotacaoProdutoFornecedor cpf = salvos.get(i);
            resultado.add(new ItemRespostaResponse(
                    cpf.getId(), cpf.getTextoOriginal(), nomesParaResultado.get(i),
                    cpf.getPrecoInformado(), cpf.getPrecoUnitarioCalculado(), cpf.isSemEstoque(),
                    cpf.getConfiancaMatch(), cpf.getStatus()));
        }
        return resultado;
    }

    // ADICIONAR_A_LISTA: item extra do fornecedor vira um CotacaoProduto novo na lista
    // base COMPARTILHADA da cotação. Não precisa de coluna "exclusivo do fornecedor" —
    // isso já é automático no modelo atual: só o fornecedor que confirmou ganha uma
    // linha em cotacao_produto_fornecedor pra esse item; os demais simplesmente não têm
    // linha (preço ausente), que é exatamente o efeito que a especificação pede.
    // Dedup por nome normalizado evita duplicar o produto se o mesmo texto for
    // reenviado numa reconfirmação (equivalente ao "se existe produto com essa key:
    // associa" da especificação) — MAS nunca reusa um item que já tem uma
    // classificação própria nesta rodada (itemBaseIdsComClassificacaoReal): sem essa
    // trava, uma colisão de nome normalizado com um match genuíno OK/ATENÇÃO faria
    // este método "associar" ao item errado e sobrescrever seu preço legítimo com o
    // preço fabricado da resolução (achado do security-reviewer).
    private UUID resolverOuCriarItemBase(UUID cotacaoId, List<CotacaoProduto> itensBase,
                                          Map<UUID, String> nomesPorItemBase,
                                          ItemConciliado itemExtraConciliado,
                                          ResolucaoItemRequest resolucao,
                                          Set<UUID> itemBaseIdsComClassificacaoReal) {
        String nomeFinal = resolucao.textoOriginalSelecionado();
        String nomeNormalizado = matchingProduto.normTxt(nomeFinal);

        for (CotacaoProduto cp : itensBase) {
            if (!itemBaseIdsComClassificacaoReal.contains(cp.getId())
                    && matchingProduto.normTxt(cp.getTextoOriginal()).equals(nomeNormalizado)) {
                return cp.getId();
            }
        }

        CotacaoProduto novo = new CotacaoProduto();
        novo.setCotacaoId(cotacaoId);
        novo.setProdutoId(null);
        novo.setTextoOriginal(nomeFinal);
        Integer qtdInformada = itemExtraConciliado.linhaOriginal().qtdInformada();
        novo.setQuantidade(qtdInformada != null ? BigDecimal.valueOf(qtdInformada) : BigDecimal.ONE);
        String unInformada = itemExtraConciliado.linhaOriginal().unInformada();
        novo.setUnidade(unInformada != null ? unInformada : "un");
        int proximaOrdem = itensBase.stream().mapToInt(CotacaoProduto::getOrdem).max().orElse(0) + 1;
        novo.setOrdem(proximaOrdem);

        CotacaoProduto salvo = cotacaoProdutoRepository.save(novo);
        // Adicionado à lista em memória (não só ao banco) para que um SEGUNDO item extra
        // resolvido na MESMA confirmação veja este como já existente (dedup) e calcule a
        // próxima ordem corretamente.
        itensBase.add(salvo);
        nomesPorItemBase.put(salvo.getId(), nomeFinal);
        return salvo.getId();
    }

    // ASSOCIAR_A_ITEM: valida que o item base de destino pertence a ESTA cotação antes
    // de deixar o item extra gravar preço nele — mesmo padrão de
    // CotacaoProdutoItemService.buscarItemDaCotacao (cross-cotação/tenant já é bloqueado
    // pela RLS antes de chegar aqui, mas trata como não encontrado em vez de vazar a
    // existência do recurso de outra cotação).
    private UUID validarItemBaseExistente(List<CotacaoProduto> itensBase, UUID itemBaseId) {
        boolean existeNestaCotacao = itensBase.stream().anyMatch(cp -> cp.getId().equals(itemBaseId));
        if (!existeNestaCotacao) {
            throw new ResourceNotFoundException("Item base não encontrado nesta cotação: " + itemBaseId);
        }
        return itemBaseId;
    }

    // Nome do item base para mensagem de erro voltada ao operador — o UUID cru não diz
    // nada a quem está olhando uma lista de 68 linhas na tela. Cai para o id só se o
    // item não estiver no mapa (não deveria acontecer; melhor identificar de algum jeito
    // do que exibir "null").
    private String nomeItem(Map<UUID, String> nomesPorItemBase, UUID itemBaseId) {
        String nome = nomesPorItemBase.get(itemBaseId);
        return nome != null ? nome : String.valueOf(itemBaseId);
    }
}
