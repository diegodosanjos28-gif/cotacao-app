package com.prx.cotacao.fornecedor.resource;

import com.prx.cotacao.cotacao.hall.dto.FornecedorHistoricoResponse;
import com.prx.cotacao.cotacao.hall.service.HallFornecedoresService;
import com.prx.cotacao.fornecedor.service.FornecedorService;
import com.prx.cotacao.fornecedor.dto.FornecedorRequest;
import com.prx.cotacao.fornecedor.dto.FornecedorResponse;
import com.prx.cotacao.fornecedor.entity.Fornecedor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/fornecedores")
public class FornecedorResource {

    private final FornecedorService fornecedorService;
    private final HallFornecedoresService hallFornecedoresService;

    public FornecedorResource(FornecedorService fornecedorService, HallFornecedoresService hallFornecedoresService) {
        this.fornecedorService = fornecedorService;
        this.hallFornecedoresService = hallFornecedoresService;
    }

    @GetMapping
    public List<FornecedorResponse> listar() {
        return fornecedorService.listar().stream().map(FornecedorResponse::from).toList();
    }

    // Hall dos Fornecedores, modo histórico (landing da Entrada de Dados, refactor).
    @GetMapping("/historico")
    public List<FornecedorHistoricoResponse> historico() {
        return hallFornecedoresService.historico();
    }

    @PostMapping
    public ResponseEntity<FornecedorResponse> criar(@Valid @RequestBody FornecedorRequest request) {
        Fornecedor f = fornecedorService.criar(request);
        return ResponseEntity.created(URI.create("/fornecedores/" + f.getId())).body(FornecedorResponse.from(f));
    }

    @PutMapping("/{id}")
    public FornecedorResponse atualizar(@PathVariable UUID id, @Valid @RequestBody FornecedorRequest request) {
        return FornecedorResponse.from(fornecedorService.atualizar(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> inativar(@PathVariable UUID id) {
        fornecedorService.inativar(id);
        return ResponseEntity.noContent().build();
    }
}
