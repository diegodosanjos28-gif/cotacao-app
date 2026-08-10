package com.prx.cotacao.catalogo.service;

import com.prx.cotacao.catalogo.entity.Produto;
import com.prx.cotacao.catalogo.dto.ProdutoRequest;
import com.prx.cotacao.catalogo.repository.ProdutoRepository;
import com.prx.cotacao.shared.error.ResourceNotFoundException;
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

    public List<Produto> buscar(String q) {
        // Hibernate filter aplica tenant automaticamente
        if (q != null && !q.isBlank()) {
            return produtoRepository.buscarPorNome(q.trim());
        }
        return produtoRepository.findAll();
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
