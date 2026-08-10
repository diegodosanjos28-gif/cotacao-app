package com.prx.cotacao.identidade.service;

import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import com.prx.cotacao.identidade.repository.UsuarioTelefoneAutorizadoRepository;
import com.prx.cotacao.identidade.dto.UsuarioTelefoneRequest;
import com.prx.cotacao.shared.error.ConflictException;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD do próprio usuário (OPERADOR_CLIENTE) sobre seus números de WhatsApp
 * autorizados a mandar mensagem em seu nome (rotas /usuarios/me/telefones).
 * ADMIN_PRX não pertence a um tenant ({@link CurrentUser#tenantId()} nulo) — não
 * faz sentido cadastrar telefone sem tenant, então {@link #criar} rejeita.
 */
@Service
public class UsuarioTelefoneService {

    private final UsuarioTelefoneAutorizadoRepository repository;

    public UsuarioTelefoneService(UsuarioTelefoneAutorizadoRepository repository) {
        this.repository = repository;
    }

    public List<UsuarioTelefoneAutorizado> listar(UUID usuarioId) {
        return repository.findByUsuarioIdOrderByCriadoEmDesc(usuarioId);
    }

    @Transactional
    public UsuarioTelefoneAutorizado criar(UUID usuarioId, UUID tenantId, UsuarioTelefoneRequest request) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Usuário sem tenant não pode cadastrar telefone.");
        }
        // Normaliza ANTES da checagem de duplicata — sem isso, "+5511..." e "5511..."
        // (mesmo número, formatos diferentes) passariam pela checagem como distintos,
        // mesmo os dois normalizando pro mesmo valor ao serem persistidos (ver setter
        // de UsuarioTelefoneAutorizado).
        String numeroNormalizado = UsuarioTelefoneAutorizado.normalizarNumero(request.numeroWhatsapp());
        if (repository.existsByNumeroWhatsapp(numeroNormalizado)) {
            throw new ConflictException("Este número já está cadastrado no sistema.");
        }

        UsuarioTelefoneAutorizado t = new UsuarioTelefoneAutorizado();
        t.setUsuarioId(usuarioId);
        t.setTenantId(tenantId);
        t.setNumeroWhatsapp(request.numeroWhatsapp());
        t.setNomeContato(request.nomeContato());
        try {
            // saveAndFlush (não save): força o INSERT a rodar agora, dentro deste
            // try/catch, em vez de ser adiado pro commit da transação (que aconteceria
            // fora deste método) — só assim a violação da constraint UNIQUE global é
            // capturada aqui.
            return repository.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            // Backstop pro caso que a checagem acima não pega: número já cadastrado
            // por OUTRO tenant. RLS restringe o SELECT de existsByNumeroWhatsapp ao
            // tenant atual (ver comentário no repositório), mas a constraint UNIQUE do
            // banco não é afetada por RLS — é o que garante a unicidade global de fato.
            throw new ConflictException("Este número já está cadastrado no sistema.");
        }
    }

    @Transactional
    public void remover(UUID usuarioId, UUID telefoneId) {
        UsuarioTelefoneAutorizado t = repository.findById(telefoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Telefone não encontrado: " + telefoneId));
        if (!usuarioId.equals(t.getUsuarioId())) {
            throw new ResourceNotFoundException("Telefone não encontrado: " + telefoneId);
        }
        repository.delete(t);
    }
}
