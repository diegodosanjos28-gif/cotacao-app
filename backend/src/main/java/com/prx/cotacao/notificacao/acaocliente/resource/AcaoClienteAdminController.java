package com.prx.cotacao.notificacao.acaocliente.resource;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.dto.AcaoClienteResponse;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.repository.AcaoClienteCenarioRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Catálogo global de ações do cliente (ver {@link AcaoCliente}) — alimenta o
 * grid de Templates de Mensagem no Admin PRX. Fora de {@code /admin/tenants/{id}}: não
 * é dado de tenant, o mesmo catálogo vale pra todos.
 */
@RestController
@RequestMapping("/admin/acoes-cliente")
public class AcaoClienteAdminController {

    // Sem ORDER BY/comparator, a ordem física de retorno do Postgres não é garantida —
    // o grid do frontend ficaria com ordem não-determinística entre boots.
    // NAO_IDENTIFICADO primeiro (é o fallback, mesma posição que "Genérico" ocupava
    // antes), depois as ações na ordem do enum, SUCESSO antes de ERRO dentro de cada.
    private static final Comparator<AcaoCliente> ORDEM_EXIBICAO = Comparator
            .<AcaoCliente>comparingInt(c -> c.getAcao() == AcaoClienteEnum.NAO_IDENTIFICADO ? -1 : c.getAcao().ordinal())
            .thenComparingInt(c -> c.getResultado() == null ? 0 : c.getResultado().ordinal());

    private final AcaoClienteCenarioRepository cenarioRepository;

    public AcaoClienteAdminController(AcaoClienteCenarioRepository cenarioRepository) {
        this.cenarioRepository = cenarioRepository;
    }

    @GetMapping
    public List<AcaoClienteResponse> listar() {
        return cenarioRepository.findAll().stream()
                .sorted(ORDEM_EXIBICAO)
                .map(AcaoClienteResponse::from)
                .toList();
    }
}
