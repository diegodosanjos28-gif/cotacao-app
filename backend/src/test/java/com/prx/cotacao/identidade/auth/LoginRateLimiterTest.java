package com.prx.cotacao.identidade.auth;

import com.prx.cotacao.identidade.auth.dto.LoginRateLimiter;
import com.prx.cotacao.identidade.auth.dto.RateLimitException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes unitários de LoginRateLimiter — sem contexto Spring, instanciando direto via
 * o construtor package-private (que aceita um Clock injetado) para controlar o avanço
 * do tempo sem Thread.sleep.
 *
 * Cobre as três camadas independentes descritas nos comentários da classe: flood por
 * IP (toda tentativa conta), lockout primário por (e-mail+IP) e lockout secundário só
 * por e-mail (limiar bem mais alto, defesa contra ataque distribuído em vários IPs).
 */
class LoginRateLimiterTest {

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

    // construtor: ipMaxAttempts, ipWindowSeconds, emailIpMaxFailures, emailIpWindowSeconds,
    //             emailMaxFailures, emailWindowSeconds, clock

    // ── checkIp ──────────────────────────────────────────────────────────────

    @Test
    void checkIp_permiteAteOLimiteMaximoNaMesmaJanela() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 60, 5, 900, 30, 1800, Clock.systemUTC());

        assertDoesNotThrow(() -> limiter.checkIp("1.2.3.4"));
        assertDoesNotThrow(() -> limiter.checkIp("1.2.3.4"));
        assertDoesNotThrow(() -> limiter.checkIp("1.2.3.4"));
    }

    @Test
    void checkIp_lancaAoExcederOLimiteNaMesmaJanela() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 60, 5, 900, 30, 1800, Clock.systemUTC());

        limiter.checkIp("1.2.3.4");
        limiter.checkIp("1.2.3.4");
        limiter.checkIp("1.2.3.4");

        // 4ª chamada excede ipMaxAttempts=3 dentro da mesma janela.
        assertThrows(RateLimitException.class, () -> limiter.checkIp("1.2.3.4"));
    }

    @Test
    void checkIp_janelaExpirada_liberaNovasTentativas() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(2, 10, 5, 900, 30, 1800, clock);

        limiter.checkIp("1.2.3.4");
        limiter.checkIp("1.2.3.4");
        assertThrows(RateLimitException.class, () -> limiter.checkIp("1.2.3.4"));

        clock.advance(Duration.ofSeconds(11));

        assertDoesNotThrow(() -> limiter.checkIp("1.2.3.4"));
    }

    // ── reserveLoginAttempt: camada primária (e-mail + IP) ──────────────────

    @Test
    void reserveLoginAttempt_naoBloqueiaEnquantoFalhasEstaoAbaixoDoLimiteEmailIp() {
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 3, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        // 1 falha, limite emailIp é 3 — ainda não deve bloquear.
        assertDoesNotThrow(() -> limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4"));
    }

    @Test
    void reserveLoginAttempt_lancaAoAtingirLimiteDeEmailMaisIp() {
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 3, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        // 3 tentativas == emailIpMaxFailures=3 — a 4ª (mesmo par email+ip) já bloqueia.

        assertThrows(RateLimitException.class, () -> limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4"));
    }

    @Test
    void reserveLoginAttempt_ipsDiferentesNaoColidemNaCamadaEmailIp() {
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 2, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("foo@bar.com", "1.1.1.1");
        limiter.reserveLoginAttempt("foo@bar.com", "1.1.1.1");
        // foo@bar.com a partir de 1.1.1.1 atinge o limite de 2 — bloqueado NESSE par.
        assertThrows(RateLimitException.class, () -> limiter.reserveLoginAttempt("foo@bar.com", "1.1.1.1"));

        // O mesmo e-mail a partir de outro IP não está bloqueado pela camada
        // email+ip (chave composta diferente) — só entraria em jogo a camada
        // secundária email-only, que aqui tem limiar bem mais alto (30) e não foi atingida.
        assertDoesNotThrow(() -> limiter.reserveLoginAttempt("foo@bar.com", "2.2.2.2"));
    }

    // ── reserveLoginAttempt: camada secundária (e-mail sozinho, ataque distribuído) ──

    @Test
    void reserveLoginAttempt_ataqueDistribuidoEmMuitosIpsAcabaBloqueadoPelaCamadaSoEmail() {
        // Limiar da camada email+ip alto o bastante pra nunca disparar sozinho aqui
        // (cada IP usado só uma vez); quem bloqueia é a camada email-only (limiar 3).
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 100, 900, 3, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("vitima@bar.com", "1.1.1.1");
        limiter.reserveLoginAttempt("vitima@bar.com", "2.2.2.2");
        limiter.reserveLoginAttempt("vitima@bar.com", "3.3.3.3");
        // 3 falhas de 3 IPs diferentes == emailMaxFailures=3 — a 4ª, de um IP inédito,
        // ainda deve ser barrada pela camada distribuída.

        assertThrows(RateLimitException.class,
                () -> limiter.reserveLoginAttempt("vitima@bar.com", "4.4.4.4"));
    }

    @Test
    void reserveLoginAttempt_umUnicoIpMaliciosoNaoPrecisaEsgotarOLimiarDistribuidoParaSerBloqueado() {
        // Regressão do achado do security-reviewer: um único IP atacante insistindo
        // contra a mesma conta é barrado pela camada email+ip (limiar baixo), bem
        // antes de chegar perto do limiar mais alto pensado pra ataque distribuído.
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 2, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("vitima@bar.com", "9.9.9.9");
        limiter.reserveLoginAttempt("vitima@bar.com", "9.9.9.9");
        // 2 falhas do mesmo IP == emailIpMaxFailures=2 — já bloqueado, longe dos 30
        // necessários pra disparar a camada frouxa.

        assertThrows(RateLimitException.class,
                () -> limiter.reserveLoginAttempt("vitima@bar.com", "9.9.9.9"));
    }

    @Test
    void onLoginSuccess_resetaAsDuasCamadasDeLockout() {
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 3, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        // 2 falhas acumuladas, ainda abaixo do limite de 3 na camada email+ip.

        limiter.onLoginSuccess("foo@bar.com", "1.2.3.4");

        // Depois do sucesso, as 2 falhas anteriores não contam mais: uma nova falha
        // isolada não deveria, sozinha, disparar o lockout (limite é 3, não 1).
        assertDoesNotThrow(() -> limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4"));
    }

    @Test
    void emailsDiferentesTemContadoresDeFalhaIndependentes() {
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 2, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");
        // foo@bar.com atinge o limite de 2 falhas (mesmo ip) — deve estar bloqueado.
        assertThrows(RateLimitException.class, () -> limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4"));

        // outro e-mail nunca falhou — não deve ser afetado pelo lockout de foo@bar.com.
        assertDoesNotThrow(() -> limiter.reserveLoginAttempt("outro@bar.com", "1.2.3.4"));
    }

    @Test
    void emailNormalizado_espacosEMaiusculasBatemNaMesmaChaveDoEmailJaRegistrado() {
        LoginRateLimiter limiter = new LoginRateLimiter(20, 60, 2, 900, 30, 1800, Clock.systemUTC());

        limiter.reserveLoginAttempt(" Foo@Bar.com ", "1.2.3.4");
        limiter.reserveLoginAttempt(" Foo@Bar.com ", "1.2.3.4");
        // 2 falhas registradas sob a forma não normalizada.

        // A tentativa seguinte com a forma normalizada (sem espaços, minúsculo) deve
        // enxergar o mesmo contador — prova que normalize() (.strip().toLowerCase()) é
        // aplicado de forma consistente.
        assertThrows(RateLimitException.class,
                () -> limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4"));
    }

    // ── cleanup (scheduled, chamado direto no teste) ────────────────────────

    @Test
    void cleanup_naoLancaEJanelaExpiradaSeComportaComoNovaAposLimpeza() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(1, 10, 1, 10, 1, 10, clock);

        limiter.checkIp("1.2.3.4");
        limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4");

        clock.advance(Duration.ofSeconds(11));

        // cleanup() é package-private (chamado normalmente pelo @Scheduled) — aqui
        // chamado direto, só para confirmar que não lança e que entradas expiradas
        // saem do mapa (evitando crescimento sem limite, ver comentário na classe).
        assertDoesNotThrow(limiter::cleanup);

        // Depois da limpeza, IP e e-mail voltam a se comportar como se nunca tivessem
        // sido vistos (janela expirada de qualquer forma já teria esse efeito — cleanup
        // só antecipa a remoção da entrada do mapa).
        assertDoesNotThrow(() -> limiter.checkIp("1.2.3.4"));
        assertDoesNotThrow(() -> limiter.reserveLoginAttempt("foo@bar.com", "1.2.3.4"));
    }
}
