package com.prx.cotacao.historico.resource;

import com.prx.cotacao.historico.service.HistoricoPrecoService;
import com.prx.cotacao.historico.dto.HistoricoPrecoProdutoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/historico-precos")
public class HistoricoPrecoResource {

    private final HistoricoPrecoService historicoPrecoService;

    public HistoricoPrecoResource(HistoricoPrecoService historicoPrecoService) {
        this.historicoPrecoService = historicoPrecoService;
    }

    @GetMapping
    public List<HistoricoPrecoProdutoResponse> listar() {
        return historicoPrecoService.historico();
    }
}
