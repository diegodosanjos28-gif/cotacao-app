package com.prx.cotacao.cotacao.core.resource;

import com.prx.cotacao.cotacao.respostafornecedor.dto.AdicionarFornecedorCotacaoRequest;
import com.prx.cotacao.cotacao.core.dto.AdicionarItemCotacaoRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ConfirmarRespostaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.CotacaoFornecedorResponse;
import com.prx.cotacao.cotacao.core.dto.CotacaoResponse;
import com.prx.cotacao.cotacao.core.dto.CriarCotacaoRequest;
import com.prx.cotacao.cotacao.core.dto.EditarItemCotacaoRequest;
import com.prx.cotacao.cotacao.core.dto.EnviarListaRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.EnviarRespostaRequest;
import com.prx.cotacao.cotacao.core.dto.ImportarTextoItemResponse;
import com.prx.cotacao.cotacao.core.dto.ItemListaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ItemRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.PreviewRespostaResponse;
import com.prx.cotacao.cotacao.respostafornecedor.dto.ResolverAvisoRequest;
import com.prx.cotacao.cotacao.respostafornecedor.dto.RespostaPersistidaResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.prx.cotacao.cotacao.respostafornecedor.service.AvisoService;
import com.prx.cotacao.cotacao.respostafornecedor.service.ConfirmacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.service.CotacaoFornecedorService;
import com.prx.cotacao.cotacao.core.service.CotacaoListaService;
import com.prx.cotacao.cotacao.core.service.CotacaoProdutoItemService;
import com.prx.cotacao.cotacao.core.service.CotacaoService;
import com.prx.cotacao.cotacao.respostafornecedor.service.FornecedorRespostaService;
import com.prx.cotacao.cotacao.mensagem.service.MensagemService;
import com.prx.cotacao.cotacao.mapacompra.enums.CenarioSelecionado;
import com.prx.cotacao.cotacao.core.entity.Cotacao;
import com.prx.cotacao.cotacao.respostafornecedor.entity.CotacaoProdutoFornecedor;

@RestController
@RequestMapping("/cotacoes")
public class CotacaoResource {

    private final CotacaoService cotacaoService;
    private final CotacaoListaService listaService;
    private final FornecedorRespostaService respostaService;
    private final AvisoService avisoService;
    private final MensagemService mensagemService;
    private final CotacaoFornecedorService cotacaoFornecedorService;
    private final ConfirmacaoRespostaService confirmacaoRespostaService;
    private final CotacaoProdutoItemService cotacaoProdutoItemService;

    public CotacaoResource(CotacaoService cotacaoService,
                           CotacaoListaService listaService,
                           FornecedorRespostaService respostaService,
                           AvisoService avisoService,
                           MensagemService mensagemService,
                           CotacaoFornecedorService cotacaoFornecedorService,
                           ConfirmacaoRespostaService confirmacaoRespostaService,
                           CotacaoProdutoItemService cotacaoProdutoItemService) {
        this.cotacaoService = cotacaoService;
        this.listaService = listaService;
        this.respostaService = respostaService;
        this.avisoService = avisoService;
        this.mensagemService = mensagemService;
        this.cotacaoFornecedorService = cotacaoFornecedorService;
        this.confirmacaoRespostaService = confirmacaoRespostaService;
        this.cotacaoProdutoItemService = cotacaoProdutoItemService;
    }

    @PostMapping
    public ResponseEntity<CotacaoResponse> criar(@Valid @RequestBody CriarCotacaoRequest request) {
        Cotacao cotacao = cotacaoService.criar(request);
        return ResponseEntity.created(URI.create("/cotacoes/" + cotacao.getId())).body(CotacaoResponse.from(cotacao));
    }

    @GetMapping
    public Page<CotacaoResponse> listar(Pageable pageable) {
        return cotacaoService.listar(pageable).map(CotacaoResponse::from);
    }

    @GetMapping("/{id}")
    public CotacaoResponse buscar(@PathVariable UUID id) {
        return CotacaoResponse.from(cotacaoService.buscar(id));
    }

    @GetMapping("/{id}/fornecedores")
    public List<CotacaoFornecedorResponse> listarFornecedoresDaCotacao(@PathVariable UUID id) {
        return cotacaoFornecedorService.listar(id);
    }

