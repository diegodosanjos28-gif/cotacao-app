package com.prx.cotacao.cotacao.comparativo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Projeção da query nativa de página do carrossel "Cotações Registradas"
// (CotacaoProdutoFornecedorRepository.buscarPaginaCarrossel) — 1 linha por cotação já
// com os agregados de itens/comprado/economizado calculados no banco (mesma base
// "vencedor por item" de EconomiaResumoService, só que por cotação em vez de somada
// sobre o tenant inteiro). getFinalizadaEm() como Instant, não OffsetDateTime — o
// driver JDBC devolve Instant pra timestamptz em projeção nativa, e o
// ProxyProjectionFactory do Spring Data não converte Instant->OffsetDateTime
// automaticamente (achado rodando o teste contra Postgres real, não só compile-time).
public interface CotacaoFinalizadaCursorProjecao {
    UUID getId();
    Instant getFinalizadaEm();
    long getItensCotados();
    BigDecimal getValorTotalComprado();
    BigDecimal getEconomizado();
    BigDecimal getEconomizadoPct();
    long getFornecedoresCount();
}
