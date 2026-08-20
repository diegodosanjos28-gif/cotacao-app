package com.prx.cotacao.catalogo.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.dto.ProdutoRequest;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProdutoService {

    private final ProdutoRepository produtoRepository;

    public ProdutoService(ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    public Page<Produto> buscar(String q, Pageable pageable) {
        // Hibernate filter aplica tenant automaticamente
        if (q != null && !q.isBlank()) {
            return produtoRepository.buscarPorNome(q.trim(), pageable);
        }
        return produtoRepository.findAll(pageable);
    }

    // Resolução de nome por IDs conhecidos (ex: produtoIdEncontrado de itens já salvos
    // numa cotação) — bounded pelo conjunto de IDs pedido, não pelo catálogo do tenant.
    // Hibernate filter aplica tenant automaticamente.
    public List<Produto> buscarPorIds(List<UUID> ids) {
        return produtoRepository.findAllById(ids);
    }

    @Transactional
    public Produto atualizar(UUID id, ProdutoRequest request) {
        Produto p = produtoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: " + id));
        if (request.nome() != null) p.setNome(request.nome());
        if (request.marca() != null) p.setMarca(request.marca());
        if (request.pesoVolumeValor() != null) p.setPesoVolumeValor(request.pesoVolumeValor());
        if (request.pesoVolumeUnidade() != null) p.setPesoVolumeUnidade(request.pesoVolumeUnidade());
        if (request.unidadePadrao() != null) p.setUnidadePadrao(request.unidadePadrao());
        return produtoRepository.save(p);
    }
}
