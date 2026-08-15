package com.prx.cotacao.notificacao.acaocliente.repository;

import com.prx.cotacao.notificacao.acaocliente.AcaoClienteEnum;
import com.prx.cotacao.notificacao.acaocliente.ResultadoAcaoCliente;
import com.prx.cotacao.notificacao.acaocliente.entity.AcaoCliente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AcaoClienteCenarioRepository extends JpaRepository<AcaoCliente, UUID> {

    Optional<AcaoCliente> findByAcaoAndResultado(AcaoClienteEnum acao, ResultadoAcaoCliente resultado);

    Optional<AcaoCliente> findByAcaoAndResultadoIsNull(AcaoClienteEnum acao);

    boolean existsByAcaoAndResultado(AcaoClienteEnum acao, ResultadoAcaoCliente resultado);

    boolean existsByAcaoAndResultadoIsNull(AcaoClienteEnum acao);
}
