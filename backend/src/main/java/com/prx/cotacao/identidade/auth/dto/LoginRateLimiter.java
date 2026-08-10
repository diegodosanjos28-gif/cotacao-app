package com.prx.cotacao.identidade.auth.dto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Proteção contra brute force em /auth/login e /auth/refresh, em memória — deploy é
// droplet único (sem Redis), então um contador local é suficiente; se um dia houver
// múltiplas instâncias do backend, isso precisa virar um store compartilhado (Redis).
//
// Três camadas independentes:
// - Por IP (checkIp): flood genérico na superfície /auth/** inteira (login+refresh
//   compartilham o mesmo bucket), não importa qual conta.
// - Por (e-mail + IP) — camada primária de lockout: limiar apertado, pega um atacante
//   de fonte única tentando adivinhar a senha de uma conta específica.
// - Por e-mail sozinho — camada secundária, limiar bem mais alto: pega um ataque
//   distribuído (muitos IPs, mesma conta) sem deixar um único IP malicioso derrubar o
//   acesso do usuário legítimo com só 5 tentativas de terceiros (achado do
//   security-reviewer: lockout só-por-e-mail com limiar baixo é um DoS direcionado
//   barato contra um usuário específico, real dado o perfil de poucos usuários por
//   tenant deste sistema).
// Nenhuma camada distingue e-mail existente de inexistente (a chave é sempre
// normalize(email) cru), então não vaza informação de existência de conta.
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final int ipMaxAttempts;
    private final long ipWindowMs;
    private final int emailIpMaxFailures;
    private final long emailIpWindowMs;
    private final int emailMaxFailures;
    private final long emailWindowMs;

    private final Map<String, Window> byIp = new ConcurrentHashMap<>();
    private final Map<String, Window> byEmailIp = new ConcurrentHashMap<>();
    private final Map<String, Window> byEmail = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LoginRateLimiter(
            @Value("${app.rate-limit.auth.ip-max-attempts:20}") int ipMaxAttempts,
            @Value("${app.rate-limit.auth.ip-window-seconds:60}") long ipWindowSeconds,
            @Value("${app.rate-limit.login.email-ip-max-failures:15}") int emailIpMaxFailures,
            @Value("${app.rate-limit.login.email-ip-window-seconds:900}") long emailIpWindowSeconds,
            @Value("${app.rate-limit.login.email-max-failures:30}") int emailMaxFailures,
            @Value("${app.rate-limit.login.email-window-seconds:1800}") long emailWindowSeconds) {
        this(ipMaxAttempts, ipWindowSeconds, emailIpMaxFailures, emailIpWindowSeconds,
                emailMaxFailures, emailWindowSeconds, Clock.systemUTC());
    }

    public LoginRateLimiter(int ipMaxAttempts, long ipWindowSeconds, int emailIpMaxFailures, long emailIpWindowSeconds,
                      int emailMaxFailures, long emailWindowSeconds, Clock clock) {
        this.ipMaxAttempts = ipMaxAttempts;
        this.ipWindowMs = ipWindowSeconds * 1000;
        this.emailIpMaxFailures = emailIpMaxFailures;
        this.emailIpWindowMs = emailIpWindowSeconds * 1000;
        this.emailMaxFailures = emailMaxFailures;
        this.emailWindowMs = emailWindowSeconds * 1000;
        this.clock = clock;
    }

    // Conta toda tentativa (sucesso ou falha) contra a janela do IP — chamar antes de
    // qualquer lookup de usuário/senha.
    public void checkIp(String ip) {
        Window w = byIp.compute(ip, (k, existing) -> tick(existing, ipWindowMs));
        if (w.count.get() > ipMaxAttempts) {
            log.warn("Rate limit por IP excedido em /auth/**");
            throw new RateLimitException("Muitas tentativas. Tente novamente em instantes.");
        }
    }

    // Reserva atomicamente a tentativa nas duas camadas de e-mail ANTES do lookup de
    // usuário/senha — check e incremento acontecem dentro do mesmo compute() por
    // chave, sem a janela de corrida entre "ler contagem" e "gravar contagem" que uma
    // versão anterior deste método tinha (checkEmailLockout separado de
    // onLoginFailure permitia uma rajada paralela ultrapassar o limite antes de
    // qualquer requisição registrar sua própria falha). onLoginSuccess reverte o
    // efeito no caminho feliz.
    public void reserveLoginAttempt(String email, String ip) {
        String normalized = normalize(email);

        boolean emailIpBlocked = reserveAndCheck(byEmailIp, normalized + '|' + ip, emailIpWindowMs, emailIpMaxFailures);
        boolean emailBlocked = reserveAndCheck(byEmail, normalized, emailWindowMs, emailMaxFailures);

        if (emailIpBlocked || emailBlocked) {
            log.warn("Login bloqueado por rate limit ({})", emailIpBlocked ? "email+ip" : "email, distribuído");
            throw new RateLimitException("Conta temporariamente bloqueada por excesso de tentativas. Tente novamente mais tarde.");
        }
    }

    // Login válido encerra os dois lockouts do e-mail — não queremos punir um usuário
    // legítimo que errou a senha algumas vezes antes de acertar.
    public void onLoginSuccess(String email, String ip) {
        String normalized = normalize(email);
        byEmailIp.remove(normalized + '|' + ip);
        byEmail.remove(normalized);
    }

    private boolean reserveAndCheck(Map<String, Window> store, String key, long windowMs, int maxFailures) {
        Window w = store.compute(key, (k, existing) -> tick(existing, windowMs));
        return w.count.get() > maxFailures;
    }

    private Window tick(Window existing, long windowMs) {
        long now = clock.millis();
        if (existing == null || existing.isExpired(now, windowMs)) {
            return new Window(now);
        }
        existing.count.incrementAndGet();
        return existing;
    }

    private String normalize(String email) {
        return email.strip().toLowerCase();
    }

    // Sem isso, um atacante espalhando tentativas por muitos e-mails/IPs distintos
    // faria os mapas crescerem sem limite até o próximo restart.
    @Scheduled(fixedRateString = "${app.rate-limit.login.cleanup-interval-ms:300000}")
    public void cleanup() {
        long now = clock.millis();
        byIp.entrySet().removeIf(e -> e.getValue().isExpired(now, ipWindowMs));
        byEmailIp.entrySet().removeIf(e -> e.getValue().isExpired(now, emailIpWindowMs));
        byEmail.entrySet().removeIf(e -> e.getValue().isExpired(now, emailWindowMs));
    }

    private static final class Window {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(1);

        Window(long windowStart) {
            this.windowStart = windowStart;
        }

        boolean isExpired(long now, long windowMs) {
            return now - windowStart >= windowMs;
        }
    }
}
