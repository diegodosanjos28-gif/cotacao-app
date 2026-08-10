package com.prx.cotacao.cotacao.comparativo.resource;

import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
import com.prx.cotacao.cotacao.comparativo.service.ComparativoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/cotacoes")
public class ComparativoResource {

    private final ComparativoService comparativoService;

    public ComparativoResource(ComparativoService comparativoService) {
        this.comparativoService = comparativoService;
    }

    @GetMapping("/{id}/comparativo")
    public List<ComparativoItemResponse> comparativo(@PathVariable UUID id) {
        return comparativoService.comparativo(id);
    }
}
