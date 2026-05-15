package com.game.backend.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationSourceTest {
    @Test
    void shouldRestrictCorsToConfiguredOriginsAndHeaders() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://game.example", "https://admin.example"));

        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(properties);
        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/auth/login"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("https://game.example", "https://admin.example");
        assertThat(configuration.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).contains("Authorization", "Content-Type", "If-Match", "Idempotency-Key");
        assertThat(configuration.getExposedHeaders()).contains("ETag", "Retry-After");
        assertThat(configuration.getAllowCredentials()).isFalse();
    }
}
