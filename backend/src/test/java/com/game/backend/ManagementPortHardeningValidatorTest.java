package com.game.backend;

import com.game.backend.common.config.ManagementPortHardeningValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementPortHardeningValidatorTest {
    @Test
    void shouldRejectMissingOrSharedProductionManagementPort() {
        assertThatThrownBy(() -> ManagementPortHardeningValidator.validateForStartup(
            new String[]{"prod"},
            8080,
            -1,
            9443
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires management.server.port");

        assertThatThrownBy(() -> ManagementPortHardeningValidator.validateForStartup(
            new String[]{"production"},
            8080,
            8080,
            9443
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must differ from server.port");

        assertThatThrownBy(() -> ManagementPortHardeningValidator.validateForStartup(
            new String[]{"prod"},
            8080,
            9443,
            9443
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must differ from app.server-auth.mtls.port");
    }

    @Test
    void shouldAllowDedicatedProductionPortAndIgnoreLocalTopology() {
        assertThatCode(() -> ManagementPortHardeningValidator.validateForStartup(
            new String[]{"prod"},
            8080,
            8081,
            9443
        )).doesNotThrowAnyException();

        assertThatCode(() -> ManagementPortHardeningValidator.validateForStartup(
            new String[]{"local"},
            8080,
            -1,
            8080
        )).doesNotThrowAnyException();
    }
}
