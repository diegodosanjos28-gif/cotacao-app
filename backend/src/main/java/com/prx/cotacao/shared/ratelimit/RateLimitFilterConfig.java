package com.prx.cotacao.shared.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class RateLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<IpRateLimitFilter> ipRateLimitFilter(
            GlobalIpRateLimiter limiter, ObjectMapper objectMapper, CorsConfigurationSource corsConfigurationSource) {
        FilterRegistrationBean<IpRateLimitFilter> registration =
                new FilterRegistrationBean<>(new IpRateLimitFilter(limiter, objectMapper, corsConfigurationSource));
        // HIGHEST_PRECEDENCE: precisa rodar antes da cadeia inteira do Spring Security
        // (CORS/JWT/TenantFilter), senão uma rajada de flood ainda pagaria o custo
        // dessas etapas antes de ser barrada.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
