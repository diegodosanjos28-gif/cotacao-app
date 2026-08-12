package com.prx.cotacao.whatsapp.canal.service;

import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.service.CotacaoListaService;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;
import com.prx.cotacao.whatsapp.canal.dto.ResultadoProcessamentoLista;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Roteamento de lista de produtos vinda por WhatsApp (doc técnica, seção 10.3):
 * anexa à cotação em andamento do USUÁRIO (não do tenant — decisão revisada da Fase 2)
 * se houver uma dentro da janela de 48h, senão cria uma nova. A persistência dos itens
 * em si é 100% reaproveitada de {@link CotacaoListaService#processarLista}, sem
 * alteração — nenhuma lógica de parsing/matching nova aqui.
 */
@Service
public class WhatsappListaProdutosService {

    private static final int JANELA_HORAS_COTACAO_EM_ANDAMENTO = 48;
    private static final DateTimeFormatter FORMATO_TITULO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CotacaoRepository cotacaoRepository;
    private final CotacaoListaService cotacaoListaService;

    public WhatsappListaProdutosService(CotacaoRepository cotacaoRepository,
                                         CotacaoListaService cotacaoListaService) {
        this.cotacaoRepository = cotacaoRepository;
        this.cotacaoListaService = cotacaoListaService;
    }

    /**
     * Chamado com TenantContext já resolvido para o tenant do usuário — o coordenador
     * (WhatsappWebhookService) garante que essa resolução acontece ANTES da primeira
     * consulta JPA da requisição (ver javadoc de IdentificacaoWhatsappService: com
     * open-in-view, a conexão do EntityManager da requisição inteira é fixada na
     * primeira consulta e não muda depois). tenantId é preenchido automaticamente pelo
     * TenantAuditEntityListener na criação da cotação nova.
     */
    @Transactional
    public ResultadoProcessamentoLista processar(UUID usuarioId, String texto) {
        Cotacao cotacao = buscarCotacaoEmAndamento(usuarioId).orElseGet(() -> criarCotacao(usuarioId));
        List<ItemListaResponse> itens = cotacaoListaService.processarLista(cotacao.getId(), texto);
        int itensReconhecidos = (int) itens.stream().filter(ItemListaResponse::matched).count();
        return new ResultadoProcessamentoLista(cotacao.getId(), itens.size(), itensReconhecidos);
    }

    private java.util.Optional<Cotacao> buscarCotacaoEmAndamento(UUID usuarioId) {
        OffsetDateTime desde = OffsetDateTime.now().minusHours(JANELA_HORAS_COTACAO_EM_ANDAMENTO);
        return cotacaoRepository
                .findFirstByCriadoPorAndCanalOrigemAndStatusAndUltimaAtividadeEmGreaterThanOrderByUltimaAtividadeEmDesc(
                        usuarioId, CanalOrigem.WHATSAPP, CotacaoStatus.EM_ANDAMENTO, desde);
    }

    private Cotacao criarCotacao(UUID usuarioId) {
        Cotacao nova = new Cotacao();
        // tenantId setado automaticamente pelo TenantAuditEntityListener
        nova.setCriadoPor(usuarioId);
        nova.setTitulo("WhatsApp " + OffsetDateTime.now().format(FORMATO_TITULO));
        // RASCUNHO aqui — processarLista (chamado logo em seguida) já promove para
        // EM_ANDAMENTO ao persistir os itens, mesmo caminho do fluxo web.
        nova.setStatus(CotacaoStatus.RASCUNHO);
        nova.setCanalOrigem(CanalOrigem.WHATSAPP);
        nova.setListaRevisada(false);
        nova.setUltimaAtividadeEm(OffsetDateTime.now());
        return cotacaoRepository.save(nova);
    }
}
