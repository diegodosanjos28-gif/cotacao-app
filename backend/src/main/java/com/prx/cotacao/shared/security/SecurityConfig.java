package com.prx.cotacao.shared.security;

import com.prx.cotacao.identidade.auth.dto.JwtAuthFilter;
import com.prx.cotacao.shared.tenant.TenantFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final TenantFilter tenantFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final AdminAccessDeniedHandler adminAccessDeniedHandler;

    // Comma-separated. @Value com List<String> não suporta YAML list notation.
    // Ex: "http://localhost:3000,https://app.prx.com.br"
    // Sem default de propósito: dev/test setam o valor explicitamente em
    // application-dev.yml; prod deve setar via APP_CORS_ALLOWED_ORIGINS
    // (application-prod.yml) — se não setar, o boot falha aqui em vez de servir com
    // CORS mal configurado silenciosamente. Achado do security-reviewer em revisão de
    // 2026-07-09, corrigido em 2026-07-11.
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, TenantFilter tenantFilter,
                           RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                           AdminAccessDeniedHandler adminAccessDeniedHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.tenantFilter = tenantFilter;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.adminAccessDeniedHandler = adminAccessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(adminAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Webhook Meta: nunca manda JWT — a única fronteira de auth é a
                        // assinatura HMAC validada dentro do controller (não aqui).
                        // TenantContext NÃO é setado via ENDPOINTS_SEM_TENANT do
                        // TenantFilter (ao contrário de /auth/login) — a resolução do
                        // tenant é manual dentro de WhatsappWebhookService, confinando o
                        // bypass de RLS à menor janela possível (só a busca de
                        // telefone), não à requisição inteira.
                        .requestMatchers(HttpMethod.GET, "/whatsapp/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/whatsapp/webhook").permitAll()
                        // Só existe quando @Profile("!prod") registra o bean do
                        // controller de simulação — não é um filtro condicional que
                        // possa vazar por engano, a rota simplesmente não existe em prod.
                        .requestMatchers("/dev/whatsapp/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN_PRX")
                        // ADMIN_PRX não tem tenant — sem isso, cairia no
                        // .anyRequest().authenticated() genérico e um ADMIN_PRX
                        // autenticado bateria na rota sem erro claro (achado do
                        // security-reviewer).
                        .requestMatchers("/usuarios/me/**").hasRole("OPERADOR_CLIENTE")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, JwtAuthFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
