package com.prx.cotacao.whatsapp.canal.service;

import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.entity.CotacaoProduto;
import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ParametroResolucaoWhatsapp;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ResolvedorFornecedorWhatsappStrategy;
import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ParserRespostaFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoResposta;
import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoResposta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Roteamento de resposta de fornecedor vinda por WhatsApp (Prompt 15 — unificação
 * Web/WhatsApp). A única responsabilidade exclusiva deste canal é decidir A QUAL
 * COTAÇÃO esta mensagem pertence (não tem equivalente no canal Web, onde a cotação já
 * é dada pela URL) — resolve a cotação em andamento do usuário (janela de 48h) ou
 * cria uma nova usando os próprios itens da mensagem como lista base ("o fornecedor
 * abre a cotação", doc técnica §10.3).
 *
 * <p>A partir daí, delega 100% do resto: monta o {@link ParametroResolucaoWhatsapp} e
 * chama {@link RespostaFornecedorCoreService#processar}, o MESMO núcleo usado pelo
 * canal Web — a resolução/criação do fornecedor via
 * {@link ResolvedorFornecedorWhatsappStrategy} (equivalente ao dropdown do canal Web,
 * mas por matching de nome — doc técnica §10.5) é despachada internamente pelo core,
 * não chamada mais diretamente daqui.
 * Este método NUNCA persiste em {@code cotacao_produto_fornecedor} — só gera preview,
 * marcando {@code CotacaoFornecedor.status = PROCESSADO}. Persistência de fato só
 * acontece via {@code POST .../confirmar}
 * ({@link ConfirmacaoRespostaService#confirmar}), o único gate
 * de qualidade do sistema, para os dois canais, sem exceção — inclusive para uma
 * resposta sem nenhuma divergência: o operador confirma pela Conferência (botão
 * "Conferir resposta do fornecedor" em {@code FornecedoresCotacoesSection.tsx"}) antes
 * de qualquer linha ser gravada.</p>
 */
@Service
public class WhatsappRespostaFornecedorService {

    private static final Logger log = LoggerFactory.getLogger(WhatsappRespostaFornecedorService.class);

    private static final int JANELA_HORAS_COTACAO_EM_ANDAMENTO = 48;
    private static final DateTimeFormatter FORMATO_TITULO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoProdutoRepository cotacaoProdutoRepository;
    private final ParserRespostaFornecedorService parserResposta;
    private final RespostaFornecedorCoreService core;

    public WhatsappRespostaFornecedorService(CotacaoRepository cotacaoRepository,
                                              CotacaoProdutoRepository cotacaoProdutoRepository,
                                              ParserRespostaFornecedorService parserResposta,
                                              RespostaFornecedorCoreService core) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoProdutoRepository = cotacaoProdutoRepository;
        this.parserResposta = parserResposta;
        this.core = core;
    }

    // Chamado com TenantContext já resolvido para o tenant do usuário — o coordenador
    // (WhatsappWebhookService) garante que essa resolução acontece ANTES da primeira
    // consulta JPA da requisição (ver javadoc de IdentificacaoWhatsappService).
    @Transactional
    public ResultadoProcessamentoResposta processar(UUID usuarioId, String texto) {
        ResultadoResposta resultado = parserResposta.parsear(texto);
        List<LinhaFornecedor> linhasValidas = resultado.linhas().stream().filter(l -> !l.ignorada()).toList();

        UUID cotacaoId;
        Optional<Cotacao> emAndamento = buscarCotacaoEmAndamento(usuarioId);
        if (emAndamento.isPresent()) {
            cotacaoId = emAndamento.get().getId();
        } else {
            // "O fornecedor abre a cotação" (doc técnica, seção 10.3): usa os próprios
            // produtos da resposta como lista base.
            Cotacao nova = criarCotacao(usuarioId);
            criarItensBaseDaResposta(nova.getId(), linhasValidas);
            cotacaoId = nova.getId();
        }

        core.processar(cotacaoId, new ParametroResolucaoWhatsapp(resultado.nomeFornecedor()), texto);

        log.info("Resposta de fornecedor via WhatsApp roteada para preview: cotacaoId={}, nomeFornecedor={}",
                cotacaoId, resultado.nomeFornecedor());

        return new ResultadoProcessamentoResposta(cotacaoId, resultado.nomeFornecedor(), linhasValidas.size());
    }

    private List<CotacaoProduto> criarItensBaseDaResposta(UUID cotacaoId, List<LinhaFornecedor> linhas) {
        List<CotacaoProduto> criados = new ArrayList<>();
        int ordem = 1;
        for (LinhaFornecedor linha : linhas) {
            CotacaoProduto cp = new CotacaoProduto();
            cp.setCotacaoId(cotacaoId);
            cp.setTextoOriginal(linha.textoOriginal());
            // qtdInformada/unInformada representam a embalagem OFERECIDA pelo
            // fornecedor, não a quantidade pedida pelo cliente (que não existe, pois é
            // o fornecedor quem está abrindo a cotação) — frequentemente null. Valor
            // neutro por padrão: seguro porque listaRevisada=false força esta cotação
            // pela tela Ajuste de Lista (Fase 4) antes de prosseguir.
            cp.setQuantidade(linha.qtdInformada() != null ? BigDecimal.valueOf(linha.qtdInformada()) : BigDecimal.ONE);
            cp.setUnidade(linha.unInformada() != null ? linha.unInformada() : "UN");
            cp.setOrdem(ordem++);
            criados.add(cotacaoProdutoRepository.save(cp));
        }
        return criados;
    }

    private Cotacao criarCotacao(UUID usuarioId) {
        Cotacao nova = new Cotacao();
        nova.setCriadoPor(usuarioId);
        nova.setTitulo("WhatsApp " + OffsetDateTime.now().format(FORMATO_TITULO));
        nova.setStatus(CotacaoStatus.EM_ANDAMENTO);
        nova.setCanalOrigem(CanalOrigem.WHATSAPP);
        nova.setListaRevisada(false);
        nova.setUltimaAtividadeEm(OffsetDateTime.now());
        return cotacaoRepository.save(nova);
    }

    private Optional<Cotacao> buscarCotacaoEmAndamento(UUID usuarioId) {
        OffsetDateTime desde = OffsetDateTime.now().minusHours(JANELA_HORAS_COTACAO_EM_ANDAMENTO);
        return cotacaoRepository
                .findFirstByCriadoPorAndCanalOrigemAndStatusAndUltimaAtividadeEmGreaterThanOrderByUltimaAtividadeEmDesc(
                        usuarioId, CanalOrigem.WHATSAPP, CotacaoStatus.EM_ANDAMENTO, desde);
    }
}
