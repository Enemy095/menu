package com.salamu.menu.identity.internal.security;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final List<String> READ_REQUEST_HEADERS = List.of(
            "Accept",
            "X-Correlation-Id",
            "traceparent",
            "tracestate");
    private static final List<String> ORDER_REQUEST_HEADERS = List.of(
            "Accept",
            "Content-Type",
            "Idempotency-Key",
            "X-Correlation-Id",
            "traceparent",
            "tracestate");
    private static final List<String> EXPOSED_RESPONSE_HEADERS = List.of(
            "X-Correlation-Id",
            "X-Trace-Id",
            "ETag",
            "Cache-Control",
            "Cache-Tag",
            "Surrogate-Key",
            "Retry-After");

    @Bean
    @Order(1)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().denyAll())
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            UrlBasedCorsConfigurationSource corsConfigurationSource,
            SecurityProperties properties,
            TrustedClientIpResolver clientIpResolver,
            OrderRateLimiter rateLimiter,
            SecurityErrorWriter errorWriter) throws Exception {
        var authorityFilter = new RequestAuthorityFilter(properties, errorWriter);
        var rateLimitFilter = new PublicOrderRateLimitFilter(clientIpResolver, rateLimiter, errorWriter);
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.ignoringRequestMatchers(SecurityRequestMatchers.PUBLIC_ORDER))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                SecurityRequestMatchers.PUBLIC_CONFIG,
                                SecurityRequestMatchers.PUBLIC_AVAILABILITY,
                                SecurityRequestMatchers.PUBLIC_MENU,
                                SecurityRequestMatchers.PUBLIC_ORDER,
                                SecurityRequestMatchers.LIVENESS,
                                SecurityRequestMatchers.READINESS)
                        .permitAll()
                        .anyRequest().denyAll())
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(authorityFilter, CorsFilter.class)
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        var allowedOrigins = new ArrayList<>(properties.cors().allowedOrigins());
        source.registerCorsConfiguration(
                SecurityRequestMatchers.PUBLIC_AVAILABILITY_PATH,
                corsConfiguration(allowedOrigins, List.of("GET"), READ_REQUEST_HEADERS));
        source.registerCorsConfiguration(
                SecurityRequestMatchers.PUBLIC_CONFIG_PATH,
                corsConfiguration(allowedOrigins, List.of("GET"), READ_REQUEST_HEADERS));
        source.registerCorsConfiguration(
                SecurityRequestMatchers.PUBLIC_MENU_PATH,
                corsConfiguration(allowedOrigins, List.of("GET"), READ_REQUEST_HEADERS));
        source.registerCorsConfiguration(
                SecurityRequestMatchers.PUBLIC_ORDER_PATH,
                corsConfiguration(allowedOrigins, List.of("POST"), ORDER_REQUEST_HEADERS));
        return source;
    }

    private static CorsConfiguration corsConfiguration(
            List<String> allowedOrigins,
            List<String> allowedMethods,
            List<String> allowedHeaders) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(allowedMethods);
        configuration.setAllowedHeaders(allowedHeaders);
        configuration.setExposedHeaders(EXPOSED_RESPONSE_HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        return configuration;
    }
}
