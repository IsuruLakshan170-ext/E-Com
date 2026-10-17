package com.ecom.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.ecom.apikey.ApiKeyService;
import com.ecom.audit.AuditService;
import com.ecom.config.AppProperties;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyService apiKeyService, ProblemWriter problemWriter,
                                    AppProperties props, RateLimiter rateLimiter, UserTokenStateCache tokenStateCache,
                                    AuditService audit) throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(problemWriter);

        http
                // Stateless bearer/API-key auth, no cookies -> CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**",
                                "/api/v1/categories", "/api/v1/categories/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Deny by default: anything not listed above is unreachable.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new RestAccessDeniedHandler(problemWriter, audit)))
                // 1. coarse per-IP flood guard, before any authentication work
                .addFilterBefore(new IpRateLimitFilter(rateLimiter, props.security().rateLimit(), problemWriter, audit),
                        UsernamePasswordAuthenticationFilter.class)
                // 2. API key authentication (JWTs are handled by the resource server's bearer filter)
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyService, problemWriter, audit),
                        BearerTokenAuthenticationFilter.class)
                // 3. reject JWTs of disabled/changed accounts, then 4. per-user / per-API-key rate limit.
                // Both are anchored to built-in filters only, so the order is deterministic.
                .addFilterAfter(new TokenStateFilter(tokenStateCache, problemWriter),
                        BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new PrincipalRateLimitFilter(rateLimiter, props.security().rateLimit(), problemWriter, audit),
                        AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.security().cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "Idempotency-Key", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Location", "Idempotent-Replayed"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
