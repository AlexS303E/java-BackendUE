package com.game.backend;

import com.game.backend.common.config.ProductionHardeningValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionHardeningValidatorTest {
    @Test
    void shouldRejectDevSecretsForProductionProfiles() {
        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
                new String[]{"prod"},
                "dev-admin-token",
                "strong-production-jwt-secret-value"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.token");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
                new String[]{"production"},
                "strong-production-admin-token",
                "dev-only-change-me-dev-only-change-me"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.auth.jwt-secret");

        assertThatThrownBy(() -> ProductionHardeningValidator.validateForStartup(
                new String[]{"prod"},
                "strong-production-admin-token",
                "short"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void shouldAllowSafeProductionAndLocalDevSecrets() {
        assertThatCode(() -> ProductionHardeningValidator.validateForStartup(
                new String[]{"prod"},
                "strong-production-admin-token",
                "strong-production-jwt-secret-value"
        ))
                .doesNotThrowAnyException();

        assertThatCode(() -> ProductionHardeningValidator.validateForStartup(
                new String[]{"local"},
                "dev-admin-token",
                "dev-only-change-me-dev-only-change-me"
        ))
                .doesNotThrowAnyException();
    }
}
