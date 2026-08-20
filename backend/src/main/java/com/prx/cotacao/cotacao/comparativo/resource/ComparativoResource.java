package com.prx.cotacao.cotacao.comparativo.resource;

import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoResponse;
import com.prx.cotacao.cotacao.comparativo.service.ComparativoService;
import com.prx.cotacao.cotacao.comparativo.service.EconomiaResumoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/cotacoes")
public class ComparativoResource {

    private final ComparativoService comparativoService;
    private final EconomiaResumoService economiaResumoService;

    public ComparativoResource(ComparativoService comparativoService, EconomiaResumoService economiaResumoService) {
        this.comparativoService = comparativoService;
        this.economiaResumoService = economiaResumoService;
    }

    @GetMapping("/{id}/comparativo")
    public List<ComparativoItemResponse> comparativo(@PathVariable UUID id) {
        return comparativoService.comparativo(id);
    }

    // Comparativo de várias cotações numa única chamada — evita 1 request por linha
    // visível numa grid (ver ComparativoService.comparativoLote).
    @GetMapping("/comparativo-lote")
    public Map<UUID, List<ComparativoItemResponse>> comparativoLote(@RequestParam List<UUID> ids) {
        return comparativoService.comparativoLote(ids);
    }

    // KPIs do Dashboard ("Economia de Cotações") agregados sobre TODAS as cotações
    // FINALIZADA do tenant — ver EconomiaResumoService.
    @GetMapping("/economia-resumo")
    public EconomiaResumoResponse economiaResumo() {
        return economiaResumoService.resumo();
    }
}
