package com.prx.cotacao.historico.resource;

import com.prx.cotacao.historico.service.HistoricoPrecoService;
import com.prx.cotacao.historico.dto.HistoricoPrecoPageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/historico-precos")
public class HistoricoPrecoResource {

    private final HistoricoPrecoService historicoPrecoService;

    public HistoricoPrecoResource(HistoricoPrecoService historicoPrecoService) {
        this.historicoPrecoService = historicoPrecoService;
    }

    @GetMapping
    public HistoricoPrecoPageResponse listar(Pageable pageable, @RequestParam(required = false) String q) {
        return historicoPrecoService.historico(pageable, q);
    }
}
