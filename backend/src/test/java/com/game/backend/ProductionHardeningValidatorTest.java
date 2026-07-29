package com.game.backend;

import com.game.backend.common.config.ProductionHardeningValidator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionHardeningValidatorTest {
    private static final String ADMIN_TOKEN = "strong-production-admin-token";
    private static final String PRIVATE_KEY = "file:D:/secure/jwt-private.pem";
    private static final String PUBLIC_KEY = "file:D:/secure/jwt-public.pem";
    private static final String CORS = "https://game.example";
    private static final String ADMIN_CIDRS = "10.0.0.0/8,127.0.0.1/32";

    @Test
    void shouldRejectUnsafeProductionConfiguration() {
        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            "dev-admin-token",
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.admin.token");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            "",
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.auth.jwt-private-key");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            "",
            ADMIN_CIDRS
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.cors.allowed-origins");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ""
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.admin.allowed-cidrs");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----",
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("external secret material");
    }

    @Test
    void shouldRejectUnsafeProductionRateLimitConfiguration() {
        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS,
            false,
            Duration.ofMinutes(1),
            60,
            600,
            120
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.rate-limit.enabled");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS,
            true,
            Duration.ZERO,
            60,
            600,
            120
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.rate-limit.window");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS,
            true,
            Duration.ofMinutes(1),
            0,
            600,
            120
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.rate-limit.auth-limit");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS,
            true,
            Duration.ofMinutes(1),
            60,
            600,
            120,
            false
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.rate-limit.fail-closed-on-redis-error");
    }

    @Test
    void shouldAllowSafeProductionAndLocalDevConfiguration() {
        assertThatCode(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"prod"},
            ADMIN_TOKEN,
            PRIVATE_KEY,
            PUBLIC_KEY,
            CORS,
            ADMIN_CIDRS,
            true,
            Duration.ofMinutes(1),
            60,
            600,
            120
        ))
            .doesNotThrowAnyException();

        assertThatCode(() -> ProductionHardeningValidator.validateForStartup(
            new String[]{"local"},
            "dev-admin-token",
            "",
            "",
            "",
            ""
        ))
            .doesNotThrowAnyException();
    }
}