    @PostMapping("/{id}/fornecedores")
    public ResponseEntity<CotacaoFornecedorResponse> adicionarFornecedorNaCotacao(
            @PathVariable UUID id, @Valid @RequestBody AdicionarFornecedorCotacaoRequest request) {
        CotacaoFornecedorResponse response = cotacaoFornecedorService.adicionar(id, request);
        return ResponseEntity.created(URI.create("/cotacoes/" + id + "/fornecedores/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}/lista")
    public List<ItemListaResponse> listarLista(@PathVariable UUID id) {
        return listaService.listar(id);
    }

    @PostMapping("/{id}/lista")
    public List<ItemListaResponse> enviarLista(@PathVariable UUID id,
                                                @Valid @RequestBody EnviarListaRequest request) {
        return listaService.processarLista(id, request.texto());
    }

    @PostMapping("/{id}/lista/concluir-ajuste")
    public CotacaoResponse concluirAjusteLista(@PathVariable UUID id) {
        return CotacaoResponse.from(cotacaoService.concluirAjusteLista(id));
    }

    @PostMapping("/{id}/fornecedores/{fornId}/resposta")
    public PreviewRespostaResponse enviarResposta(@PathVariable UUID id,
                                                    @PathVariable UUID fornId,
                                                    @Valid @RequestBody EnviarRespostaRequest request) {
        return respostaService.gerarPreview(id, fornId, request.texto());
    }

    @GetMapping("/{id}/fornecedores/{fornId}/resposta-persistida")
    public RespostaPersistidaResponse respostaPersistida(@PathVariable UUID id, @PathVariable UUID fornId) {
        return new RespostaPersistidaResponse(respostaService.textoPersistido(id, fornId));
    }

    @DeleteMapping("/{id}/fornecedores/{fornId}/resposta")
    public ResponseEntity<Void> cancelarResposta(@PathVariable UUID id, @PathVariable UUID fornId) {
        respostaService.cancelarResposta(id, fornId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fornecedores/{fornId}/confirmar")
    public List<ItemRespostaResponse> confirmarResposta(@PathVariable UUID id,
                                                          @PathVariable UUID fornId,
                                                          @Valid @RequestBody ConfirmarRespostaRequest request) {
        return confirmacaoRespostaService.confirmar(id, fornId, request);
    }

    @PostMapping("/{id}/avisos/{cpfId}/resolver")
    public CotacaoProdutoFornecedor resolverAviso(@PathVariable UUID id,
                                                   @PathVariable UUID cpfId,
                                                   @Valid @RequestBody ResolverAvisoRequest request) {
        return avisoService.resolver(id, cpfId, request.embalagemQtd());
    }

    @PostMapping("/{id}/produtos")
    public ResponseEntity<ItemListaResponse> adicionarItem(@PathVariable UUID id,
                                                            @Valid @RequestBody AdicionarItemCotacaoRequest request) {
        ItemListaResponse response = cotacaoProdutoItemService.adicionar(id, request);
        return ResponseEntity.created(URI.create("/cotacoes/" + id + "/produtos/" + response.id())).body(response);
    }

    @PostMapping("/{id}/produtos/importar-texto")
    public List<ImportarTextoItemResponse> importarTexto(@PathVariable UUID id,
                                                          @Valid @RequestBody EnviarListaRequest request) {
        return listaService.importarTexto(id, request.texto());
    }

    @PatchMapping("/{id}/produtos/{cotacaoProdutoId}")
    public ResponseEntity<Void> editarItem(@PathVariable UUID id, @PathVariable UUID cotacaoProdutoId,
                                            @Valid @RequestBody EditarItemCotacaoRequest request) {
        cotacaoProdutoItemService.editar(id, cotacaoProdutoId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/produtos/{cotacaoProdutoId}")
    public ResponseEntity<Void> removerItem(@PathVariable UUID id, @PathVariable UUID cotacaoProdutoId) {
        cotacaoProdutoItemService.remover(id, cotacaoProdutoId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/fornecedores/{fornId}/mensagem")
    public ResponseEntity<Map<String, String>> mensagem(@PathVariable UUID id, @PathVariable UUID fornId,
                                                          @RequestParam CenarioSelecionado cenario) {
        String texto = mensagemService.gerarMensagem(id, fornId, cenario);
        return ResponseEntity.ok(Map.of("mensagem", texto));
    }

    @PostMapping("/{id}/finalizar")
    public CotacaoResponse finalizar(@PathVariable UUID id, @RequestParam CenarioSelecionado cenario) {
        return CotacaoResponse.from(cotacaoService.finalizar(id, cenario));
    }
}
