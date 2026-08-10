package com.prx.cotacao.fornecedor.service;

import com.prx.cotacao.fornecedor.dto.FornecedorRequest;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import com.prx.cotacao.fornecedor.enums.FornecedorStatus;
import com.prx.cotacao.fornecedor.enums.OrigemCadastro;
import com.prx.cotacao.fornecedor.repository.FornecedorRepository;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FornecedorService {

    private final FornecedorRepository fornecedorRepository;

    public FornecedorService(FornecedorRepository fornecedorRepository) {
        this.fornecedorRepository = fornecedorRepository;
    }

    public List<Fornecedor> listar() {
        // Hibernate filter garante retorno apenas do tenant atual
        return fornecedorRepository.findByStatusNot(FornecedorStatus.INATIVO);
    }

    @Transactional
    public Fornecedor criar(FornecedorRequest request) {
        if (fornecedorRepository.existsByNomeIgnoreCaseAndStatusNot(request.nome(), FornecedorStatus.INATIVO)) {
            throw new ConflictException("Já existe um fornecedor ativo com esse nome.");
        }
        Fornecedor f = new Fornecedor();
        // tenantId setado automaticamente pelo TenantAuditEntityListener no @PrePersist
        aplicarRequest(f, request);
        f.setOrigemCadastro(OrigemCadastro.MANUAL);
        return fornecedorRepository.save(f);
    }

    @Transactional
    public Fornecedor atualizar(UUID id, FornecedorRequest request) {
        Fornecedor f = fornecedorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + id));
        if (fornecedorRepository.existsByNomeIgnoreCaseAndStatusNotAndIdNot(request.nome(), FornecedorStatus.INATIVO, id)) {
            throw new ConflictException("Já existe um fornecedor ativo com esse nome.");
        }
        aplicarRequest(f, request);
        return fornecedorRepository.save(f);
    }

    @Transactional
    public void inativar(UUID id) {
        // Soft-delete: fornecedor é cadastro persistente reaproveitado entre cotações
        // (seção 3.2 da doc técnica) — cotações antigas continuam referenciando
        // fornecedor_id, então um DELETE físico quebraria histórico. INATIVO já é
        // filtrado por listar() e pelo comparativo.
        Fornecedor f = fornecedorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado: " + id));
        f.setStatus(FornecedorStatus.INATIVO);
        fornecedorRepository.save(f);
    }

    private void aplicarRequest(Fornecedor f, FornecedorRequest request) {
        f.setNome(request.nome());
        f.setPrazoEntregaPadrao(request.prazoEntregaPadrao());
        f.setCondicaoPagamentoPadrao(request.condicaoPagamentoPadrao());
        f.setPedidoMinimoPadrao(request.pedidoMinimoPadrao());
        f.setObservacoesPadrao(request.observacoesPadrao());
        if (request.status() != null) {
            f.setStatus(request.status());
        } else if (f.getStatus() == FornecedorStatus.PENDENTE_DADOS && dadosComerciaisCompletos(f)) {
            // Auto-promoção (doc técnica §10.5): um fornecedor criado automaticamente
            // pelo WhatsApp nasce PENDENTE_DADOS e fica fora do cenário Compra
            // Equilibrada do Mapa de Compra "até o operador completar prazo de
            // entrega, condição de pagamento e pedido mínimo no painel". A tela de
            // edição rápida (Entrada de Dados, FornecedorRespostaBlock) nunca manda
            // `status` no PUT, só os campos comerciais — sem isso, o fornecedor nunca
            // saía de PENDENTE_DADOS mesmo com o cadastro 100% completo (achado do
            // usuário, 09/08). Só promove quando o CALLER não pediu um status
            // explícito, pra não pisar numa inativação/reversão intencional.
            f.setStatus(FornecedorStatus.ATIVO);
        }
    }

    // "Completo" = os 3 campos que o Mapa de Compra precisa pra incluir o fornecedor
    // no cenário Compra Equilibrada (pedido mínimo, prazo de entrega, condição de
    // pagamento — ver MapaCompraService#fornecedorAbaixoDoMinimo/#melhorPrazo).
    // pedidoMinimoPadrao pode ser ZERO (fornecedor sem mínimo) — só precisa estar
    // presente, não positivo.
    private boolean dadosComerciaisCompletos(Fornecedor f) {
        return f.getPrazoEntregaPadrao() != null && !f.getPrazoEntregaPadrao().isBlank()
                && f.getCondicaoPagamentoPadrao() != null && !f.getCondicaoPagamentoPadrao().isBlank()
                && f.getPedidoMinimoPadrao() != null;
    }
}
