package com.prx.cotacao.cotacao.comparativo.service;

import com.prx.cotacao.cotacao.comparativo.dto.CotacaoFinalizadaCursorPage;
import com.prx.cotacao.cotacao.comparativo.dto.CotacaoFinalizadaCursorProjecao;
import com.prx.cotacao.cotacao.comparativo.dto.CotacaoFinalizadaCursorResponse;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

// Carrossel "Cotações Registradas" (Dashboard) — primeira paginação por cursor/keyset
// do projeto (as demais telas usam Page<T> offset-based, ver CotacaoRepository.
// buscar()). Cursor é um token opaco "<finalizadaEmIso>_<id>": o frontend nunca monta
// nem interpreta esse valor, só repassa de volta o nextCursor recebido — o tie-break
// por id fica um detalhe interno daqui, não um contrato implícito com o cliente.
@Service
public class EconomiaCarrosselService {

    private final CotacaoProdutoFornecedorRepository cpfRepository;

    public EconomiaCarrosselService(CotacaoProdutoFornecedorRepository cpfRepository) {
        this.cpfRepository = cpfRepository;
    }

    @Transactional(readOnly = true)
    public CotacaoFinalizadaCursorPage paginar(String cursor, int size, LocalDate inicio, LocalDate fim) {
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

        // Mesmo fuso da competência dos painéis de insight (VariacaoPrecoService) —
        // um único ponto de verdade pro "dia" do usuário virar range em UTC/timestamptz.
        // :fim é inclusivo pro usuário ("até 20/08" inclui o dia inteiro) — soma 1 dia
        // e usa como limite exclusivo, mesmo padrão do fim do mês em VariacaoPrecoService.
        OffsetDateTime inicioOffset = inicio != null
                ? inicio.atStartOfDay(VariacaoPrecoService.FUSO_COMPETENCIA).toOffsetDateTime()
                : null;
        OffsetDateTime fimOffset = fim != null
                ? fim.plusDays(1).atStartOfDay(VariacaoPrecoService.FUSO_COMPETENCIA).toOffsetDateTime()
                : null;

        // Busca size+1 pra derivar hasMore sem um COUNT separado — a linha extra
        // nunca é devolvida ao cliente.
        List<CotacaoFinalizadaCursorProjecao> linhas =
                cpfRepository.buscarPaginaCarrossel(cursorFinalizadaEm, cursorId, size + 1, inicioOffset, fimOffset);

        boolean hasMore = linhas.size() > size;
        List<CotacaoFinalizadaCursorProjecao> pagina = hasMore ? linhas.subList(0, size) : linhas;

        List<CotacaoFinalizadaCursorResponse> items = pagina.stream()
                .map(p -> new CotacaoFinalizadaCursorResponse(
                        p.getId(), p.getFinalizadaEm().atOffset(ZoneOffset.UTC), p.getItensCotados(),
                        p.getValorTotalComprado(), p.getEconomizado(), p.getEconomizadoPct(), p.getFornecedoresCount()))
                .toList();

        String nextCursor = null;
        if (hasMore && !pagina.isEmpty()) {
            CotacaoFinalizadaCursorProjecao ultimo = pagina.get(pagina.size() - 1);
            nextCursor = ultimo.getFinalizadaEm() + "_" + ultimo.getId();
        }

        // Total real de cotações que batem com o filtro (inicio/fim) — nunca o total
        // já carregado no cliente (achado do usuário: o badge não pode refletir só a
        // primeira leva).
        long total = cpfRepository.contarFinalizadas(inicioOffset, fimOffset);

        return new CotacaoFinalizadaCursorPage(items, nextCursor, hasMore, total);
    }
}
