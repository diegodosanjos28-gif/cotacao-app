package com.prx.cotacao.catalogo.resource;

import com.prx.cotacao.catalogo.dto.ProdutoRequest;
import com.prx.cotacao.catalogo.dto.ProdutoResponse;
import com.prx.cotacao.catalogo.service.ProdutoService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // Resolução de nome por IDs conhecidos (ex: itens já salvos numa cotação) — ignora
    // paginação, devolve a lista completa desses IDs. Usado só para resolução de nome,
    // nunca como listagem/autocomplete (esse usa page/size no outro método abaixo).
    @GetMapping(params = "ids")
    public List<ProdutoResponse> buscarPorIds(@RequestParam List<UUID> ids) {
        return produtoService.buscarPorIds(ids).stream().map(ProdutoResponse::from).toList();
    }

    @GetMapping
    public Page<ProdutoResponse> buscar(@RequestParam(required = false) String q, Pageable pageable) {
        return produtoService.buscar(q, pageable).map(ProdutoResponse::from);
    }

    @PutMapping("/{id}")
    public ProdutoResponse atualizar(@PathVariable UUID id, @Valid @RequestBody ProdutoRequest request) {
        return ProdutoResponse.from(produtoService.atualizar(id, request));
    }
}
