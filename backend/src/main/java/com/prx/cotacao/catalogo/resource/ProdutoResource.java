package com.prx.cotacao.catalogo.resource;

import com.prx.cotacao.catalogo.dto.ProdutoRequest;
import com.prx.cotacao.catalogo.dto.ProdutoResponse;
import com.prx.cotacao.catalogo.service.ProdutoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/produtos")
public class ProdutoResource {

    private final ProdutoService produtoService;

    public ProdutoResource(ProdutoService produtoService) {
        this.produtoService = produtoService;
    }

    @GetMapping
    public List<ProdutoResponse> buscar(@RequestParam(required = false) String q) {
        return produtoService.buscar(q).stream().map(ProdutoResponse::from).toList();
    }

    @PutMapping("/{id}")
    public ProdutoResponse atualizar(@PathVariable UUID id, @Valid @RequestBody ProdutoRequest request) {
        return ProdutoResponse.from(produtoService.atualizar(id, request));
    }
}
