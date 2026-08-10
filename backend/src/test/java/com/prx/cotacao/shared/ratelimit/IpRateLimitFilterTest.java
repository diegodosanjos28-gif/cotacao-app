package com.prx.cotacao.shared.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitários de IpRateLimitFilter — mocks diretos de HttpServletRequest,
 * HttpServletResponse e FilterChain (Mockito), sem subir contexto Spring. GlobalIpRateLimiter
 * usado é real (não mockado), com capacidade baixa, pra exercitar o comportamento de ponta a
 * ponta entre o filtro e o limiter. CorsConfigurationSource é mockado retornando uma
 * CorsConfiguration fixa — não é objetivo deste teste validar o path-matching interno do
 * Spring (UrlBasedCorsConfigurationSource), só a lógica própria do filtro de ecoar (ou não)
 * os headers de CORS quando bloqueia com 429.
 *
 * Chama doFilterInternal(...) diretamente (protected, mesmo pacote) em vez do doFilter(...)
 * público herdado de OncePerRequestFilter, evitando depender do mecanismo de "already
 * filtered" baseado em atributos de request (que exigiria simular um mock de request com
 * comportamento de mapa de atributos).
 */
class IpRateLimitFilterTest {

    private static final String ORIGEM_PERMITIDA = "http://localhost:3000";

    private CorsConfigurationSource corsSourceComOrigemPermitida() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(ORIGEM_PERMITIDA));
        config.setAllowCredentials(true);

        CorsConfigurationSource source = mock(CorsConfigurationSource.class);
        when(source.getCorsConfiguration(any())).thenReturn(config);
        return source;
    }

    private HttpServletResponse mockResponseComWriter() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return response;
    }

    // ── OPTIONS nunca consome token / nunca é bloqueado ─────────────────────

    @Test
    void options_nuncaConsomeTokenMesmoEstourandoCapacidade() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getRemoteAddr()).thenReturn("9.9.9.9");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        // capacity=1: se OPTIONS consumisse token, a 2ª chamada já bloquearia.
        filter.doFilterInternal(request, response, chain);
        filter.doFilterInternal(request, response, chain);
        filter.doFilterInternal(request, response, chain);

        verify(chain, times(3)).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void options_naoConsomeToken_proximoGetDoMesmoIpAindaTemBucketCheio() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());

        HttpServletRequest options = mock(HttpServletRequest.class);
        when(options.getMethod()).thenReturn("OPTIONS");
        when(options.getRemoteAddr()).thenReturn("9.9.9.9");
        FilterChain chain = mock(FilterChain.class);

        // Várias preflights, capacity=1 — nenhuma deveria gastar o único token do balde.
        filter.doFilterInternal(options, mock(HttpServletResponse.class), chain);
        filter.doFilterInternal(options, mock(HttpServletResponse.class), chain);

        HttpServletRequest get = mock(HttpServletRequest.class);
        when(get.getMethod()).thenReturn("GET");
        when(get.getRemoteAddr()).thenReturn("9.9.9.9");
        HttpServletResponse getResponse = mock(HttpServletResponse.class);

        filter.doFilterInternal(get, getResponse, chain);

        verify(chain, times(3)).doFilter(any(), any());
        verify(getResponse, never()).setStatus(anyInt());
    }

    // ── bloqueio 429 após esgotar capacidade ────────────────────────────────

    @Test
    void get_recebe429AposEsgotarCapacidade() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(2, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        // 2 primeiras (capacity=2) passam livremente — endpoint em si é irrelevante pro
        // filtro (não inspeciona path), simula tanto rota autenticada quanto pública
        // (ex: /actuator/health), já que o registro é addUrlPatterns("/*").
        filter.doFilterInternal(request, mock(HttpServletResponse.class), chain);
        filter.doFilterInternal(request, mock(HttpServletResponse.class), chain);
        verify(chain, times(2)).doFilter(any(), any());

        // 3ª requisição estoura a capacidade — bloqueada com 429, corpo problem+json.
        HttpServletResponse blockedResponse = mockResponseComWriter();
        filter.doFilterInternal(request, blockedResponse, chain);

        verify(chain, times(2)).doFilter(any(), any()); // não avançou pra próxima na cadeia
        verify(blockedResponse).setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        verify(blockedResponse).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    void get_bloqueado_corpoContemProblemDetailComStatus429() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        ObjectMapper objectMapper = new ObjectMapper();
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, objectMapper, corsSourceComOrigemPermitida());
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        filter.doFilterInternal(request, mock(HttpServletResponse.class), chain); // consome o único token

        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, chain);

        assertTrue(body.toString().contains("\"status\":429"));
        assertTrue(body.toString().contains("Muitas requisições"));
    }

    // ── fallback de IP quando getRemoteAddr() é null ────────────────────────

    @Test
    void remoteAddrNulo_naoLancaEUsaIpUnknownComoChaveDoBucket() {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn(null);

        assertDoesNotThrow(() -> filter.doFilterInternal(request, mock(HttpServletResponse.class), chain));
    }

    // ── headers de CORS na resposta 429 ──────────────────────────────────────

    @Test
    void respostaBloqueada_incluiAccessControlAllowOriginQuandoOrigemBateComCorsConfigurado() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(request.getHeader("Origin")).thenReturn(ORIGEM_PERMITIDA);

        filter.doFilterInternal(request, mock(HttpServletResponse.class), chain); // consome o único token

        HttpServletResponse blockedResponse = mockResponseComWriter();
        filter.doFilterInternal(request, blockedResponse, chain);

        // Sem isso, o browser descartaria a resposta 429 como erro de CORS antes do
        // frontend conseguir ler o corpo e mostrar uma mensagem legível.
        verify(blockedResponse).setHeader("Access-Control-Allow-Origin", ORIGEM_PERMITIDA);
        verify(blockedResponse).setHeader("Access-Control-Allow-Credentials", "true");
    }

    @Test
    void respostaBloqueada_naoIncluiHeaderCorsQuandoOrigemNaoEstaNaListaPermitida() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(request.getHeader("Origin")).thenReturn("http://evil.example.com");

        filter.doFilterInternal(request, mock(HttpServletResponse.class), chain); // consome o único token

        HttpServletResponse blockedResponse = mockResponseComWriter();
        filter.doFilterInternal(request, blockedResponse, chain);

        verify(blockedResponse, never()).setHeader(eq("Access-Control-Allow-Origin"), any());
        verify(blockedResponse, never()).setHeader(eq("Access-Control-Allow-Credentials"), any());
    }

    @Test
    void respostaBloqueada_semHeaderOrigin_naoLancaENaoSetaHeadersCors() throws Exception {
        GlobalIpRateLimiter limiter = new GlobalIpRateLimiter(1, 1000, Clock.systemUTC());
        IpRateLimitFilter filter = new IpRateLimitFilter(limiter, new ObjectMapper(), corsSourceComOrigemPermitida());
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(request.getHeader("Origin")).thenReturn(null); // ex: requisição same-origin/curl, sem header Origin

        filter.doFilterInternal(request, mock(HttpServletResponse.class), chain); // consome o único token

        HttpServletResponse blockedResponse = mockResponseComWriter();
        assertDoesNotThrow(() -> filter.doFilterInternal(request, blockedResponse, chain));

        verify(blockedResponse, never()).setHeader(eq("Access-Control-Allow-Origin"), any());
    }
}
