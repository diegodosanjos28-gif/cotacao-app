package com.prx.cotacao.cotacao.comparativo.resource;

import com.prx.cotacao.cotacao.comparativo.dto.ComparativoItemResponse;
import com.prx.cotacao.cotacao.comparativo.dto.ConcentracaoFornecedoresResponse;
import com.prx.cotacao.cotacao.comparativo.dto.CotacaoFinalizadaCursorPage;
import com.prx.cotacao.cotacao.comparativo.dto.EconomiaResumoResponse;
import com.prx.cotacao.cotacao.comparativo.dto.VariacaoPrecoResponse;
import com.prx.cotacao.cotacao.comparativo.service.ComparativoService;
import com.prx.cotacao.cotacao.comparativo.service.ConcentracaoFornecedorService;
import com.prx.cotacao.cotacao.comparativo.service.EconomiaCarrosselService;
import com.prx.cotacao.cotacao.comparativo.service.EconomiaResumoService;
import com.prx.cotacao.cotacao.comparativo.service.VariacaoPrecoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/cotacoes")
public class ComparativoResource {

    private final ComparativoService comparativoService;
    private final EconomiaResumoService economiaResumoService;
    private final EconomiaCarrosselService economiaCarrosselService;
    private final VariacaoPrecoService variacaoPrecoService;
    private final ConcentracaoFornecedorService concentracaoFornecedorService;

    public ComparativoResource(ComparativoService comparativoService,
                                EconomiaResumoService economiaResumoService,
                                EconomiaCarrosselService economiaCarrosselService,
                                VariacaoPrecoService variacaoPrecoService,
                                ConcentracaoFornecedorService concentracaoFornecedorService) {
        this.comparativoService = comparativoService;
        this.economiaResumoService = economiaResumoService;
        this.economiaCarrosselService = economiaCarrosselService;
        this.variacaoPrecoService = variacaoPrecoService;
        this.concentracaoFornecedorService = concentracaoFornecedorService;
    }

    // "2026-08" -> YearMonth; DateTimeParseException não é IllegalArgumentException
    // por padrão (GlobalExceptionHandler só mapeia essa última pra 400), então
    // rewrap explícito aqui em vez de deixar cair no catch-all genérico de 500.
    private YearMonth parseMes(String mes) {
        try {
            return YearMonth.parse(mes);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Parâmetro 'mes' inválido — use o formato AAAA-MM.");
        }
    }

    // Mesmo motivo de parseMes: erro de parsing precisa virar 400, não cair no
    // catch-all de 500. null (parâmetro omitido) passa direto — inicio/fim do
    // carrossel são opcionais.
    private LocalDate parseData(String data, String nomeParametro) {
        if (data == null) return null;
        try {
            return LocalDate.parse(data);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Parâmetro '" + nomeParametro + "' inválido — use o formato AAAA-MM-DD.");
        }
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

    // Carrossel "Cotações Registradas" (Dashboard) — paginação por cursor/keyset, ver
    // EconomiaCarrosselService. cursor omitido/vazio = primeira página. inicio/fim
    // (AAAA-MM-DD, opcionais) filtram por período — trocar o período reinicia a
    // paginação no frontend (novo cursor null).
    @GetMapping("/economia-carrossel")
    public CotacaoFinalizadaCursorPage economiaCarrossel(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String inicio,
            @RequestParam(required = false) String fim) {
        return economiaCarrosselService.paginar(cursor, size, parseData(inicio, "inicio"), parseData(fim, "fim"));
    }

    // Painel "Variação de Preço por Produto" (Dashboard) — ver VariacaoPrecoService.
    @GetMapping("/variacao-preco")
    public VariacaoPrecoResponse variacaoPreco(@RequestParam String mes) {
        return variacaoPrecoService.topSpread(parseMes(mes));
    }

    // Painel "Concentração de Fornecedores" (Dashboard) — ver ConcentracaoFornecedorService.
    @GetMapping("/concentracao-fornecedores")
    public ConcentracaoFornecedoresResponse concentracaoFornecedores(@RequestParam String mes) {
        return concentracaoFornecedorService.topConcentracao(parseMes(mes));
    }
}
