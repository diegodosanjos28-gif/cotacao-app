package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.cotacao.core.dto.CotacaoAnteriorCursorPage;
import com.prx.cotacao.cotacao.core.dto.CotacaoAnteriorCursorProjecao;
import com.prx.cotacao.cotacao.core.dto.CotacaoAnteriorCursorResponse;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

// Carrossel "Cotações anteriores" da landing da Entrada de Dados (refactor) — mesma
// mecânica de cursor/keyset de EconomiaCarrosselService (Dashboard), DTO próprio (ver
// CotacaoAnteriorCursorResponse). Só cotações FINALIZADA — "Reabrir" no frontend
// carrega o mapaFinalSnapshot já congelado dessas cotações, nunca recalcula.
@Service
public class CotacaoAnterioresService {

    private final CotacaoProdutoFornecedorRepository cpfRepository;

    public CotacaoAnterioresService(CotacaoProdutoFornecedorRepository cpfRepository) {
        this.cpfRepository = cpfRepository;
    }

    @Transactional(readOnly = true)
    public CotacaoAnteriorCursorPage paginar(String cursor, int size) {
        // Sem teto, ?size=999999999 força uma query pesada (CTEs com múltiplos joins)
        // — achado do security-reviewer, mesmo raciocínio de proteção contra abuso.
        int limite = Math.min(Math.max(size, 1), 100);

        OffsetDateTime cursorFinalizadaEm = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] partes = cursor.split("_", 2);
            if (partes.length != 2) {
                throw new IllegalArgumentException("Cursor inválido.");
            }
            try {
                cursorFinalizadaEm = OffsetDateTime.parse(partes[0]);
                cursorId = UUID.fromString(partes[1]);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Cursor inválido.");
            }
        }

        // Busca limite+1 pra derivar hasMore sem um COUNT separado — a linha extra
        // nunca é devolvida ao cliente.
        List<CotacaoAnteriorCursorProjecao> linhas =
                cpfRepository.buscarPaginaAnteriores(cursorFinalizadaEm, cursorId, limite + 1);

        boolean hasMore = linhas.size() > limite;
        List<CotacaoAnteriorCursorProjecao> pagina = hasMore ? linhas.subList(0, limite) : linhas;

        List<CotacaoAnteriorCursorResponse> items = pagina.stream()
                .map(p -> new CotacaoAnteriorCursorResponse(
                        p.getId(), p.getFinalizadaEm().atOffset(ZoneOffset.UTC),
                        p.getItensListaBase(), p.getItensCotados(), p.getFornecedoresCount()))
                .toList();

        String nextCursor = null;
        if (hasMore && !pagina.isEmpty()) {
            CotacaoAnteriorCursorProjecao ultimo = pagina.get(pagina.size() - 1);
            nextCursor = ultimo.getFinalizadaEm() + "_" + ultimo.getId();
        }

        long total = cpfRepository.contarAnteriores();

        return new CotacaoAnteriorCursorPage(items, nextCursor, hasMore, total);
    }
}
