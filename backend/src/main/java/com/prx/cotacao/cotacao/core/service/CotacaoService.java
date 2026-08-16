package com.prx.cotacao.cotacao.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.cotacao.mapacompra.service.MapaCompraService;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.security.CurrentUser;
import com.prx.cotacao.cotacao.core.dto.CriarCotacaoRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.prx.cotacao.cotacao.core.enums.CanalOrigem;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.core.enums.CotacaoStatus;

@Service
public class CotacaoService {

    private static final Logger log = LoggerFactory.getLogger(CotacaoService.class);

    private final CotacaoRepository cotacaoRepository;
    private final CurrentUser currentUser;
    private final MapaCompraService mapaCompraService;
    private final ObjectMapper objectMapper;

    public CotacaoService(CotacaoRepository cotacaoRepository, CurrentUser currentUser,
                           MapaCompraService mapaCompraService, ObjectMapper objectMapper) {
        this.cotacaoRepository = cotacaoRepository;
        this.currentUser = currentUser;
        this.mapaCompraService = mapaCompraService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Cotacao criar(CriarCotacaoRequest request) {
        Cotacao cotacao = new Cotacao();
        // tenantId setado automaticamente pelo TenantAuditEntityListener
        cotacao.setCriadoPor(currentUser.usuarioId());
        cotacao.setTitulo(request.titulo());
        cotacao.setStatus(CotacaoStatus.RASCUNHO);
        cotacao.setCanalOrigem(CanalOrigem.WEB);
        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        return cotacaoRepository.save(cotacao);
    }

    public Page<Cotacao> listar(Pageable pageable) {
        // Hibernate filter garante retorno apenas do tenant atual
        return cotacaoRepository.findAll(pageable);
    }

    // Prompt 24: listagem do dashboard com filtro opcional por status e termo de busca
    // (título ou canal). status/termo nulos preservam o comportamento de listar(Pageable).
    public Page<Cotacao> listar(Pageable pageable, CotacaoStatus status, String termo) {
        return cotacaoRepository.buscar(status, termo, pageable);
    }

    public Cotacao buscar(UUID id) {
        // findById já filtra pelo tenant via Hibernate filter
        return cotacaoRepository.findByIdOrThrow(id);
    }

    @Transactional
    public Cotacao finalizar(UUID cotacaoId, CenarioSelecionado cenario) {
        Cotacao cotacao = buscar(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação já finalizada");
        }
        // Congela a distribuição escolhida no momento da finalização — a tela da
        // cotação finalizada passa a exibir esse snapshot, não mais um recálculo ao
        // vivo do algoritmo (que mudaria retroativamente se dado subjacente mudasse
        // depois, ex: fornecedor inativado). Calculado antes de marcar FINALIZADA
        // porque MapaCompraService.gerar() já passa a servir o snapshot congelado
        // pra cotações finalizadas — nesse ponto ela ainda não é.
        MapaCompraResponse mapaFinal = mapaCompraService.gerar(cotacaoId, cenario);
        cotacao.setMapaFinalSnapshot(serializar(mapaFinal));
        cotacao.setStatus(CotacaoStatus.FINALIZADA);
        cotacao.setCenarioSelecionado(cenario);
        cotacao.setFinalizadaEm(OffsetDateTime.now());
        log.info("Cotação finalizada: cotacaoId={}, cenario={}", cotacaoId, cenario);
        return cotacaoRepository.save(cotacao);
    }

    private String serializar(MapaCompraResponse mapa) {
        try {
            return objectMapper.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar snapshot do Mapa de Compra", e);
        }
    }

    @Transactional
    public void atualizarAtividade(UUID cotacaoId) {
        cotacaoRepository.findById(cotacaoId).ifPresent(c -> {
            c.setUltimaAtividadeEm(OffsetDateTime.now());
            cotacaoRepository.save(c);
        });
    }

    // Botão "Concluir ajuste e seguir para conferência" da tela Ajuste de Lista (Fase 3
    // WhatsApp) — só faz sentido pra cotação WhatsApp ainda não revisada; status e
    // canalOrigem NUNCA são tocados aqui, só listaRevisada (regra do projeto).
    @Transactional
    public Cotacao concluirAjusteLista(UUID cotacaoId) {
        Cotacao cotacao = buscar(cotacaoId);
        if (cotacao.getStatus() == CotacaoStatus.FINALIZADA) {
            throw new ConflictException("Cotação finalizada não aceita alteração de itens");
        }
        if (cotacao.getCanalOrigem() != CanalOrigem.WHATSAPP || cotacao.isListaRevisada()) {
            throw new ConflictException(
                    "Esta cotação não está com uma lista de WhatsApp pendente de revisão.");
        }
        cotacao.setListaRevisada(true);
        cotacao.setUltimaAtividadeEm(OffsetDateTime.now());
        return cotacaoRepository.save(cotacao);
    }
}
