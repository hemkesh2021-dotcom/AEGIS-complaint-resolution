package com.aegis.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS restricted to the configured frontend origin(s) — not a wildcard — so only
 * the AEGIS console/portal may call the API from a browser. Configure with
 * {@code AEGIS_ALLOWED_ORIGINS} (comma-separated).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${aegis.allowed-origins:http://localhost:8088}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
