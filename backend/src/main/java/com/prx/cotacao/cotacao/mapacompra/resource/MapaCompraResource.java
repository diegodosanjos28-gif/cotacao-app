package com.prx.cotacao.cotacao.mapacompra.resource;

import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.mapacompra.dto.MapaCompraResponse;
import com.prx.cotacao.cotacao.mapacompra.service.CotacaoAjusteManualService;
import com.prx.cotacao.cotacao.mapacompra.service.MapaCompraService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/cotacoes")
public class MapaCompraResource {

    private final MapaCompraService mapaCompraService;
    private final CotacaoAjusteManualService ajusteManualService;

    public MapaCompraResource(MapaCompraService mapaCompraService,
                              CotacaoAjusteManualService ajusteManualService) {
        this.mapaCompraService = mapaCompraService;
        this.ajusteManualService = ajusteManualService;
    }

    @GetMapping("/{id}/mapa")
    public MapaCompraResponse mapa(@PathVariable UUID id, @RequestParam CenarioSelecionado cenario) {
        return mapaCompraService.gerar(id, cenario);
    }

    // ── Ajuste manual da distribuição do Mapa de Compra ──────────────────────────

    @PutMapping("/{id}/mapa/itens/{cotacaoProdutoId}")
    public ResponseEntity<Void> moverItemMapa(@PathVariable UUID id, @PathVariable UUID cotacaoProdutoId,
                                               @RequestParam UUID fornecedorId) {
        ajusteManualService.mover(id, cotacaoProdutoId, fornecedorId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/mapa/itens/{cotacaoProdutoId}/remover")
    public ResponseEntity<Void> removerItemMapa(@PathVariable UUID id, @PathVariable UUID cotacaoProdutoId) {
        ajusteManualService.remover(id, cotacaoProdutoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/mapa/itens/{cotacaoProdutoId}")
    public ResponseEntity<Void> restaurarItemMapa(@PathVariable UUID id, @PathVariable UUID cotacaoProdutoId) {
        ajusteManualService.restaurar(id, cotacaoProdutoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/mapa/ajustes")
    public ResponseEntity<Void> restaurarTodosAjustesMapa(@PathVariable UUID id) {
        ajusteManualService.restaurarTudo(id);
        return ResponseEntity.noContent().build();
    }
}
