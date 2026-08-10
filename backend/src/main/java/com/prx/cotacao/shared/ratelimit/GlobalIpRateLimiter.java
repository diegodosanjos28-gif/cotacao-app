package com.prx.cotacao.shared.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Token bucket por IP — teto genérico de flood/DoS pra qualquer rota da API (não só
// /auth/**, que já tem sua própria camada mais apertada em LoginRateLimiter).
// Capacidade default 20 absorvia mal rajadas legítimas de navegação real (várias
// telas em sequência, cada uma disparando 2-10 chamadas em paralelo — ver Promise.all
// em entrada/page.tsx e mapa/page.tsx): estourava em poucos cliques numa demonstração
// real. Em vez de só aumentar a capacidade (o que empurra o teto sustentado de longo
// prazo junto), a reposição passou de 1 para REFILL_TOKENS_PER_INTERVAL=10
// tokens/segundo — o balde volta a ter folga rápido entre uma rajada e outra, então a
// capacidade em si pôde CAIR (200 → 35) sem reintroduzir o estouro original: absorve
// ~3-4 telas de rajada instantânea (35 tokens) e, dali em diante, sustenta 10 req/s por
// IP indefinidamente. Em memória — deploy é droplet único (sem Redis); se um dia
// houver múltiplas instâncias, isso precisa virar um store compartilhado.
@Component
public class GlobalIpRateLimiter {

    // Tokens devolvidos ao bucket a cada refillIntervalMs decorrido — não configurável
    // via YAML como capacity/refillIntervalMs porque não há hoje um cenário de deploy
    // que precise variar isso independente da capacidade (ver comentário de classe).
    private static final long REFILL_TOKENS_PER_INTERVAL = 10;

    private final long capacity;
    private final long refillIntervalMs;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public GlobalIpRateLimiter(
            @Value("${app.rate-limit.global.capacity:35}") long capacity,
            @Value("${app.rate-limit.global.refill-interval-ms:1000}") long refillIntervalMs) {
        this(capacity, refillIntervalMs, Clock.systemUTC());
    }

    GlobalIpRateLimiter(long capacity, long refillIntervalMs, Clock clock) {
        this.capacity = capacity;
        this.refillIntervalMs = refillIntervalMs;
        this.clock = clock;
    }

    // Atômico por chave via Map.compute — refill, checagem e consumo do token
    // acontecem numa única operação, sem a janela de corrida entre "ler contagem" e
    // "gravar contagem" que existia na primeira versão do LoginRateLimiter.
    public boolean tryConsume(String ip) {
        AtomicBoolean allowed = new AtomicBoolean();
        buckets.compute(ip, (key, existing) -> {
            Bucket bucket = existing != null ? existing : new Bucket(capacity, clock.millis());
            refill(bucket);
            if (bucket.tokens > 0) {
                bucket.tokens--;
                allowed.set(true);
            } else {
                allowed.set(false);
            }
            return bucket;
        });
        return allowed.get();
    }

    // elapsedIntervals * REFILL_TOKENS_PER_INTERVAL, capado em capacity — igual à versão
    // anterior (que devolvia 1 token/intervalo), só que 10x mais rápido. lastRefill
    // avança só pelos intervalos INTEIROS consumidos (nunca reseta pra "agora"), então
    // ms residuais entre um refill e outro não se perdem — ver
    // GlobalIpRateLimiterTest.tryConsume_preservaRestoDeMsEntreRefillsSemPerderPrecisao.
    private void refill(Bucket bucket) {
        long now = clock.millis();
        long elapsedIntervals = (now - bucket.lastRefill) / refillIntervalMs;
        if (elapsedIntervals <= 0) return;
        bucket.tokens = Math.min(capacity, (bucket.tokens + elapsedIntervals * REFILL_TOKENS_PER_INTERVAL));
        bucket.lastRefill += elapsedIntervals * refillIntervalMs;
    }

    // Balde parado tempo suficiente pra estar cheio de novo não precisa continuar
    // ocupando memória — sem isso, um IP diferente por requisição (scraper distribuído,
    // ou só o giro natural de IPs dinâmicos) faria o mapa crescer sem limite.
    @Scheduled(fixedRateString = "${app.rate-limit.global.cleanup-interval-ms:300000}")
    void cleanup() {
        long now = clock.millis();
        long idleThresholdMs = capacity * refillIntervalMs;
        buckets.entrySet().removeIf(e -> now - e.getValue().lastRefill > idleThresholdMs);
    }

    private static final class Bucket {
        long tokens;
        long lastRefill;

        Bucket(long tokens, long lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }
}
