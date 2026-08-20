package com.prx.cotacao.cotacao.core.dto;

import java.time.Instant;
import java.util.UUID;

// Projeção da query nativa de página do carrossel "Cotações anteriores"
// (CotacaoProdutoFornecedorRepository.buscarPaginaAnteriores) — getFinalizadaEm()
// como Instant, não OffsetDateTime, mesmo motivo de CotacaoFinalizadaCursorProjecao
// (Dashboard): o driver JDBC devolve Instant pra timestamptz em projeção nativa.
public interface CotacaoAnteriorCursorProjecao {
    UUID getId();
    Instant getFinalizadaEm();
    long getItensListaBase();
    long getItensCotados();
    long getFornecedoresCount();
}
