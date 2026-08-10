package com.prx.cotacao.shared.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários de GlobalIpRateLimiter — sem contexto Spring, instanciando direto via
 * o construtor package-private (que aceita um Clock injetado) para controlar o avanço do
 * tempo sem Thread.sleep. Mesmo padrão de LoginRateLimiterTest (ver referência naquele
 * arquivo, pacote identidade.auth).
 */
class GlobalIpRateLimiterTest {

    // Clock mutável de teste — permite simular o avanço do tempo sem sleep real.
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // construtor: capacity, refillIntervalMs, clock

    @Test
    void tryConsume_permiteAteACapacidadeNaMesmaJanelaERecusaAProxima() {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(3, 1000, Clock.systemUTC());

        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));

        // 4ª chamada excede capacity=3 sem nenhum refill ter ocorrido ainda.
        assertFalse(limiter.tryConsume("1.2.3.4"));
    }

    @Test
    void tryConsume_apósAvancarUmIntervalo_liberaApenasDezTokensNovos() {
        // capacity=25 (> REFILL_TOKENS_PER_INTERVAL=10) pra que o teto de capacidade não
        // mascare o valor por intervalo — com capacity<=10 um único refill sempre bate
        // no cap e o teste não distinguiria "refill de 10" de "refill pra capacidade cheia".
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(25, 1000, clock);

        for (int i = 0; i < 25; i++) {
            assertTrue(limiter.tryConsume("1.2.3.4"));
        }
        assertFalse(limiter.tryConsume("1.2.3.4"));

        clock.advance(Duration.ofMillis(1000));

        // 1 intervalo se passou: exatamente 10 novos tokens disponíveis (
        // REFILL_TOKENS_PER_INTERVAL) — não volta pra capacidade cheia (25), só
        // recupera o que o intervalo decorrido permite.
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryConsume("1.2.3.4"));
        }
        assertFalse(limiter.tryConsume("1.2.3.4"));
    }

    @Test
    void tryConsume_avancarVariosIntervalosDeUmaVez_recarregaTokensCapadoNaCapacidade() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(3, 1000, clock);

        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertFalse(limiter.tryConsume("1.2.3.4"));

        // 10 intervalos de refillIntervalMs=1000 de uma vez só (ex: IP ficou horas sem
        // request) — não deve acumular 10 tokens, só até o teto de capacity=3.
        clock.advance(Duration.ofMillis(10_000));

        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertFalse(limiter.tryConsume("1.2.3.4"));
    }

    @Test
    void tryConsume_preservaRestoDeMsEntreRefillsSemPerderPrecisao() {
        // Regressão de drift: bucket.lastRefill avança só pelos intervalos INTEIROS
        // consumidos (lastRefill += elapsedIntervals * refillIntervalMs), preservando o
        // restante em ms — não reseta lastRefill pra "agora" a cada refill parcial. Se
        // resetasse, o teste abaixo falharia no passo final (900ms adicionais não
        // seriam suficientes para completar o intervalo, porque os 100ms já decorridos
        // antes teriam sido descartados).
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, clock);

        assertTrue(limiter.tryConsume("1.2.3.4")); // consome o único token inicial

        clock.advance(Duration.ofMillis(700));
        assertFalse(limiter.tryConsume("1.2.3.4")); // só 700ms — ainda não completou 1 intervalo

        clock.advance(Duration.ofMillis(400)); // total decorrido: 1100ms desde a criação
        assertTrue(limiter.tryConsume("1.2.3.4")); // 1 intervalo completo (1000ms) — 1 token novo
        assertFalse(limiter.tryConsume("1.2.3.4")); // token já consumido, resto de 100ms não basta

        clock.advance(Duration.ofMillis(900)); // 100ms residuais + 900ms = 1000ms novos
        assertTrue(limiter.tryConsume("1.2.3.4")); // prova que o resíduo de 100ms não foi perdido
    }

    @Test
    void tryConsume_ipsDiferentesTemBucketsIndependentes() {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());

        assertTrue(limiter.tryConsume("1.1.1.1"));
        assertFalse(limiter.tryConsume("1.1.1.1"));

        // IP diferente não é afetado pelo esgotamento do bucket de 1.1.1.1.
        assertTrue(limiter.tryConsume("2.2.2.2"));
    }

    @Test
    void cleanup_removeBucketOciosoSemLancarEIpVoltaASeComportarComoNovo() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(2, 1000, clock);

        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertFalse(limiter.tryConsume("1.2.3.4"));

        // idleThresholdMs = capacity * refillIntervalMs = 2000ms — avança um pouco além.
        clock.advance(Duration.ofMillis(2001));

        // cleanup() é package-private (chamado normalmente pelo @Scheduled) — aqui
        // chamado direto, só para confirmar que não lança.
        assertDoesNotThrow(limiter::cleanup);

        // Bucket removido do mapa — próxima chamada recria do zero, cheio (capacity=2).
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertTrue(limiter.tryConsume("1.2.3.4"));
        assertFalse(limiter.tryConsume("1.2.3.4"));
    }
}
